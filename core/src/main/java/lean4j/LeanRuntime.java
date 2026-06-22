package lean4j;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Low-level bridge to the Lean 4 native runtime via the Foreign Function & Memory API.
 *
 * Lifecycle: call {@link #initialize(String)} once before any Lean function.
 * Thread safety: initialization is thread-safe; object refcounts are not — see docs.
 */
public final class LeanRuntime {

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static Linker LINKER;
    private static SymbolLookup LOOKUP;

    // Cached method handles for bridge functions
    private static MethodHandle MH_INITIALIZE;
    private static MethodHandle MH_INIT_MODULE;
    private static MethodHandle MH_MARK_END_INIT;
    private static MethodHandle MH_START_TASK_MANAGER;
    private static MethodHandle MH_INC;
    private static MethodHandle MH_DEC;
    private static MethodHandle MH_STRING_CSTR;
    private static MethodHandle MH_STRING_UTF8_LEN;
    private static MethodHandle MH_MK_STRING;
    private static MethodHandle MH_IO_RESULT_IS_OK;
    private static MethodHandle MH_IO_RESULT_GET_VALUE;

    private LeanRuntime() {}

    /**
     * Initialize the Lean runtime. Must be called before any Lean function.
     * @param libraryPath absolute path to liblean4j.so
     */
    public static synchronized void initialize(String libraryPath) {
        if (INITIALIZED.get()) return;

        System.load(libraryPath);
        LINKER = Linker.nativeLinker();
        LOOKUP = SymbolLookup.loaderLookup()
                .or(SymbolLookup.libraryLookup(libraryPath, Arena.global()));

        // Resolve all bridge handles
        MH_INITIALIZE = lookup("lean4j_initialize",
                FunctionDescriptor.ofVoid());
        MH_INIT_MODULE = lookup("lean4j_init_module",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE));
        MH_MARK_END_INIT = lookup("lean4j_mark_end_init",
                FunctionDescriptor.ofVoid());
        MH_START_TASK_MANAGER = lookup("lean4j_start_task_manager",
                FunctionDescriptor.ofVoid());
        MH_INC = lookup("lean4j_inc",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        MH_DEC = lookup("lean4j_dec",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        MH_STRING_CSTR = lookup("lean4j_string_cstr",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MH_STRING_UTF8_LEN = lookup("lean4j_string_utf8_len",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        MH_MK_STRING = lookup("lean4j_mk_string",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MH_IO_RESULT_IS_OK = lookup("lean4j_io_result_is_ok",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        MH_IO_RESULT_GET_VALUE = lookup("lean4j_io_result_get_value",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        try {
            // Init sequence: runtime → module → mark end → task manager
            MH_INITIALIZE.invoke();
            MemorySegment res = (MemorySegment) MH_INIT_MODULE.invoke((byte) 1);
            if ((int) MH_IO_RESULT_IS_OK.invoke(res) == 0) {
                throw new RuntimeException("Lean module initialization failed");
            }
            MH_MARK_END_INIT.invoke();
            MH_START_TASK_MANAGER.invoke();
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Lean runtime initialization failed", e);
        }

        INITIALIZED.set(true);
    }

    /** Increment a lean_object* refcount. */
    public static void inc(MemorySegment obj) {
        try { MH_INC.invoke(obj); } catch (Throwable e) { throw new AssertionError(e); }
    }

    /** Decrement a lean_object* refcount (frees if it reaches zero). */
    public static void dec(MemorySegment obj) {
        try { MH_DEC.invoke(obj); } catch (Throwable e) { throw new AssertionError(e); }
    }

    /**
     * Convert a Lean string object to a Java String.
     * Does NOT consume the object (does not call dec).
     */
    public static String leanStringToJava(MemorySegment leanStr) {
        try {
            MemorySegment cstr = (MemorySegment) MH_STRING_CSTR.invoke(leanStr);
            long len = (long) MH_STRING_UTF8_LEN.invoke(leanStr);
            return cstr.reinterpret(len + 1).getString(0, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to convert Lean string to Java", e);
        }
    }

    /**
     * Convert a Java String to a Lean string object.
     * Returns a lean_object* with refcount=1. Caller owns it and must call dec().
     */
    public static MemorySegment javaStringToLean(String s, Arena arena) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        MemorySegment buf = arena.allocate(bytes.length + 1);
        buf.asByteBuffer().put(bytes).put((byte) 0);
        try {
            return (MemorySegment) MH_MK_STRING.invoke(buf);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to convert Java string to Lean", e);
        }
    }

    static MethodHandle lookup(String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                LOOKUP.find(name).orElseThrow(() ->
                        new RuntimeException("Symbol not found: " + name)),
                desc);
    }

    static MethodHandle lookupSymbol(String name, FunctionDescriptor desc) {
        return lookup(name, desc);
    }

    public static boolean isInitialized() { return INITIALIZED.get(); }
}
