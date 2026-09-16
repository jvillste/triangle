# triangle

A colored triangle in a native window, drawn with **WebGPU** — not
OpenGL, not a browser. Clojure drives everything through Java's
Foreign Function & Memory (FFM) API:

- **GLFW** (via LWJGL 3.3.6) only owns the window — it is created
  with `GLFW_CLIENT_API = GLFW_NO_API`, so no GL context exists.
- **libwgpu-native 22.1.0.5** does the rendering. It is dlopened at
  startup (bundled copy in `resources/native/linux-aarch64/`, or
  point `-Dwgpu.library=/path/to/libwgpu_native.so` at another one)
  and called through hand-written FFM bindings — no glue generators.
- The triangle itself is procedural: a WGSL vertex shader builds
  three clip-space vertices from `vertex_index` and gives each a
  primary color; the hardware interpolates between them.

## Building and testing

    ./test.sh                   compile, struct-layout checks, the
                                headless smoke test, and the window
                                test (under Xvfb when headless)
    ./run.sh                    open the window (needs X11/Wayland)
    lein run -- --smoke         headless check: instance, adapter,
                                device, shader module, render pipeline
    lein run -- --window-test   draw, screenshot the window, prove
                                the triangle is on screen (exit code
                                says it)

The window test screenshots itself with `java.awt.Robot` (XTEST /
XGetImage under X11) and fails unless colourful triangle pixels are
found. It passes on a headless aarch64 Linux machine with no GPU and
no display: only software rendering (llvmpipe) and a throwaway Xvfb.

## Requirements

JDK 25 is the pinned toolchain: ./test.sh and ./run.sh pick it up
by themselves (Linux: apt's openjdk-25-jdk - ask for the FULL
build; the "-headless" package has no X11 AWT toolkit, so
java.awt.Robot could never screenshot anything there. macOS:
/usr/libexec/java_home -v 25. A set JAVA_HOME is honoured.)
project.clj adds -XstartOnFirstThread on macOS only: Cocoa wants
AppKit on the first thread, while Linux HotSpot does not even know
that flag and refuses to start a JVM when it sees it.

A Vulkan driver, and software rendering is enough. Window mode and
the window test need a display: a real X11/Wayland session, or
Xvfb (`apt install xvfb`), which `./test.sh` starts by itself
when no `DISPLAY` is set.

## How the pieces talk

Clojure calls are built as `MethodHandle`s over `Linker.downcallHandle`
(see `triangle.ffi/function`). Pointer-sized arguments are marshalled
as `MemorySegment`s — the FFM linker rejects raw `long`s there. Two
APIs are deliberately never called: `wgpuInstanceProcessEvents` (in
wgpu-native v22 it is an `unimplemented!()` trap which panics and
aborts the whole process under X11) and `glfwGetFramebufferSize`
(this LWJGL aarch64 native build segfaults in it — the window is
fixed-size, so its size is simply known).

## Layout

    java/triangle/FFM.java        the whole native boundary: symbol
                                  lookup, downcall handles, callback
                                  (upcall) stubs, memory helpers
    src/triangle/ffi.clj          Clojure side of the boundary: find
                                  symbols, build call handles, coerce
                                  arguments (pointers must cross as
                                  MemorySegments, not raw longs)
    src/triangle/wgpu.clj         WebGPU knowledge: function
                                  signatures, C struct layouts,
                                  the WGSL shader, one frame's
                                  worth of calls
    src/triangle/core.clj         GLFW window, frame loop, self-tests
    test/triangle/webgpu_test.clj struct-layout checks (lein test)
    resources/native/<platform>/  libwgpu_native to dlopen
