# triangle

A colored triangle in a native window, drawn with **WebGPU** — not
OpenGL, not a browser. Clojure drives everything through Java's
Foreign Function & Memory (FFM) API:

- **GLFW** (via LWJGL 3.3.6) only owns the window — it is created
  with `GLFW_CLIENT_API = GLFW_NO_API`, so no GL context exists.
- **libwgpu-native 22.1.0.5** does the rendering. It is dlopened at
  startup (bundled copy under `resources/native/<platform>/`, or
  point `-Dwgpu.library=/path/to/libwgpu_native.so` at another one)
  and called through hand-written FFM bindings — no glue generators.
  The bundled binaries come from the official wgpu-native v22.1.0.5
  GitHub release zips (macOS, linux-x86_64) and from the `wn22`
  source tree (linux-aarch64, windows-x86_64); all were built against
  the same `webgpu.h`, which is what the struct layouts in
  `triangle.wgpu` and the tests assume.
- The triangle itself is procedural: a WGSL vertex shader builds
  three clip-space vertices from `vertex_index` and gives each a
  primary color; the hardware interpolates between them.

## Building and testing

    ./test.sh                   compile, struct-layout checks, the
                                headless smoke test, and the window
                                test (under Xvfb when headless)
    ./run.sh                    open the window (needs X11/Wayland)
    TRIANGLE_REPL_PORT=7888 ./run.sh
                                the same, plus an embedded nREPL server;
                                connect an editor with cider-connect
                                (REPL threads must not call GLFW — on
                                macOS only the main thread may, and it
                                stays in the window loop)
    lein run -- --smoke         headless check: instance, adapter,
                                device, shader module, render pipeline
    lein run -- --window-test   draw, screenshot the window, prove
                                the triangle is on screen (exit code
                                says it)

The window test screenshots itself with `java.awt.Robot` (XTEST /
XGetImage under X11) and fails unless colourful triangle pixels are
found. It passes on a headless aarch64 Linux machine with no GPU and
no display: only software rendering (llvmpipe) and a throwaway Xvfb.

## Driving the window from a REPL

Start the window with an embedded nREPL server and connect an editor
from it — CIDER: `M-x cider-connect` to localhost:7888. Do *not*
`cider-jack-in` this project: jack-in loads the cider-nrepl
middleware on the JVM's main thread, which hangs under
`-XstartOnFirstThread` (AWT waits for the AppKit run loop; JDK bug
8019496; see hello_lwjgl issue 6). The embedded server loads the
middleware lazily, on the first connection, when the window loop
already runs it:

    TRIANGLE_REPL_PORT=7888 ./run.sh
    # prints: nREPL server on port 7888

What the REPL may and may not do:

- `triangle.core/session` holds the live objects while the window is
  open: `{:api :device :queue :surface :pipeline :pipeline-data
  :window}`. Take handles from it; a null or stale handle does not
  throw, it aborts the whole process (Rust panics do not unwind into
  the JVM).
- WebGPU calls are thread-safe: draw frames, write buffers and build
  pipelines from REPL threads freely.
- GLFW and Cocoa are main-thread only. Never call GLFW or
  `triangle.cocoa` from the REPL — the one exception is
  `triangle.core/request-frame!`, which uses `glfwPostEmptyEvent`, the
  one GLFW call documented safe from any thread.

Draw triangles from the REPL with `triangle.core/draw-triangles!`:

```clojure
;; x right, y up, -1..1 clip space; colors 0..1 floats or 0..255 ints
(draw-triangles! [[-0.5 -0.5 255 0 0]
                  [ 0.5 -0.5   0 255 0]
                  [ 0.0  0.5   0 0 255]])

;; nested color vectors and explicit alpha work too
(draw-triangles! [[[0.5 0.5 [127 127 127]]
                   [-0.5 0.5 0.0 0.0 1.0]
                   [0.0 -0.5 255 255 0 1.0]]])
```

Vertices are `[x y r g b]`, `[x y r g b a]` or `[x y [r g b a]]`,
passed either flat (a multiple of three vertices) or as a seq of
triangles of three vertices each. Each call uploads a vertex buffer,
presents one frame and releases everything it created; the next
window event repaints the procedural triangle over it.

Lower level: `triangle.wgpu` exposes `:create-buffer`,
`:queue-write-buffer` and `:release-buffer` for uploading data of
your own, and `:create-data-pipeline!` builds the pipeline that reads
positions (float32x2) and colors (float32x4) from a vertex buffer.
GLFW also has to *reach* a display server, and `triangle.core` only
knows the four windowing systems WebGPU has a surface backend for:
X11, Wayland, Win32 and Cocoa. With none of them in front of the
process - a Linux container with `DISPLAY` unset, say - the run is
reported as "no display to draw on" rather than as a crash.

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

## macOS: Metal needs a layer, not a window

`--smoke` (instance, adapter, device, pipeline - all of which
GLFW_NO_API leaves for the JVM to wire up) already works there.
`lein run` gets a window up with nothing in it. WebGPU's Metal backend
does not draw into a window, it presents onto a CAMetalLayer, so the
window's content view has to be given a backing layer first.
`triangle.cocoa/metal-layer!` sends the same three messages shower.c and
wgpu-native's own triangle example send: `setWantsLayer:`, `+layer` and
`setLayer:`. The layer's address is then the one member of the 24-byte
`WGPUSurfaceDescriptorFromMetalLayer` struct (sType 0x1 at 8, layer
pointer at 16), which is why `triangle.wgpu/platform-descriptor!` takes
its members as a vector. `objc_msgSend` needs a downcall handle per
message *shape*, not per symbol, which is why `triangle.ffi/function`
takes a signature, and the runtime is a second library to dlopen, which
is why `triangle.ffi/load-libraries!` looks for libobjc next to
libwgpu_native.

None of that has been run anywhere: no macOS machine was available
while it was written. If the window still comes up empty, the knobs to
turn are the layer's `contentsScale` and `drawableSize` (AppKit normally
keeps both in step with the view it backs), and after those the
`addSublayer:` variant, which needs a `frame`, a `setNeedsDisplay` and a
`drawableSize` the window does give it.

`./test.sh` does not screenshot on macOS either. `java.awt.Robot`
captures the screen rectangle (0,0,800,600), which is only the window
when the window manager happens to place it there, and since macOS 10.15
that capture comes back empty unless the terminal running `java` holds
Screen Recording permission. So on macOS `./test.sh` only says the
window test needs X11, and `./run.sh` opens the window.

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
    src/triangle/cocoa.clj        macOS only: the CAMetalLayer the
                                  window presents onto, sent as
                                  Objective-C messages
    test/triangle/webgpu_test.clj struct-layout checks (lein test)
    resources/native/<platform>/  libwgpu_native to dlopen
