# triangle

A colored triangle in a native window, drawn with **WebGPU** — not
OpenGL, not a browser. Clojure drives everything through Java's
Foreign Function & Memory (FFM) API:

- **GLFW** (via LWJGL 3.3.6) only owns the window — it is created
  with `GLFW_CLIENT_API = GLFW_NO_API`, so no GL context exists.
- **libwgpu-native 22.1.0.5** does the rendering. It is dlopened at
  startup (bundled copy under `resources/native/<platform>/`, or
  point `-Dwgpu.library=/path/to/libwgpu_native.so` at another one)
  and called through the full wgpu-native bindings jextract
  generated from that release's C header: `java/wgpu/` is the
  generated tree, and `./regenerate-bindings.sh` makes it again.
  No native library lives in git: `./fetch-native.sh` downloads the
  pinned official release artifact for this platform (sha256-checked)
  into `resources/native/`, and `test.sh`/`run.sh` run it first. All
  five artifacts are the official v22.1.0.5 release builds, made
  against the same `webgpu.h` the generated layouts and the tests
  assume.
- The triangle itself is procedural: a WGSL vertex shader builds
  three clip-space vertices from `vertex_index` and gives each a
  primary color; the hardware interpolates between them.

## Building and testing

    ./fetch-native.sh           download this platform's libwgpu_native
                                (add --all for every platform)
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
  open: `{:device :queue :surface :pipeline :pipeline-data :window}`.
  Take handles from it; a null or stale handle does not
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

Lower level: the generated bindings expose every entry point of the
header as a static on `wgpu.wgpu_h`, and `triangle.wgpu` adds the
demo-side builders (`buffer-descriptor!`, `vertex-buffer-layout!`)
and `create-data-pipeline!`, the pipeline that reads positions
(float32x2) and colors (float32x4) from a vertex buffer.
GLFW also has to *reach* a display server, and `triangle.core` only
knows the four windowing systems WebGPU has a surface backend for:
X11, Wayland, Win32 and Cocoa. With none of them in front of the
process - a Linux container with `DISPLAY` unset, say - the run is
reported as "no display to draw on" rather than as a crash.

## Requirements

Network access for the first build: ./fetch-native.sh (run by
./test.sh and ./run.sh) downloads libwgpu_native from the wgpu-native
GitHub release and checks its sha256 against the pinned value in the
script. After that the check is local and offline. On macOS behind a
Docker shared folder, run ./fetch-native.sh on the Mac itself — a
dylib the container wrote into the mount can be served with stale
pages and killed at dlopen (see the macOS section below). Windows is
not scripted: fetch the wgpu-windows-x86_64-msvc-release.zip by hand
and copy lib/wgpu_native.dll into resources/native/windows-x86_64/.

JDK 25 is the pinned toolchain: ./test.sh and ./run.sh pick it up
by themselves (Linux: apt's openjdk-25-jdk - ask for the FULL
build; the "-headless" package has no X11 AWT toolkit, so
java.awt.Robot could never screenshot anything there. macOS:
/usr/libexec/java_home -v 25. A set JAVA_HOME is honoured.)
project.clj adds -XstartOnFirstThread on macOS only: Cocoa wants
AppKit on the first thread, while Linux HotSpot does not even know
that flag and refuses to start a JVM when it sees it.

jextract, which made java/wgpu/, ships as an early-access build,
not with the JDK: only ./regenerate-bindings.sh needs it (run that
when the header revision changes), and it downloads the pinned EA
build itself unless a jextract is on PATH. It reads the header
from a wgpu-native checkout (WGPU_NATIVE_DIR, or a sibling "wn22"
directory) matching the pinned release.

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

The WebGPU side is a generated boundary: `java/wgpu/` is the full
jextract output for `webgpu.h` plus the wgpu-native extensions in
`wgpu.h`, so every struct layout and every entry point of the C API
exists as Java, and the demo never writes a byte offset by hand.
`triangle.wgpu` allocates each struct with the layout jextract
derived and fills it through the generated per-field setters.
jextract's own `SYMBOL_LOOKUP` would dlopen `libwgpu_native` by bare
name at class init; the one hand-maintained line in `wgpu_h.java`
(kept in step by `./regenerate-bindings.sh`) points it at
`triangle.WgpuSymbols` instead, which `triangle.ffi/load-libraries!`
registers against the library it dlopened — deferred, so the lookup
answers at call time, when the right library is in. Every entry
point is a `MethodHandle` downcall, and pointer-sized arguments are
marshalled as `MemorySegment`s — the FFM linker rejects raw `long`s
there. Two APIs are deliberately never called: `wgpuInstanceProcessEvents` (in
wgpu-native v22 it is an `unimplemented!()` trap which panics and
aborts the whole process under X11) and `glfwGetFramebufferSize`
(this LWJGL aarch64 native build segfaults in it — the window is
fixed-size, so its size is simply known).

## Layout

    java/wgpu/                    jextract-generated bindings for
                                  the full wgpu-native 22.1.0.5 C
                                  API (webgpu.h plus wgpu.h's
                                  extensions); the one hand-maintained
                                  line is SYMBOL_LOOKUP in wgpu_h.java
    java/triangle/FFM.java        shared native plumbing: symbol
                                  lookup, downcall handles, memory
                                  helpers (still the whole boundary
                                  for the macOS Objective-C messages)
    java/triangle/WgpuSymbols.java  the symbol lookup the generated
                                  bindings bind against: defers to
                                  whatever triangle.ffi dlopened
    src/triangle/ffi.clj          dlopen the libraries, keep the
                                  symbol chain, register it with the
                                  bindings, generic FFM helpers
                                  (pointers cross as MemorySegments,
                                  not raw longs)
    src/triangle/wgpu.clj         the demo on the bindings: the
                                  descriptor builders, the WGSL
                                  shaders, one frame's worth of
                                  calls
    src/triangle/core.clj         GLFW window, frame loop, self-tests
    src/triangle/cocoa.clj        macOS only: the CAMetalLayer the
                                  window presents onto, sent as
                                  Objective-C messages
    test/triangle/webgpu_test.clj layout checks of the generated
                                  structs and the demo's builders
                                  (lein test)
    resources/native/<platform>/  libwgpu_native to dlopen
