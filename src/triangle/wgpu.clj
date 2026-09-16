(ns triangle.wgpu
  "Bindings for the WebGPU C API (webgpu.h) plus the little struct
  builders the triangle demo needs. Struct layouts and enum values were
  taken from webgpu.h of wgpu-native 22.1.0.5.

  Calling conventions used throughout:

  - Handles cross the FFM boundary as opaque values: either a raw long
    address or a MemorySegment; triangle.ffi/function wraps both into
    pointer arguments, so code here can pass handles around freely.
  - Every descriptor buffer is zero-filled, which is also what the C
    API expects for unset members (NULL pointers, zero counts, default
    enum values that live at 0)."
  (:require [clojure.test :refer [deftest is testing]]
            [triangle.ffi :as ffi]))

;; ── enum values (webgpu.h)

(def window-system
  "WGPUSType values for the per-platform surface descriptors."
  {:cocoa 0x1      ; WGPUSType_SurfaceDescriptorFromMetalLayer
   :windows 0x2    ; WGPUSType_SurfaceDescriptorFromWindowsHWND
   :x11 0x3        ; WGPUSType_SurfaceDescriptorFromXlibWindow
   :wayland 0x8})  ; WGPUSType_SurfaceDescriptorFromWaylandSurface

(def shader-stage-type
  "WGPUSType_ShaderModuleWGSLDescriptor."
  0x6)

(def ^:private bgra8-unorm-srgb
  "WGPUTextureFormat value. If the triangle comes out black on a machine
  where this format is unsupported, try 0x17 (B8G8R8A8Unorm) or query
  wgpuSurfaceGetCapabilities."
  0x18)

(def ^:private render-attachment-usage
  "WGPUTextureUsage_RenderAttachment | WGPUTextureUsage_CopySrc."
  0x11)

;; ── the WGSL triangle shader
;;
;; No vertex buffers: clip-space positions and per-corner colors are
;; computed from the builtin vertex index. The fragment stage receives
;; the (hardware-interpolated) corner colors and forwards them.

(def triangle-wgsl
  "struct VertexOut {
  @builtin(position) position: vec4<f32>,
  @location(0) color: vec3<f32>,
}

@vertex
fn vertex_main(@builtin(vertex_index) vertex_index: u32) -> VertexOut {
  var out: VertexOut;
  if (vertex_index == 0u) {
    out.position = vec4<f32>(0.0, 0.5, 0.0, 1.0);
    out.color = vec3<f32>(1.0, 0.0, 0.0);
  } else if (vertex_index == 1u) {
    out.position = vec4<f32>(-0.5, -0.25, 0.0, 1.0);
    out.color = vec3<f32>(0.0, 1.0, 0.0);
  } else {
    out.position = vec4<f32>(0.5, -0.25, 0.0, 1.0);
    out.color = vec3<f32>(0.0, 0.0, 1.0);
  }
  return out;
}

@fragment
fn fragment_main(input: VertexOut) -> @location(0) vec4<f32> {
  return vec4<f32>(input.color, 1.0);
}
")
(def data-triangle-wgsl
  "struct DataOut {
  @builtin(position) position: vec4<f32>,
  @location(0) color: vec4<f32>,
}

@vertex
fn vertex_data_main(@location(0) position: vec2<f32>,
                    @location(1) color: vec4<f32>) -> DataOut {
  var out: DataOut;
  out.position = vec4<f32>(position, 0.0, 1.0);
  out.color = color;
  return out;
}

@fragment
fn fragment_data_main(input: DataOut) -> @location(0) vec4<f32> {
  return input.color;
}
")

;; ── the flat function table

(defn make-api
  "Build the map of WebGPU entry points. The native library must be
  loaded first with triangle.ffi/load-library!. Each entry is a plain
  Clojure function; its docstring-free signature is documented inline."
  []
  (let [f ffi/function]
    {:create-instance          (f "wgpuCreateInstance" :ptr [:ptr])
     :process-events           (f "wgpuInstanceProcessEvents" nil [:ptr])
     :enumerate-adapters       (f "wgpuInstanceEnumerateAdapters" :u64 [:ptr :ptr :ptr])
     :create-surface           (f "wgpuInstanceCreateSurface" :ptr [:ptr :ptr])
     :configure-surface        (f "wgpuSurfaceConfigure" nil [:ptr :ptr])
     :get-current-texture      (f "wgpuSurfaceGetCurrentTexture" nil [:ptr :ptr])
     :present                  (f "wgpuSurfacePresent" nil [:ptr])
     :request-device           (f "wgpuAdapterRequestDevice" nil [:ptr :ptr :ptr :ptr])
     :get-queue                (f "wgpuDeviceGetQueue" :ptr [:ptr])
     :create-buffer            (f "wgpuDeviceCreateBuffer" :ptr [:ptr :ptr])
     :queue-write-buffer       (f "wgpuQueueWriteBuffer" nil [:ptr :ptr :u64 :ptr :u64])
     :release-buffer           (f "wgpuBufferRelease" nil [:ptr])
     :create-shader-module     (f "wgpuDeviceCreateShaderModule" :ptr [:ptr :ptr])
     :create-render-pipeline   (f "wgpuDeviceCreateRenderPipeline" :ptr [:ptr :ptr])
     :create-command-encoder   (f "wgpuDeviceCreateCommandEncoder" :ptr [:ptr :ptr])
     :begin-render-pass        (f "wgpuCommandEncoderBeginRenderPass" :ptr [:ptr :ptr])
     :end-render-pass          (f "wgpuRenderPassEncoderEnd" nil [:ptr])
     :draw                     (f "wgpuRenderPassEncoderDraw" nil [:ptr :u32 :u32 :u32 :u32])
     :set-pipeline             (f "wgpuRenderPassEncoderSetPipeline" nil [:ptr :ptr])
     :set-vertex-buffer        (f "wgpuRenderPassEncoderSetVertexBuffer" nil [:ptr :u32 :ptr :u64 :u64])
     :encoder-finish           (f "wgpuCommandEncoderFinish" :ptr [:ptr :ptr])
     :submit                   (f "wgpuQueueSubmit" nil [:ptr :u64 :ptr])
     :create-texture-view      (f "wgpuTextureCreateView" :ptr [:ptr :ptr])
     :release-texture          (f "wgpuTextureRelease" nil [:ptr])
     :release-texture-view     (f "wgpuTextureViewRelease" nil [:ptr])
     :release-render-pass      (f "wgpuRenderPassEncoderRelease" nil [:ptr])
     :release-command-encoder  (f "wgpuCommandEncoderRelease" nil [:ptr])
     :release-command-buffer   (f "wgpuCommandBufferRelease" nil [:ptr])}))

(comment
  ;; Shape check for make-api (run after loading the library):
  ;; (def api (make-api))
  ;; ((:create-instance api) nil) => non-NULL MemorySegment
  )

;; ── struct builders
;;
;; Each builder fills a zeroed buffer with the byte layout of one C
;; struct. Offsets are commented with the member they point at.

(defn platform-descriptor!
  "Build a WGPUSurfaceDescriptorFrom<Platform> struct: the per-platform
  struct that gets chained into WGPUSurfaceDescriptor.nextInChain. They
  all begin with the 16-byte chain (a NULL next pointer, then the
  WGPUSType tag and its padding) and go on with their pointer members,
  written in the order the C struct declares them: display then window
  for X11, Wayland and Win32, and the CAMetalLayer for Cocoa. kind is
  one of :x11, :wayland, :windows or :cocoa; members are those members."
  [kind members]
  (let [members (vec members)
        descriptor (ffi/allocate! (* 8 (+ 2 (count members))))]
    (ffi/write-u32! descriptor 8 (kind window-system))
    (doseq [[index member] (map-indexed vector members)]
      (ffi/write-pointer! descriptor (+ 16 (* 8 index)) member))
    descriptor))

(defn surface-descriptor!
  "Build a WGPUSurfaceDescriptor (16 bytes) chaining platform-descriptor."
  [platform-descriptor]
  (doto (ffi/allocate! 16)
    (ffi/write-pointer! 0 platform-descriptor)))

(defn surface-configuration!
  "Build a WGPUSurfaceConfiguration (56 bytes) for a surface of the
  given pixel size: default BGRA8 format, render-target usage, FIFO
  present mode (0 in this header revision)."
  [device width height]
  (doto (ffi/allocate! 56)
    (ffi/write-pointer! 8 device)            ; .device
    (ffi/write-u32! 16 bgra8-unorm-srgb)     ; .format
    (ffi/write-u32! 20 render-attachment-usage) ; .usage
    (ffi/write-u32! 44 width)                 ; .width
    (ffi/write-u32! 48 height)                ; .height
    (ffi/write-u32! 52 0)))                    ; .presentMode = Fifo

(defn shader-module-descriptor!
  "Build the shader module descriptor pair (24 + 32 bytes):
  WGPUShaderModuleDescriptor {nextInChain, label, hintCount, hints}
  chaining a WGPUShaderModuleWGSLDescriptor {chain, code}."
  [wgsl-source]
  (let [wgsl (doto (ffi/allocate! 24)
               (ffi/write-u32! 8 shader-stage-type) ; chain.sType
               (ffi/write-pointer! 16 wgsl-source)) ; .code
        descriptor (doto (ffi/allocate! 32)
                     (ffi/write-pointer! 0 wgsl))]  ; nextInChain
    descriptor))

(defn buffer-descriptor!
  "Build a WGPUBufferDescriptor (40 bytes) for a buffer of the given
  size in bytes, by default with VERTEX | COPY_DST usage (0x28), so
  the vertex shader can read it and queue-write-buffer can fill it
  later. Pass a different usage number to override."
  ([size]
   (buffer-descriptor! 0x28 size))
  ([usage size]
   (doto (ffi/allocate! 40)
     (ffi/write-u32! 16 usage)  ; .usage
     (ffi/write-u64! 24 size)   ; .size
     (ffi/write-u32! 32 0))))   ; .mappedAtCreation = false

(defn color-target-state!
  "Build a WGPUColorTargetState (32 bytes, writeMask 8 bytes): one
  colour attachment target, default blending, writeMask = All."
  []
  (doto (ffi/allocate! 32)
    (ffi/write-u32! 8 bgra8-unorm-srgb) ; .format
    (ffi/write-u32! 24 0xF)))            ; .writeMask = All

(defn fragment-state!
  "Build a WGPUFragmentState (56 bytes) pointing at the shader module
  and one colour target, with the given fragment entry point."
  ([shader-module color-target]
   (fragment-state! shader-module color-target "fragment_main"))
  ([shader-module color-target entry-point]
   (doto (ffi/allocate! 56)
     (ffi/write-pointer! 8 shader-module)            ; .module
     (ffi/write-pointer! 16 (ffi/string! entry-point)) ; .entryPoint
     (ffi/write-u64! 40 1)                            ; .targetCount
     (ffi/write-pointer! 48 color-target))))            ; .targets

(defn render-pipeline-descriptor!
  "Build a flat WGPURenderPipelineDescriptor (144 bytes) for the
  triangle shader. The vertex, primitive and multisample states live
  inside it by value; fragment is a pointer to its own struct."
  [shader-module fragment-state]
  (doto (ffi/allocate! 144)
    (ffi/write-pointer! 32 shader-module)                  ; vertex.module
    (ffi/write-pointer! 40 (ffi/string! "vertex_main"))    ; vertex.entryPoint
    (ffi/write-u32! 88 0x3)                                ; primitive.topology = TriangleList
    (ffi/write-u32! 120 1)                                 ; multisample.count
    (ffi/write-u32! 124 0xFFFFFFFF)                        ; multisample.mask
    (ffi/write-pointer! 136 fragment-state)))              ; fragment

(defn create-triangle-shader!
  "Compile the WGSL triangle shader on a device."
  [api device]
  ((:create-shader-module api) device
                               (shader-module-descriptor! (ffi/string! triangle-wgsl))))

(defn create-triangle-pipeline!
  "Create the render pipeline for the triangle shader (automatic bind
  group layout, one BGRA8 colour target)."
  [api device shader-module]
  (let [color-target (color-target-state!)
        fragment (fragment-state! shader-module color-target)]
    ((:create-render-pipeline api) device
                                   (render-pipeline-descriptor! shader-module fragment))))

;; ── the data-driven pipeline: vertices from a vertex buffer

(def float32x2-vertex-format 0x14) ; WGPUVertexFormat_Float32x2
(def float32x4-vertex-format 0x16) ; WGPUVertexFormat_Float32x4
(def vertex-bytes-per-vertex
  "Stride of one vertex in the vertex buffer: two floats position,
  four floats color."
  24)

(defn vertex-attributes!
  "Build the two WGPUVertexAttribute entries (24 bytes each):
  position (float32x2) at shader location 0, color (float32x4) at
  shader location 1."
  []
  (doto (ffi/allocate! 48)
    (ffi/write-u32! 0 float32x2-vertex-format) ; .format
    (ffi/write-u64! 8 0)                        ; .offset
    (ffi/write-u32! 16 0)                       ; .shaderLocation
    (ffi/write-u32! 24 float32x4-vertex-format) ; .format
    (ffi/write-u64! 32 8)                       ; .offset
    (ffi/write-u32! 40 1)))                     ; .shaderLocation

(defn vertex-buffer-layout!
  "Build a WGPUVertexBufferLayout (32 bytes) describing the interleaved
  24-byte vertex stride. stepMode stays 0, which is Vertex."
  []
  (doto (ffi/allocate! 32)
    (ffi/write-u64! 0 vertex-bytes-per-vertex) ; .arrayStride
    (ffi/write-u64! 16 2)                       ; .attributeCount
    (ffi/write-pointer! 24 (vertex-attributes!)))) ; .attributes

(defn data-render-pipeline-descriptor!
  "Build a flat WGPURenderPipelineDescriptor (144 bytes) for the
  data-driven triangle shader: like render-pipeline-descriptor!, but
  the vertex state names vertex_data_main and carries one vertex
  buffer layout."
  [shader-module fragment-state buffer-layout]
  (doto (ffi/allocate! 144)
    (ffi/write-pointer! 32 shader-module)                  ; vertex.module
    (ffi/write-pointer! 40 (ffi/string! "vertex_data_main")) ; vertex.entryPoint
    (ffi/write-u64! 64 1)                                   ; vertex.bufferCount
    (ffi/write-pointer! 72 buffer-layout)                   ; vertex.buffers
    (ffi/write-u32! 88 0x3)                                 ; primitive.topology
    (ffi/write-u32! 120 1)                                  ; multisample.count
    (ffi/write-u32! 124 0xFFFFFFFF)                         ; multisample.mask
    (ffi/write-pointer! 136 fragment-state)))               ; fragment

(defn create-data-shader!
  "Compile the vertex-color shader on a device."
  [api device]
  ((:create-shader-module api) device
                               (shader-module-descriptor! (ffi/string! data-triangle-wgsl))))

(defn create-data-pipeline!
  "Create the data-driven render pipeline: a vertex buffer carries
  position and color per vertex, one BGRA8 colour target, automatic
  bind group layout."
  [api device shader-module]
  (let [layout (vertex-buffer-layout!)
        color-target (color-target-state!)
        fragment (fragment-state! shader-module color-target "fragment_data_main")]
    ((:create-render-pipeline api) device
                                   (data-render-pipeline-descriptor! shader-module fragment layout))))

;; ── Clojure triangle data -> vertex floats

(defn color-component
  "Read one color component from Clojure data: integers above 1 are
  0..255 and scale down, anything else is already 0..1."
  [component]
  (if (and (integer? component) (> component 1))
    (/ (double component) 255)
    (double component)))

(defn vertex-floats
  "One vertex of Clojure data — [x y r g b], [x y r g b a] or
  [x y [r g b a]] with the color nested (alpha optional) — as the six
  floats the vertex layout wants (alpha defaults to 1)."
  [vertex]
  (let [[x y & more] vertex
        colors (if (sequential? (first more)) (first more) more)
        [r g b a] (take 4 (concat colors [1.0 1.0 1.0]))]
    [(double x) (double y)
     (color-component r) (color-component g)
     (color-component b) (color-component a)]))

(defn triangle-vertices
  "Flatten Clojure triangle data into a flat vector of floats, six per
  vertex: either a flat seq of vertices or a seq of triangles of three
  vertices each."
  [triangles]
  (->> (if (sequential? (first (first triangles)))
         (apply concat triangles)
         triangles)
       (mapcat vertex-floats)
       (into [])))

(deftest test-color-component
  (testing "integers above 1 are 0..255, everything else 0..1"
    (is (= 1.0 (color-component 255)))
    (is (= 0.0 (color-component 0)))
    (is (= 1.0 (color-component 1)))
    (is (= (/ 128.0 255) (color-component 128)))
    (is (= 0.5 (color-component 0.5)))))

(deftest test-vertex-floats
  (testing "five members pad alpha with 1, six pass through"
    (is (= [0.1 0.2 1.0 0.0 (/ 128.0 255) 1.0]
           (vertex-floats [0.1 0.2 255 0 128])))
    (is (= [0.0 0.0 1.0 0.5 0.0 0.25]
           (vertex-floats [0 0 1.0 0.5 0.0 0.25])))
    (is (= [0.5 0.5 (/ 127.0 255) (/ 127.0 255) (/ 127.0 255) 1.0]
           (vertex-floats [0.5 0.5 [127 127 127]])))))

(deftest test-triangle-vertices
  (testing "nested triangles and flat vertices flatten the same"
    (is (= (triangle-vertices [[0 0 255 0 0] [1 0 0 255 0] [0 1 0 0 255]])
           (triangle-vertices [[[0 0 255 0 0] [1 0 0 255 0] [0 1 0 0 255]]])))
    (is (= 36 (count (triangle-vertices
                      [[[0 0 255 0 0] [1 0 0 255 0] [0 1 0 0 255]]
                       [[1 1 0 0 255] [0 1 255 0 0] [1 0 0 255 0]]]))))))

;; ── per-frame plumbing

(defn acquire-swapchain-texture
  "Call wgpuSurfaceGetCurrentTexture. Returns the acquired texture
  handle (or nil if the swapchain has no free image right now), paired
  with its WGPUSurfaceTexture result buffer."
  [api surface]
  (let [acquired (ffi/allocate! 16)]
    ((:get-current-texture api) surface acquired)
    [(ffi/deref-pointer acquired 0) acquired]))

(defn render-attachment!
  "Build a WGPURenderPassColorAttachment (72 bytes) on the given
  texture view: Clear to the demo's dark grey, Store."
  [view]
  (doto (ffi/allocate! 72)
    (ffi/write-pointer! 8 view)   ; .view
    (ffi/write-u32! 32 1)         ; .loadOp = Clear
    (ffi/write-u32! 36 1)         ; .storeOp = Store
    (ffi/write-f64! 40 0.09)      ; .clearValue.r
    (ffi/write-f64! 48 0.09)      ; .clearValue.g
    (ffi/write-f64! 56 0.11)      ; .clearValue.b
    (ffi/write-f64! 64 1.0)))     ; .clearValue.a

(defn render-pass-descriptor!
  "Build a WGPURenderPassDescriptor (56 bytes) with one colour
  attachment."
  [attachment]
  (doto (ffi/allocate! 56)
    (ffi/write-u64! 16 1)               ; .colorAttachmentCount
    (ffi/write-pointer! 24 attachment))) ; .colorAttachments

(defn draw-triangle!
  "Record and submit one frame: render pass that clears to a dark grey,
  draws three procedural vertices, then submits and presents. Releases
  everything it created; does nothing when the swapchain has no image."
  [api device queue surface pipeline]
  (let [[texture acquired] (acquire-swapchain-texture api surface)]
    (when texture
      (let [view ((:create-texture-view api) texture nil)
            encoder ((:create-command-encoder api) device nil)
            pass ((:begin-render-pass api) encoder
                                           (render-pass-descriptor! (render-attachment! view)))
            _ ((:set-pipeline api) pass pipeline)
            _ ((:draw api) pass 3 1 0 0)
            _ ((:end-render-pass api) pass)
            _ ((:release-render-pass api) pass)
            command-buffer ((:encoder-finish api) encoder nil)
            commands (doto (ffi/pointer-array! 1)
                       (ffi/write-pointer! 0 command-buffer))]
        ((:submit api) queue 1 commands)
        ((:present api) surface)
        ((:release-command-buffer api) command-buffer)
        ((:release-command-encoder api) encoder)
        ((:release-texture-view api) view)
        ((:release-texture api) texture)))))

(defn vertex-data!
  "Copy the flat vertex floats (triangle-vertices) into freshly
  allocated native memory, four bytes each; returns the segment."
  [floats]
  (let [data (ffi/allocate! (* 4 (count floats)))]
    (doseq [[index float-value] (map-indexed vector floats)]
      (ffi/write-f32! data (* 4 index) float-value))
    data))

(defn draw-triangles!
  "Draw the given Clojure triangle data (triangle-vertices) as one
  frame with the data-driven pipeline: the vertices upload into a
  fresh vertex buffer, a render pass clears and draws them, the frame
  presents, everything releases. Positions are clip-space (x right,
  y up, -1..1); a vertex count that is not a multiple of three throws."
  [api device queue surface pipeline triangles]
  (let [floats (triangle-vertices triangles)
        vertex-count (quot (count floats) 6)]
    (when-not (zero? (rem vertex-count 3))
      (throw (ex-info "triangle data must hold a multiple of three vertices"
                      {:vertex-count vertex-count})))
    (let [data (vertex-data! floats)
          data-bytes (* 4 (count floats))
          buffer ((:create-buffer api) device (buffer-descriptor! data-bytes))]
      ((:queue-write-buffer api) queue buffer 0 data data-bytes)
      (let [[texture acquired] (acquire-swapchain-texture api surface)]
        (when texture
          (let [view ((:create-texture-view api) texture nil)
                encoder ((:create-command-encoder api) device nil)
                pass ((:begin-render-pass api) encoder
                                               (render-pass-descriptor! (render-attachment! view)))]
            ((:set-pipeline api) pass pipeline)
            ((:set-vertex-buffer api) pass 0 buffer 0 data-bytes)
            ((:draw api) pass vertex-count 1 0 0)
            ((:end-render-pass api) pass)
            ((:release-render-pass api) pass)
            (let [command-buffer ((:encoder-finish api) encoder nil)
                  commands (doto (ffi/pointer-array! 1)
                             (ffi/write-pointer! 0 command-buffer))]
              ((:submit api) queue 1 commands)
              ((:present api) surface)
              ((:release-command-buffer api) command-buffer)
              ((:release-command-encoder api) encoder)
              ((:release-texture-view api) view)
              ((:release-texture api) texture))))
        ((:release-buffer api) buffer)))))

(comment
  ;; Offsets used above, verified against webgpu.h (wgpu-native 22.1.0.5):
  ;; WGPUSurfaceConfiguration {nextInChain, device, format, usage, count,
  ;;   formats, alphaMode, width, height, presentMode} = 56 bytes
  ;; WGPURenderPassColorAttachment {nextInChain, view, depthSlice,
  ;;   resolveTarget, loadOp, storeOp, clearValue} = 72 bytes
  ;; WGPURenderPassDescriptor {chain, label, count, colorAttachments,
  ;;   depthAttachment, occlusion, timestamp} = 56 bytes
  ;; WGPUVertexState {nextInChain, module, entryPoint, constantCount,
  ;;   constants, bufferCount, buffers} — bufferCount at 64, buffers
  ;;   at 72 inside the 144-byte WGPURenderPipelineDescriptor
  ;; WGPUVertexBufferLayout {arrayStride, stepMode, attributeCount,
  ;;   attributes} = 32 bytes; WGPUVertexAttribute {format, offset,
  ;;   shaderLocation} = 24 bytes
  )
