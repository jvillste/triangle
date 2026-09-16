 (ns triangle.probe
  "Measurements for the render pass on macOS, printed instead of
  assumed.

  Two things are still open and both have to be measured, not argued
  about. One: whether the render pass that wgpu-native's
  wgpuCommandEncoderBeginRenderPass built saw any colour attachment at
  all, which depends on where this build puts the attachment count and
  array inside WGPURenderPassDescriptor, and on whether it reads them
  from the buffer or expects them somewhere else. Two: whether
  libwgpu_native went through the CAMetalLayer that triangle.cocoa
  built when it made the Metal surface, because a layer that was never
  mounted hands Metal no drawable to draw into, which would look just
  like an empty attachment list.

  Neither can be settled from a Linux box, so this namespace measures.
  Everything in it reads. No layer is built, installed or resized, and
  nothing here calls into wgpu-core, because a printout that changes
  what it measures is worth nothing.

  Handles are built on the first call, so triangle.ffi/load-libraries!
  has to have run first; triangle.core/-main does that before anything
  opens a window. Call report! with the GLFW window once the pipeline is
  ready and before the first frame is drawn."
  (:require [triangle.ffi :as ffi])
  (:import [java.lang.foreign Linker MemorySegment SymbolLookup ValueLayout]))

(def ^:private message-symbols
  "The two message-sending symbols, looked up on demand. On Apple
  silicon the runtime keeps a separate entry point for messages that
  answer with a struct too big for the floating point registers; whether
  this build has it, and whether anything under it still answers that
  way, is exactly the kind of thing that should be looked at rather than
  assumed, so it is looked at."
  (delay
    (when (ffi/loaded?)
      (let [paths (remove nil? [(ffi/library-path) (ffi/runtime-path)])]
        (when-not (empty? paths)
          (let [arena (java.lang.foreign.Arena/global)
                lookups (mapv #(triangle.FFM/libraryLookup % arena) paths)]
            {:send (triangle.FFM/downcall (first lookups) "objc_msgSend"
                                          ValueLayout/ADDRESS
                                          (into-array ValueLayout [ValueLayout/ADDRESS
                                                                   ValueLayout/ADDRESS]))
             :lookups lookups}))))))

(defn report!
  "Print which message entry points this machine's runtimes expose:
  whether every dlopened library answers objc_msgSend and whether the
  plain floating-point-register downcall for it built. Takes the
  GLFW window, which is a raw number, and answers nothing."
  [window]
  (when-let [built @message-symbols]
    (println "  message entry points:"
             (pr-str (mapv #(boolean (triangle.FFM/hasSymbol % "objc_msgSend"))
                           (:lookups built))))
    (println "  objc_msgSend downcall built:" (some? (:send built))))
  nil)

(comment
  ;; To use it, on the Mac, after a rebuild:
  ;; (require 'triangle.probe)
  ;; (triangle.ffi/load-libraries!)
  ;; (def api (triangle.wgpu/make-api))
  ;; (def window (triangle.core/open-window! api))
  ;; (triangle.probe/report! window)
  )
