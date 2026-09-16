package triangle;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SequenceLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Small plumbing layer over the java.lang.foreign (FFM) API, written in
 * Java because Clojure resolves overloaded methods reflectively and the
 * FFM API overloads heavily (Linker.downcallHandle, MemorySegment.get...).
 *
 * Only plumbing lives here; WebGPU knowledge lives in triangle.wgpu and
 * triangle.core.
 */
public final class FFM {

    private FFM() {}

    private static final Linker LINKER = Linker.nativeLinker();
    private static final Arena ARENA = Arena.global();

    /** dlopen a library file and return a SymbolLookup for it. */
    public static SymbolLookup libraryLookup(String path, Arena arena) {
        return SymbolLookup.libraryLookup(java.nio.file.Path.of(path), arena);
    }

    /** True when the library exposes the given symbol. */
    public static boolean hasSymbol(SymbolLookup lookup, String name) {
        return lookup.find(name).isPresent();
    }

    /**
     * Build a MethodHandle for a native function. ret is null for void
     * functions, args are the parameter layouts. Returns null when the
     * library does not export the symbol.
     */
    public static MethodHandle downcall(SymbolLookup lookup, String name,
                                        MemoryLayout ret, MemoryLayout... args) {
        MemorySegment symbol = lookup.find(name).orElse(MemorySegment.NULL);
        if (symbol.equals(MemorySegment.NULL)) {
            return null;
        }
        FunctionDescriptor descriptor = (ret == null)
                ? FunctionDescriptor.ofVoid(args)
                : FunctionDescriptor.of(ret, args);
        return LINKER.downcallHandle(symbol, descriptor);
    }

    /**
     * Call a downcall MethodHandle generically. MemorySegment arguments
     * (including MemorySegment.NULL for "no pointer") and primitives are
     * marshalled by MethodHandle.invokeWithArguments.
     */
    public static Object call(MethodHandle handle, Object... args) {
        try {
            return handle.invokeWithArguments(args);
        } catch (Throwable failure) {
            throw new RuntimeException(failure);
        }
    }

    /**
     * Wrap a Clojure function (any clojure.lang.IFn) as a native function
     * pointer usable as a WebGPU callback. The Clojure function is called
     * with the callback arguments; pointer arguments arrive as
     * MemorySegments.
     */
    public static MemorySegment callback(Object fn, MemoryLayout ret, MemoryLayout... args) {
        try {
            Class<?> ifn = Class.forName("clojure.lang.IFn");
            FunctionDescriptor descriptor = (ret == null)
                    ? FunctionDescriptor.ofVoid(args)
                    : FunctionDescriptor.of(ret, args);
            MethodType interfaceType = descriptor.toMethodType();
            MethodHandle target = MethodHandles.publicLookup()
                    .findVirtual(ifn, "invoke",
                            MethodType.genericMethodType(args.length))
                    .bindTo(fn);
            if (interfaceType.returnType() == void.class) {
                target = MethodHandles.filterReturnValue(target, IGNORE_RETURN);
            }
            target = target.asType(interfaceType);
            return LINKER.upcallStub(target, descriptor, ARENA);
        } catch (Throwable failure) {
            throw new RuntimeException(failure);
        }
    }

    /** Clojure functions return a value; void-returning callbacks drop it. */
    public static void ignoreReturn(Object value) {
    }

    private static final MethodHandle IGNORE_RETURN;

    static {
        try {
            IGNORE_RETURN = MethodHandles.publicLookup()
                    .findStatic(FFM.class, "ignoreReturn",
                            MethodType.methodType(void.class, Object.class));
        } catch (NoSuchMethodException | IllegalAccessException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /** Allocate zeroed native memory laid out like a C struct. */
    public static MemorySegment allocateStruct(MemoryLayout... members) {
        StructLayout struct = MemoryLayout.structLayout(members);
        return ARENA.allocate(struct.byteSize(), struct.byteAlignment());
    }

    public static long byteSize(MemoryLayout layout) {
        return layout.byteSize();
    }

    public static String describe(MemoryLayout layout) {
        return layout.toString();
    }

    /** Read one native int (4 bytes) at a byte offset. */
    public static int getInt(MemorySegment segment, long offset) {
        return segment.get(ValueLayout.JAVA_INT, offset);
    }

    /** Read one native pointer (8 bytes) at a byte offset. */
    public static MemorySegment getPointer(MemorySegment segment, long offset) {
        return segment.get(ValueLayout.ADDRESS, offset);
    }

    /** Read one native long (8 bytes) at a byte offset. */
    public static long getLong(MemorySegment segment, long offset) {
        return segment.get(ValueLayout.JAVA_LONG, offset);
    }

    /** Write one native long (8 bytes) at a byte offset. */
    public static void putLong(MemorySegment segment, long offset, long value) {
        segment.set(ValueLayout.JAVA_LONG, offset, value);
    }

    /** Write one native int (4 bytes) at a byte offset. */
    public static void putInt(MemorySegment segment, long offset, long value) {
        segment.set(ValueLayout.JAVA_INT, offset, (int) value);
    }

    /** Write one native double (8 bytes) at a byte offset. */
    public static void putDouble(MemorySegment segment, long offset, double value) {
        segment.set(ValueLayout.JAVA_DOUBLE, offset, value);
    }

    /** Write one native pointer (8 bytes) at a byte offset; null means NULL. */
    public static void putPointer(MemorySegment segment, long offset, MemorySegment value) {
        segment.set(ValueLayout.ADDRESS, offset,
                value == null ? MemorySegment.NULL : value);
    }

    /** Allocate a pointer-sized zeroed buffer (for out-parameters). */
    public static MemorySegment allocatePointerSlot() {
        return ARENA.allocate(8, 8);
    }

    /** Allocate an array of pointer-sized slots, laid out like a C array. */
    public static MemorySegment allocatePointerArray(long count) {
        SequenceLayout array =
                MemoryLayout.sequenceLayout(count, ValueLayout.ADDRESS);
        return ARENA.allocate(array.byteSize(), array.byteAlignment());
    }
}
