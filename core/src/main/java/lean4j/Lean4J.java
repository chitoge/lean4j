package lean4j;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * Java bindings for the Lean4J example module.
 * Auto-generated in the future; hand-written here for the spike.
 */
public final class Lean4J {

    private static final MethodHandle MH_ADD_UINT32;
    private static final MethodHandle MH_MUL_UINT32;
    private static final MethodHandle MH_GREET;
    private static final MethodHandle MH_FIB;

    static {
        Linker linker = Linker.nativeLinker();
        MH_ADD_UINT32 = LeanRuntime.lookupSymbol("lean4j_add_uint32",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        MH_MUL_UINT32 = LeanRuntime.lookupSymbol("lean4j_mul_uint32",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        MH_GREET = LeanRuntime.lookupSymbol("lean4j_greet",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MH_FIB = LeanRuntime.lookupSymbol("lean4j_fib",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
    }

    private Lean4J() {}

    /** Lean: @[export lean4j_add_uint32] def addUInt32 (a b : UInt32) : UInt32 */
    public static int addUInt32(int a, int b) {
        try {
            return (int) MH_ADD_UINT32.invoke(a, b);
        } catch (Throwable e) { throw new RuntimeException(e); }
    }

    /** Lean: @[export lean4j_mul_uint32] def mulUInt32 (a b : UInt32) : UInt32 */
    public static int mulUInt32(int a, int b) {
        try {
            return (int) MH_MUL_UINT32.invoke(a, b);
        } catch (Throwable e) { throw new RuntimeException(e); }
    }

    /** Lean: @[export lean4j_greet] def greet (name : String) : String */
    public static String greet(String name) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment leanName = LeanRuntime.javaStringToLean(name, arena);
            // lean4j_greet consumes leanName (standard calling convention)
            MemorySegment result = (MemorySegment) MH_GREET.invoke(leanName);
            String javaResult = LeanRuntime.leanStringToJava(result);
            LeanRuntime.dec(result);  // we own the returned string
            return javaResult;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    /** Lean: @[export lean4j_fib] def fib (n : UInt64) : UInt64 */
    public static long fib(long n) {
        try {
            return (long) MH_FIB.invoke(n);
        } catch (Throwable e) { throw new RuntimeException(e); }
    }
}
