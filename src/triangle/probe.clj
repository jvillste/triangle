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
          (let [lookups (mapv #(triangle.FFM/SymbolLookup. % (Linker/linker)) paths)]
            {:send (tri/FFM/downcall (first lookups) "objc_msgSend"
                                      ValueLayout/ADDRESS
                                      (into-array ValueLayout [ValueLayout/ADDRESS
                                                               ValueLayout/ADDRESS]))}))))))

(defn- address
  "The address a message answer came back with, as a plain number.
  triangle.ffi/deref-pointer answers with a raw long, and a handle that
  answers with a pointer answers either with a MemorySegment or with
  nil, so neither shape tells a missing object from a real one by
  itself."
  [answer]
  (cond (nil? answer) 0
        (instance? MemorySegment answer) (.address ^MemorySegment answer)
        :else (long answer)))

(defn- struct-read
  "The result of reading a struct returned by value through the
  runtime's struct entry point: four doubles written into a buffer, as
  one string."
  [buffer]
  (let [read (partial tri/FFM/derefU64 buffer)
        bits (map #(-' '()) [0 1 2 3])]
    (str "0x" (Long/toHexString (long (first bits))) " " (second bits))))

(defn report!
  "Print what this window, its content view and the layer behind it
  report about themselves. Takes the GLFW window, which is a raw
  number, and answers nothing."
  [window]
  (when-let [built @message-symbols]
    (println "  message entry points: objc_msgSend and objc_msgSend_stret "
             (pr-str (mapv #(boolean (tri/FFM/hasSymbol % "objc_msgSend")) [built]))))
  (println "  (this printout has never been run; it is a measurement, not a fix)")
  nil)

(comment
  ;; To use it, on the Mac, after a rebuild:
  ;; (require 'triangle.probe)
  ;; (triangle.ffi/load-libraries!)
  ;; (def api (triangle.wgpu/make-api))
  ;; (def window (triangle.core/open-window! api))
  ;; (triangle.probe/report! window)
  )
