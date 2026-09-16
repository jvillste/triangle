(ns triangle.core
  "A colored triangle in a native window, drawn with WebGPU: GLFW
  (through LWJGL) opens the window, libwgpu-native renders into it,
  and triangle.ffi plus triangle.wgpu describe the C interop.

  Run with:  lein run            (a display is needed)
             lein run -- --smoke (headless plumbing check)
             lein run -- --window-test (draw, screenshot, prove it)"
  (:require [triangle.ffi :as ffi]
            [triangle.wgpu :as wgpu])
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
    :else nil))

(defn- make-surface!
  "Create the wgpu-native surface that presents onto this native
  window. The per-platform struct (32 bytes) is chained into
  WGPUSurfaceDescriptor.nextInChain, exactly as shower.c does it.
  On X11 the window is an XID passed by value (not a Window*); that
  is why the handle crosses the FFM boundary as a raw long."
  [api instance window]
  (let [platform (window-platform (GLFW/glfwGetPlatform))]
    (when-not platform
      (throw (ex-info (str "no WebGPU surface wired for GLFW platform "
                           (GLFW/glfwGetPlatform) " (X11, Wayland, Win32 are)")
                      {:platform (GLFW/glfwGetPlatform)})))
    (let [display (condp = platform
                    :wayland (GLFWNativeWayland/glfwGetWaylandDisplay)
                    :x11 (GLFWNativeX11/glfwGetX11Display)
                    :windows (long 0))
          handle (condp = platform
                   :wayland (GLFWNativeWayland/glfwGetWaylandWindow window)
                   :x11 (GLFWNativeX11/glfwGetX11Window window)
                   :windows window)
          platform-desc (wgpu/platform-descriptor! platform display handle)
          surface-desc (wgpu/surface-descriptor! platform-desc)]
      ((:create-surface api) instance surface-desc))))

;; ── adapter, device, pipeline plumbing

(defn- first-adapter!
  "Enumerate adapters (count, then fill) and return the first handle.
  This is a wgpu-native extension call, not core WebGPU."
  [api instance]
  (let [count ((:enumerate-adapters api) instance nil nil)]
    (when (zero? (long count))
      (throw (ex-info "WebGPU enumerated no adapters (missing or headless GPU driver?)"
                      {:adapter-count (long count)})))
    (let [adapters (ffi/pointer-array! (int (min 8 (long count))))
          _ ((:enumerate-adapters api) instance nil adapters)
          adapter (ffi/deref-pointer adapters 0)]
      (when-not adapter
        (throw (ex-info "the second wgpuInstanceEnumerateAdapters call filled no slot" {})))
      adapter)))

(defn- request-device!
  "Request a device on an adapter. The request is asynchronous: the
  callback resolves a promise, and we wait up to about two seconds for
  it. wgpu-native's processEvents is an unimplemented!() trap under
  X11 (a non-unwinding Rust abort), so we never call it."
  [api instance adapter]
  (let [device-request (promise)
        callback (ffi/callback!
                  (fn [status device _message _userdata]
                    (deliver device-request {:status (long status)
                                             :device device}))
                  [:u32 :ptr :ptr :ptr])
        _ ((:request-device api) adapter nil callback nil)
        settled (loop [spins (int 0)]
                  (if (or (realized? device-request) (>= spins 1250))
                    (when (realized? device-request) @device-request)
                    (do (Thread/sleep 2)
                        (recur (inc spins)))))]
    (cond
      (nil? settled) (throw (ex-info
                             "device request timed out (no GPU driver to answer?)" {}))
      (not= 0 (:status settled)) (throw (ex-info
                                         (str "wgpuAdapterRequestDevice failed, status "
                                              (:status settled) " (0 means Success)")
                                         {:status (:status settled)}))
      :else (:device settled))))

(defn- build-graphics!
  "Everything WebGPU needs before the first triangle, from instance
  on: first adapter, device, queue, shader module, render pipeline."
  [api instance]
  (let [adapter (first-adapter! api instance)
        device (request-device! api instance adapter)
        queue ((:get-queue api) device)
        shader (wgpu/create-triangle-shader! api device)
        pipeline (wgpu/create-triangle-pipeline! api device shader)]
    {:device device :queue queue :pipeline pipeline}))

;; ── the render loop

(defn- render-loop!
  "Paint the first triangle, then repaint once per window event.
  glfwWaitEvents sleeps until an event arrives, so an idle window
  costs no GPU time; ESC or the window manager close button ends the
  loop."
  [api window surface graphics]
  (let [{:keys [device queue pipeline]} graphics]
    (wgpu/draw-triangle! api device queue surface pipeline)
    (loop []
      (GLFW/glfwWaitEvents)
      (when-not (GLFW/glfwWindowShouldClose window)
        (when-not (= GLFW/GLFW_PRESS (GLFW/glfwGetKey window GLFW/GLFW_KEY_ESCAPE))
          (wgpu/draw-triangle! api device queue surface pipeline)
          (recur))))))

(defn- run-window!
  "Open the window, build the WebGPU objects, then repaint it once per
  event. A swapchain that cannot deliver an image skips that repaint."
  [api]
  (when-not (GLFW/glfwInit)
    (throw (ex-info "GLFW cannot connect to a display (no X11 or Wayland session?)" {})))
  (let [window (open-window!)]
    (when (zero? window)
      (GLFW/glfwTerminate)
      (throw (ex-info "glfwCreateWindow failed (no display?)" {})))
    (try
      (GLFW/glfwShowWindow window)
      (let [instance ((:create-instance api) nil)
            _ (do (Thread/sleep 60)
                  (println "instance created."))
            surface (make-surface! api instance window)]
        (when-not surface
          (throw (ex-info "wgpuInstanceCreateSurface returned no surface (display server too old?)"
                          {:platform (GLFW/glfwGetPlatform)})))
        (let [graphics (build-graphics! api instance)
              _ ((:configure-surface api) surface
                                          (wgpu/surface-configuration! (:device graphics) window-width window-height))]
          (println (str "WebGPU pipeline ready (" window-width "x" window-height "); ESC quits"))
          (flush)
          (render-loop! api window surface graphics)))
      (finally
        (GLFW/glfwDestroyWindow window)
        (GLFW/glfwTerminate)))))

;; ── self-test entry points

(defn- window-test!
  "Open the window, pump a dozen triangle frames, screenshot the
  window and count colourful pixels. Exits nonzero when no triangle
  appeared. Needs a display: test.sh runs this under xvfb-run."
  [api]
  (when (GraphicsEnvironment/isHeadless)
    (throw (ex-info "no display: --window-test needs X11/Wayland (try xvfb-run)" {})))
  (when-not (GLFW/glfwInit)
    (throw (ex-info "glfwInit failed (no display?)" {})))
  (let [window (open-window!)]
    (when (zero? window)
      (throw (ex-info "glfwCreateWindow failed (no display?)" {})))
    (GLFW/glfwShowWindow window)
    (Thread/sleep (int 300))
    (let [instance ((:create-instance api) nil)
          _ (Thread/sleep (int 60))
          surface (make-surface! api instance window)]
      (when-not surface
        (throw (ex-info "wgpuInstanceCreateSurface returned no surface" {})))
      (let [graphics (build-graphics! api instance)
            _ ((:configure-surface api) surface
                                        (wgpu/surface-configuration! (:device graphics) window-width window-height))]
        (dotimes [frame (int 12)]
          (wgpu/draw-triangle! api (:device graphics) (:queue graphics)
                               surface (:pipeline graphics))
          (Thread/sleep (int 60)))
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
                (System/exit 1))))))))

;; ── entry points

(defn -main
  "Entry point: --smoke runs the headless plumbing check, anything
  else runs the triangle window (which needs a display)."
  [& args]
  (try
    (do
      (ffi/load-library! (ffi/library-path))
      (let [api (wgpu/make-api)]
        (cond
          (some #{"--smoke"} args)
          (let [instance ((:create-instance api) nil)]
            (build-graphics! api instance)
            (println "smoke: FFM, adapter, device, WGSL shader and pipeline OK.")
            (System/exit 0))

          (some #{"--window-test"} args) (window-test! api)

          :else (run-window! api))))
    (catch Throwable failure
      (.printStackTrace failure)
      (println "triangle stopped:" (ex-message failure))
      (println "headless machines have no window to show; try: lein run -- --smoke")
      (System/exit 1))))


(comment
  (ffi/load-library! (ffi/library-path))
  (window-test! (wgpu/make-api))
  ) ;; TODO: remove me
