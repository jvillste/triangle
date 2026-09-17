(ns triangle.core
  "A colored triangle in a native window, drawn with WebGPU: GLFW
  (through LWJGL) opens the window, libwgpu-native renders into it,
  and triangle.ffi plus triangle.wgpu plus triangle.cocoa describe the
  C interop. On macOS the window's content view gets a CAMetalLayer
  first, because that is where Metal presents onto.

  Run with:  lein run            (a display is needed)
             lein run -- --smoke (headless plumbing check)
             lein run -- --window-test (draw, screenshot, prove it)
  TRIANGLE_REPL_PORT=<port> ./run.sh additionally serves nREPL, so an
  editor can connect to the live window process (see start-repl-server!)."
  (:require [nrepl.server :as server]
            [triangle.cocoa :as cocoa]
            [triangle.ffi :as ffi]
            [triangle.wgpu :as wgpu]
            ;; the layout checks live next to the code they check
            [clojure.test :refer [deftest is testing]])
  (:import [org.lwjgl.glfw GLFW GLFWNativeX11 GLFWNativeWayland]
           [java.awt GraphicsEnvironment Robot Rectangle]
           [java.io File]
           [javax.imageio ImageIO]
           [java.awt.image BufferedImage]))

;; ── window plumbing (GLFW through LWJGL bindings)

(def ^:private window-width 800)
(def ^:private window-height 600)

(defn- open-window!
  "Create the GLFW window. WebGPU renders directly to the window's
  surface, so GLFW must not create a GL context (NO_API)."
  []
  (GLFW/glfwWindowHint GLFW/GLFW_CLIENT_API GLFW/GLFW_NO_API)
  (GLFW/glfwWindowHint GLFW/GLFW_RESIZABLE GLFW/GLFW_FALSE)
  (GLFW/glfwCreateWindow (int window-width) (int window-height)
                         "Clojure WebGPU triangle" (long 0) (long 0)))

(defn- window-platform
  "Keyword for the windowing system GLFW uses, or nil for unknown."
  [platform]
  (cond
    (= platform GLFW/GLFW_PLATFORM_WAYLAND) :wayland
    (= platform GLFW/GLFW_PLATFORM_X11) :x11
    (= platform GLFW/GLFW_PLATFORM_WIN32) :windows
    (= platform GLFW/GLFW_PLATFORM_COCOA) :cocoa
    :else nil))

(defn- surface-members
  "The pointer members of this platform's chained struct, in struct
  order (see triangle.wgpu/platform-descriptor!). Cocoa builds the
  CAMetalLayer that WebGPU presents onto first; see triangle.cocoa."
  [platform window]
  (case platform
    :wayland [(GLFWNativeWayland/glfwGetWaylandDisplay)
              (GLFWNativeWayland/glfwGetWaylandWindow window)]
    :x11 [(GLFWNativeX11/glfwGetX11Display)
          (GLFWNativeX11/glfwGetX11Window window)]
    :windows [(long 0) window]
    :cocoa [(cocoa/metal-layer! window)]))

(defn- make-surface!
  "Create the wgpu-native surface that presents onto this native window.
  On X11 the window is an XID passed by value (not a Window*); that is
  why the handles cross the FFM boundary as raw longs."
  [instance window]
  (let [platform (window-platform (GLFW/glfwGetPlatform))]
    (when-not platform
      (throw (ex-info (str "no WebGPU surface wired for GLFW platform "
                           (GLFW/glfwGetPlatform) " (X11, Wayland, Win32 and Cocoa are)")
                      {:platform (GLFW/glfwGetPlatform)})))
    (wgpu/create-surface! instance platform (surface-members platform window))))

;; ── adapter, device, pipeline plumbing

(defn- build-graphics!
  "Everything WebGPU needs before the first triangle, from instance
  on: first adapter, device, queue, shader modules and both render
  pipelines — the procedural one for the window loop and the
  data-driven one for draw-triangles! from the REPL."
  [instance]
  (let [adapter (wgpu/first-adapter! instance)
        device (wgpu/request-device! adapter)
        queue (wgpu/get-queue device)
        shader (wgpu/create-shader-module! device wgpu/triangle-wgsl)
        pipeline (wgpu/create-triangle-pipeline! device shader)
        data-shader (wgpu/create-shader-module! device wgpu/data-triangle-wgsl)
        data-pipeline (wgpu/create-data-pipeline! device data-shader)]
    {:device device :queue queue :pipeline pipeline
     :pipeline-data data-pipeline}))

;; ── the render loop

(defn- render-loop!
  "Paint the first triangle, then repaint once per window event.
  glfwWaitEvents sleeps until an event arrives, so an idle window
  costs no GPU time; ESC or the window manager close button ends the
  loop."
  [window surface graphics]
  (let [{:keys [device queue pipeline]} graphics]
    (wgpu/draw-triangle! device queue surface pipeline)
    (loop []
      (GLFW/glfwWaitEvents)
      (when-not (GLFW/glfwWindowShouldClose window)
        (when-not (= GLFW/GLFW_PRESS (GLFW/glfwGetKey window GLFW/GLFW_KEY_ESCAPE))
          (wgpu/draw-triangle! device queue surface pipeline)
          (recur))))))

(defonce session
  #_"The live window objects while a window is open: {:device :queue
  :surface :pipeline :window}. Populated by run-window!, cleared when
  it returns. REPL threads may read it and drive the WebGPU members
  (draw frames, write buffers); the :window member is main-thread
  business."
  (atom nil))

(defn request-frame!
  "Wake the window loop so it repaints: glfwPostEmptyEvent is the one
  GLFW call documented safe from any thread. No-op when no window is
  open."
  []
  (when (:window @session)
    (GLFW/glfwPostEmptyEvent)))

(defn draw-triangles!
  "Draw triangles into the open window from the REPL: triangles is
  either a flat seq of vertices — [x y r g b] or [x y r g b a], colors
  0..1 floats or 0..255 integers — or a seq of triangles of three
  vertices each. Positions are clip-space: x right, y up, -1..1. Each
  call presents one frame; the next window event repaints the
  procedural triangle over it."
  [triangles]
  (let [{:keys [device queue surface pipeline-data]} @session]
    (when-not surface
      (throw (ex-info "no open window: start one with TRIANGLE_REPL_PORT=7888 ./run.sh" {})))
    (wgpu/draw-triangles! device queue surface pipeline-data triangles)))

(defn- run-window!
  "Open the window, build the WebGPU objects, then repaint it once per
  event. A swapchain that cannot deliver an image skips that repaint.
  The live objects go into the session atom while the window is open,
  so a connected editor can drive them (see session and request-frame!)."
  []
  (when-not (GLFW/glfwInit)
    (throw (ex-info "GLFW cannot connect to a display (no X11 or Wayland session?)" {})))
  (let [window (open-window!)]
    (when (zero? window)
      (GLFW/glfwTerminate)
      (throw (ex-info "glfwCreateWindow failed (no display?)" {})))
    (try
      (GLFW/glfwShowWindow window)
      (let [instance (wgpu/create-instance!)
            _ (do (Thread/sleep 60)
                  (println "instance created."))
            surface (make-surface! instance window)]
        (when-not surface
          (throw (ex-info "wgpuInstanceCreateSurface returned no surface (display server too old?)"
                          {:platform (GLFW/glfwGetPlatform)})))
        (let [graphics (build-graphics! instance)
              _ (wgpu/configure-surface! surface (:device graphics) window-width window-height)]
          (reset! session (assoc graphics :window window :surface surface))
          (println (str "WebGPU pipeline ready (" window-width "x" window-height "); ESC quits"))
          (flush)
          (render-loop! window surface graphics)))
      (finally
        (reset! session nil)
        (GLFW/glfwDestroyWindow window)
        (GLFW/glfwTerminate)))))

;; ── self-test entry points

(defn- window-test!
  "Open the window, pump a dozen triangle frames, screenshot the screen
  at (0,0) and count colourful pixels, which shows the triangle only
  when the window manager happened to put the window there. Needs a
  display: test.sh runs this under Xvfb on Linux, and skips it on
  macOS, where that capture would also need Screen Recording
  permission."
  []
  (when (GraphicsEnvironment/isHeadless)
    (throw (ex-info "no display to draw on: --window-test needs X11 or Wayland (try ./test.sh)" {})))
  (when-not (GLFW/glfwInit)
    (throw (ex-info "glfwInit failed (no display?)" {})))
  (let [window (open-window!)]
    (when (zero? window)
      (throw (ex-info "glfwCreateWindow failed (no display?)" {})))
    (GLFW/glfwShowWindow window)
    (Thread/sleep (int 300))
    (let [instance (wgpu/create-instance!)
          _ (Thread/sleep (int 60))
          surface (make-surface! instance window)]
      (when-not surface
        (throw (ex-info "wgpuInstanceCreateSurface returned no surface" {})))
      (let [graphics (build-graphics! instance)
            _ (wgpu/configure-surface! surface (:device graphics) window-width window-height)]
        (dotimes [_ (int 12)]
          (wgpu/draw-triangle! (:device graphics) (:queue graphics)
                               surface (:pipeline graphics))
          (Thread/sleep (int 60)))
        ;; the data-driven pipeline too: buffer-descriptor!,
        ;; queue-write-buffer and set-vertex-buffer all validate
        ;; the buffer's usage flags
        (wgpu/draw-triangles! (:device graphics) (:queue graphics)
                              surface (:pipeline-data graphics)
                              [[-0.5 -0.5 255 0 0]
                               [0.5 -0.5 0 255 0]
                               [0.0 0.5 0 0 255]]))
      (let [robot (Robot.)
            shot (.createScreenCapture ^Robot robot
                                       (Rectangle. (int 0) (int 0) (int window-width) (int window-height)))
            classify (fn [rgb]
                       (let [red (bit-and (bit-shift-right rgb 16) 0xFF)
                             green (bit-and (bit-shift-right rgb 8) 0xFF)
                             blue (bit-and rgb 0xFF)]
                         (cond
                           (and (> blue (int 130)) (< red (int 90)) (< green (int 90))) :blueish
                           (and (> green (int 130)) (< red (int 90)) (< blue (int 90))) :greenish
                           (and (> red (int 130)) (> green (int 90))) :red-yellowish
                           (and (< (abs (- red green)) (int 26))
                                (< (abs (- green blue)) (int 26))) :greyish
                           :else :unknown)))
            classes (frequencies
                     (for [y (range (int 0) (int window-height) (int 2))
                           x (range (int 0) (int window-width) (int 2))]
                       (classify (.getRGB ^BufferedImage shot (int x) (int y)))))
            samples (quot (* window-width window-height) (int 4))
            colourful (- (long samples)
                         (long (classes :greyish (long 0)))
                         (long (classes :unknown (long 0))))]
        (ImageIO/write shot "png" (File. "target/triangle.png"))
        (println (str "window test: " colourful " colourful of " samples " sampled pixels"))
        (if (>= colourful (quot samples (int 100)))
          (do (println "window test: triangle visible (target/triangle.png)")
              (System/exit 0))
          (do (println "window test: NO triangle (target/triangle.png)")
              (System/exit 1)))))))

;; ── embedded nREPL server (TRIANGLE_REPL_PORT)

(defn repl-port
  "Port for the embedded nREPL server, from the TRIANGLE_REPL_PORT
  environment variable: nil when unset or not a number."
  [value]
  (when (string? value)
    (parse-long value)))

(deftest test-repl-port
  (testing "a numeric variable selects a port, anything else none"
    (is (= 7888 (repl-port "7888")))
    (is (= 0 (repl-port "0")))
    (is (nil? (repl-port nil)))
    (is (nil? (repl-port "open sesame")))))

(defn- message-handler
  "The nREPL message handler for the embedded server: cider-nrepl's
  middleware handler when that library is on the classpath (which
  makes cider-connect work at full strength), the plain default
  otherwise. cider.nrepl is loaded lazily, on the first connection
  rather than at startup, because it initializes AWT — and AWT under
  -XstartOnFirstThread only unblocks once the AppKit run loop has
  started, which on macOS is glfwWaitEvents inside the window loop,
  already running by then (JDK bug 8019496; see hello_lwjgl issue 6)."
  []
  (let [cider (delay
                (try
                  (require 'cider.nrepl)
                  @(resolve 'cider.nrepl/cider-nrepl-handler)
                  (catch Throwable _ nil)))]
    (fn [message]
      (if-let [handler @cider]
        (handler message)
        ((server/default-handler) message)))))

(defn- start-repl-server!
  "Start an nREPL server so an external editor (CIDER's
  cider-connect, say) can evaluate against this live window process.
  Evaluation happens on the server's worker threads: they may reach
  WebGPU state, but never GLFW — on macOS only thread 0, which -main
  and the window loop occupy, may do that."
  [port]
  (let [server (server/start-server :port port :handler (message-handler))]
    (println "nREPL server on port" (:port server))
    (flush)
    server))

;; ── entry points

(defn -main
  "Entry point: --smoke runs the headless plumbing check,
  --window-test draws and screenshots, anything else runs the
  triangle window (which needs a display). With TRIANGLE_REPL_PORT
  set to a port, the window run also serves nREPL."
  [& args]
  (try
    (ffi/load-libraries!)
    (cond
      (some #{"--smoke"} args)
      (let [instance (wgpu/create-instance!)]
        (build-graphics! instance)
        (println "smoke: FFM, adapter, device, WGSL shader and pipeline OK.")
        (System/exit 0))

      (some #{"--window-test"} args) (window-test!)

      :else (let [port (repl-port (System/getenv "TRIANGLE_REPL_PORT"))
                  repl-server (when port (start-repl-server! port))]
              (.start (Thread. (fn []
                                 (Thread/sleep 2000)
                                 (draw-triangles! [[-1.0 -1.0 0 0 0]
                                                   [-1.0 4.0 0 0 0]
                                                   [4.0 -1.0 0 0 0]]))))
              (run-window!)
              (when repl-server
                (server/stop-server repl-server))))
    (catch Throwable failure
      (.printStackTrace failure)
      (println "triangle stopped:" (ex-message failure))
      (println "if this machine has no display to draw on, check the plumbing instead with: lein run -- --smoke")
      (System/exit 1))))

(defn clear! []
  (draw-triangles! [[-1.0 -1.0 0 0 0]
                    [-1.0 4.0 0 0 0]
                    [4.0 -1.0 0 0 0]]))
(comment
  (ffi/load-libraries!)
  (window-test!)


  (do (clear!)
      (draw-triangles! (apply concat
                              (for [i (range 10)]
                                [[(+ (* i 0.1)
                                     -0.5)
                                  -0.5 255 0 0]
                                 [0.5 -0.5 0 255 0]
                                 [0.0 0.5 0 0 255]]))))

  ) ;; TODO: remove me
