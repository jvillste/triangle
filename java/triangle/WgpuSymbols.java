package triangle;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.util.Optional;

/**
 * The SymbolLookup the jextract-generated wgpu bindings (package wgpu)
 * resolve their function symbols against. jextract generates bindings
 * for the "wgpu_native" library, but its default dlopens it by bare
 * name at class init; this project instead dlopens the pinned release
 * binary from resources/native/ through triangle.ffi, which registers
 * its combined symbol lookup here.
 *
 * The generated wgpu_h captures lookup() into a static field at class
 * init, and that init runs as soon as any generated layout is touched
 * (say, at namespace load), so the lookup has to answer at call time
 * rather than capture time: lookup() always returns the same
 * deferring object, which forwards to the most recent registration
 * and finds nothing before the first one, so a premature downcall
 * then fails loudly at the call site.
 */
public final class WgpuSymbols {

    private static volatile SymbolLookup registered;

    /** Forwards every find to the lookup registered at call time. */
    private static final SymbolLookup DEFERRED = WgpuSymbols::find;

    private WgpuSymbols() {
    }

    /** Install the lookup over the libraries triangle.ffi has dlopened. */
    public static void registerLookup(SymbolLookup lookup) {
        registered = lookup;
    }

    /** The lookup the generated bindings bind against; see above. */
    public static SymbolLookup lookup() {
        return DEFERRED;
    }

    private static Optional<MemorySegment> find(String name) {
        SymbolLookup current = registered;
        return current == null ? Optional.empty() : current.find(name);
    }
}
