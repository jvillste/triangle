(ns triangle.cocoa
  "Objective-C plumbing for the macOS window.

  WebGPU's Metal backend does not draw into a window: it presents onto a
  CAMetalLayer. A GLFW window opened with GLFW_NO_API therefore needs a
  layer of its own before a surface can be built from it, and AppKit
  only composites layers that belong to a view. Both steps are
  Objective-C messages, sent through the FFM boundary in triangle.ffi.

  macOS only. Everything here runs on the JVM's first thread, which is
  where AppKit wants to be called from (project.clj passes
  -XstartOnFirstThread for that reason), and it is only reached when
  GLFW reports GLFW_PLATFORM_COCOA."
  (:require [triangle.ffi :as ffi])
  (:import [org.lwjgl.glfw GLFW GLFWNativeCocoa]
           [org.lwjgl.system APIUtil]
           [java.lang.foreign MemorySegment]))

(def ^:private runtime
  "Objective-C runtime entry points, built on first use (by which time
  triangle.ffi/load-libraries! has dlopened the runtime). objc_msgSend is
  one symbol but not one signature: what a message returns and takes
  depends on the selector, so every message shape here gets its own
  downcall handle."
  (delay
    (let [function ffi/function]
      {:class (function "objc_getClass" :ptr [:ptr])
       :selector (function "sel_registerName" :ptr [:ptr])
       :send (function "objc_msgSend" :ptr [:ptr :ptr])
       :send-bool (function "objc_msgSend" nil [:ptr :ptr :u8])
       :send-layer (function "objc_msgSend" nil [:ptr :ptr :ptr])
       :retain (function "objc_retain" :ptr [:ptr])})))

(defn- native-symbol
  "Address of a GLFW native entry point, or nil when the GLFW library
  LWJGL loaded does not export it. LWJGL resolves these names with
  dlsym, and org.lwjgl.util.NoChecks skips the check that would catch a
  missing one, so look before calling anything through it."
  [name]
  (let [library ^org.lwjgl.system.SharedLibrary (GLFW/getLibrary)
        address (APIUtil/apiGetFunctionAddressOptional library name)]
    (when (pos? (long address))
      (long address))))

(defn- message!
  "Send one Objective-C message. style is the message shape (:send for a
  message that answers with an object, :send-bool and :send-layer for
  those that answer with nothing), receiver says who to send it to,
  selector-name which message, and the rest are message arguments. The
  receiver may be a class: that is how a class method is called."
  [style receiver selector-name & arguments]
  (let [api @runtime
        send (style api)]
    (apply send receiver ((:selector api) (ffi/string! selector-name)) arguments)))

(defn- class!
  "A Cocoa class, by the name the runtime spells it under."
  [name]
  ((:class @runtime) (ffi/string! name)))

(defn- retain!
  "Keep a freshly messaged object alive for the rest of the run."
  [value]
  ((:retain @runtime) value))

(defn metal-layer!
  "Give a GLFW window the CAMetalLayer that WebGPU presents onto, and
  return it. Three messages, the same ones as wgpu-native's own triangle
  example: make the window's content view layer-backed, create a Metal
  layer, install it as that view's backing layer, where AppKit
  composites it and resizes it with the window.

  If the triangle comes out black on a Retina display, the knobs to turn
  are the layer's contentsScale and drawableSize, which AppKit normally
  keeps in step with the view it backs."
  [window]
  (when-not (native-symbol "glfwGetCocoaWindow")
    (throw (ex-info "this GLFW build does not export glfwGetCocoaWindow, so the window cannot name its content view"
                    {:symbol "glfwGetCocoaWindow"})))
  (let [view (message! :send (GLFWNativeCocoa/glfwGetCocoaWindow window) "contentView")]
    (when (zero? (.address ^MemorySegment view))
      (throw (ex-info "this GLFW window has no content view to draw on" {})))
    (message! :send-bool view "setWantsLayer:" (byte 1))
    (let [layer (retain! (message! :send (class! "CAMetalLayer") "layer"))]
      (message! :send-layer view "setLayer:" layer)
      layer)))

(comment
  ;; What a window looks like from Clojure, on macOS, with a window open:
  ;; (require 'triangle.ffi) (triangle.ffi/load-libraries!)
  ;; (triangle.cocoa/metal-layer! <window handle>)
  )
