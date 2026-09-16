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
  {:x11 0x3        ; WGPUSType_SurfaceDescriptorFromXlibWindow
   :wayland 0x8    ; WGPUSType_SurfaceDescriptorFromWaylandSurface
   :windows 0x2})  ; WGPUSType_SurfaceDescriptorFromWindowsHWND

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
     :create-shader-module     (f "wgpuDeviceCreateShaderModule" :ptr [:ptr :ptr])
     :create-render-pipeline   (f "wgpuDeviceCreateRenderPipeline" :ptr [:ptr :ptr])
     :create-command-encoder   (f "wgpuDeviceCreateCommandEncoder" :ptr [:ptr :ptr])
     :begin-render-pass        (f "wgpuCommandEncoderBeginRenderPass" :ptr [:ptr :ptr])
     :end-render-pass          (f "wgpuRenderPassEncoderEnd" nil [:ptr])
     :set-pipeline             (f "wgpuRenderPassEncoderSetPipeline" nil [:ptr :ptr])
     :draw                     (f "wgpuRenderPassEncoderDraw" nil [:ptr :u32 :u32 :u32 :u32])
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
  "Build a WGPUSurfaceDescriptorFrom<Platform> struct (32 bytes, the
  four share the layout {chain, member, member}): the per-platform
  struct that gets chained into WGPUSurfaceDescriptor.nextInChain.
  kind is :x11, :wayland or :windows."
  [kind member-1 member-2]
  (case kind
    (:x11 :wayland :windows)
    (let [descriptor (ffi/allocate! 32)]
      (ffi/write-u32! descriptor 8 (kind window-system))
      (ffi/write-pointer! descriptor 16 member-1)
      (ffi/write-pointer! descriptor 24 member-2)
      descriptor)))

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

(defn color-target-state!
  "Build a WGPUColorTargetState (32 bytes, writeMask 8 bytes): one
  colour attachment target, default blending, writeMask = All."
  []
  (doto (ffi/allocate! 32)
    (ffi/write-u32! 8 bgra8-unorm-srgb) ; .format
    (ffi/write-u32! 24 0xF)))            ; .writeMask = All

(defn fragment-state!
  "Build a WGPUFragmentState (56 bytes) pointing at the shader module
  and one colour target."
  [shader-module color-target]
  (doto (ffi/allocate! 56)
    (ffi/write-pointer! 8 shader-module)            ; .module
    (ffi/write-pointer! 16 (ffi/string! "fragment_main")) ; .entryPoint
    (ffi/write-u64! 40 1)                            ; .targetCount
    (ffi/write-pointer! 48 color-target)))            ; .targets

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

;; ── per-frame plumbing

(defn acquire-swapchain-texture
  "Call wgpuSurfaceGetCurrentTexture. Returns the acquired texture
  handle (or nil if the swapchain has no free image right now), paired
  with its WGPUSurfaceTexture result buffer."
  [api surface]
  (let [acquired (ffi/allocate! 16)]
    ((:get-current-texture api) surface acquired)
    [(ffi/deref-pointer acquired 0) acquired]))

(defn draw-triangle!
  "Record and submit one frame: render pass that clears to a dark grey,
  draws three procedural vertices, then submits and presents. Releases
  everything it created; does nothing when the swapchain has no image."
  [api device queue surface pipeline]
  (let [[texture acquired] (acquire-swapchain-texture api surface)]
    (when texture
      (let [view ((:create-texture-view api) texture nil)
            encoder ((:create-command-encoder api) device nil)
            attachment (doto (ffi/allocate! 72)
                         (ffi/write-pointer! 8 view)   ; .view
                         (ffi/write-u32! 32 1)         ; .loadOp = Clear
                         (ffi/write-u32! 36 1)         ; .storeOp = Store
                         (ffi/write-f64! 40 0.09)      ; .clearValue.r
                         (ffi/write-f64! 48 0.09)      ; .clearValue.g
                         (ffi/write-f64! 56 0.11)      ; .clearValue.b
                         (ffi/write-f64! 64 1.0))      ; .clearValue.a
            pass-descriptor (doto (ffi/allocate! 56)
                              (ffi/write-u64! 16 1)    ; .colorAttachmentCount
                              (ffi/write-pointer! 24 attachment)) ; .colorAttachments
            pass ((:begin-render-pass api) encoder pass-descriptor)
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

(comment
  ;; Offsets used above, verified against webgpu.h (wgpu-native 22.1.0.5):
  ;; WGPUSurfaceConfiguration {nextInChain, device, format, usage, count,
  ;;   formats, alphaMode, width, height, presentMode} = 56 bytes
  ;; WGPUSurfaceTexture {texture, suboptimal, status} = 16 bytes
  ;; WGPURenderPassColorAttachment {nextInChain, view, depthSlice,
  ;;   resolveTarget, loadOp, storeOp, clearValue} = 72 bytes
  ;; WGPURenderPassDescriptor {chain, label, count, colorAttachments,
  ;;   depthAttachment, occlusion, timestamp} = 56 bytes
  )
