(ns triangle.ffi
  "Foreign Function Interface plumbing for calling libwgpu_native from
  Clojure. This namespace knows nothing about WebGPU, only how to find
  symbols in a native library and how to call them through the
  java.lang.foreign (FFM) API. WebGPU knowledge lives in triangle.wgpu."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io File]
           [java.lang.foreign Arena MemorySegment ValueLayout]))

(def ^:private library (atom {:lookup nil :functions {}}))

(def layouts
  "Keyword shorthand for the value layouts the WebGPU C ABI needs."
  {:ptr ValueLayout/ADDRESS
   :u32 ValueLayout/JAVA_INT
   :u64 ValueLayout/JAVA_LONG
   :f64 ValueLayout/JAVA_DOUBLE})

(defn loaded?
  "True when the native library has been loaded."
  []
  (boolean (:lookup @library)))

(defn load-library!
  "dlopen the native library at path; later function calls resolve
  symbols inside it. Returns the path loaded."
  [path]
  (swap! library assoc :lookup (triangle.FFM/libraryLookup path (Arena/global))
         :functions {})
  path)

(defn pointer
  "Coerce a pointer value into something a :ptr parameter slot accepts:
  nil and 0 become a NULL pointer, raw long addresses are wrapped,
  MemorySegments pass through."
  [^Object value]
  (cond
    (nil? value) MemorySegment/NULL
    (instance? MemorySegment value) value
    (zero? (long value)) MemorySegment/NULL
    :else (MemorySegment/ofAddress (long value))))

(defn deref-pointer
  "Read a pointer-sized slot at offset. Returns the raw address as a
  long, or nil for NULL and zero-filled memory."
  [^MemorySegment segment offset]
  (let [address (triangle.FFM/getLong segment (long offset))]
    (when (not (zero? address))
      address)))

(defn deref-u32
  "Read a 4-byte slot at offset as an int."
  [^MemorySegment segment offset]
  (triangle.FFM/getInt segment (long offset)))

(defn pointer-array!
  "Allocate an uninitialized array of count pointer slots (used where the
  C API wants a pointer to an array of handles)."
  [count]
  (triangle.FFM/allocatePointerArray (long count)))

(defn write-pointer!
  "Write a pointer-sized slot at offset (accepts anything pointer does)."
  [^MemorySegment segment offset value]
  (triangle.FFM/putPointer segment (long offset) (pointer value)))

(defn write-u32!
  [^MemorySegment segment offset value]
  (triangle.FFM/putInt segment (long offset) (long value)))

(defn write-f64!
  [^MemorySegment segment offset value]
  (triangle.FFM/putDouble segment (long offset) (double value)))

(defn allocate!
  "Allocate zeroed native memory of size bytes, 8-byte aligned (the
  alignment every WebGPU struct needs)."
  [size]
  (.allocate (Arena/global) (long size) (long 8)))

(defn string!
  "Copy a Java/Clojure string into zero-terminated native memory."
  [^String s]
  (let [bytes (inc (* 4 (count s)))]
    (doto (.allocate (Arena/global) (long bytes) (long 1))
      (.setString 0 s))))

(defn callback!
  "Wrap f as a native function pointer (a WGPU callback). arguments are
  the parameter layout keywords of that callback typedef."
  [f arguments]
  (triangle.FFM/callback f nil
                         (into-array ValueLayout (mapv layouts arguments))))

(defn write-u64!
  "Write an 8-byte slot (u64 members such as counts, or raw handles)."
  [^MemorySegment segment offset value]
  (triangle.FFM/putLong segment (long offset) (long value)))

(defn platform-directory-name
  "Directory name for native libraries on this machine, like
  \"linux-aarch64\"."
  []
  (let [os (str/lower-case (System/getProperty "os.name"))
        arch (str/lower-case (System/getProperty "os.arch"))]
    (cond
      (re-find #"linux" os) (str "linux-" arch)
      (re-find #"mac|darwin" os) (str "macos-" arch)
      (re-find #"windows" os) (str "windows-" arch)
      :else (str "unknown-" os "-" arch))))

(defn library-file-name
  "File name of the wgpu-native shared library on this machine."
  []
  (let [os (str/lower-case (System/getProperty "os.name"))]
    (cond
      (re-find #"mac|darwin" os) "libwgpu_native.dylib"
      (re-find #"windows" os) "wgpu_native.dll"
      :else "libwgpu_native.so")))

(deftest test-platform-directory-name
  (testing "describes the machine we run on"
    (is (re-matches #"(linux|macos|windows)-\w+" (platform-directory-name)))
    (is (re-matches #"(lib)?wgpu_native\.(so|dylib|dll)" (library-file-name)))))

(defn library-path
  "Absolute path of libwgpu_native to dlopen: either the override
  argument (normally the wgpu.library system property) or the copy
  under resources/native/<platform>/ in the project directory."
  ([]
   (library-path (System/getProperty "wgpu.library")))
  ([override]
   (let [directory (File. (System/getProperty "user.dir")
                          (str "resources" File/separator "native"
                               File/separator (platform-directory-name)))
         file (File. directory (library-file-name))
         path (cond
                (not-empty ^String override) override
                (.isFile file) (.getAbsolutePath file)
                :else nil)]
     (if (and path (.isFile (File. path)))
       path
       (throw (ex-info "wgpu-native library not found; set -Dwgpu.library or place the library under resources/native/<platform>/"
                       {:searched [(str (File. (System/getProperty "user.dir") "resources/native"))
                                   (str override)]}))))))

(defn function
  "Clojure function calling native function name. return and arguments
  are layout keywords (:ptr :u32 :u64 :f64; nil means void). Call the
  result with arguments in C order; nil pointers are NULL, :ptr slots
  accept pointers via pointer, and :u32/:u64/:f64 slots are converted."
  [name return signature]
  (let [handle (triangle.FFM/downcall
                 (:lookup @library)
                 name (layouts return) (into-array ValueLayout (mapv layouts signature)))]
    (when (nil? handle)
      (throw (ex-info (str "symbol not found in native library: " name)
                      {:function name})))
    (fn [& arguments]
      (when-not (= (count signature) (count arguments)) (throw (ex-info (str "arity mismatch calling " name) {:function name})))
      (triangle.FFM/call handle
                         (into-array Object
                                     (map (fn [slot argument]
                                            (case slot
                                              :ptr (pointer argument)
                                              :u32 (int argument)
                                              :u64 (long argument)
                                              :f64 (double argument)
                                              argument))
                                          signature arguments))))))

(comment
  ;; Manual smoke check, from the project directory with JDK 22:
  ;; (load-library! "resources/native/linux-aarch64/libwgpu_native.so")
  ;; ((function "wgpuCreateInstance" :ptr [:ptr]) nil) => non-NULL MemorySegment
  )
