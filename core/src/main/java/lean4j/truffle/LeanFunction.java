package lean4j.truffle;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import lean4j.LeanRuntime;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.List;

/**
 * A Lean 4 exported function exposed as a GraalVM Truffle polyglot value.
 *
 * Supports cross-language calls from JavaScript, Python, Ruby, Java etc.
 * Type conversion is driven by {@link LeanFunctionDescriptor}.
 */
@ExportLibrary(InteropLibrary.class)
public final class LeanFunction implements TruffleObject {

    private final LeanFunctionDescriptor descriptor;
    private final MethodHandle handle;

    LeanFunction(LeanFunctionDescriptor descriptor, MethodHandle handle) {
        this.descriptor = descriptor;
        this.handle = handle;
    }

    // --- InteropLibrary ---

    @ExportMessage
    boolean isExecutable() { return true; }

    @ExportMessage
    @TruffleBoundary
    Object execute(Object[] args)
            throws ArityException, UnsupportedTypeException, UnsupportedMessageException {
        List<LeanType> params = descriptor.paramTypes();
        if (args.length != params.size()) {
            throw ArityException.create(params.size(), params.size(), args.length);
        }
        try {
            return dispatch(args);
        } catch (Throwable e) {
            throw UnsupportedMessageException.create();
        }
    }

    @ExportMessage
    boolean hasMetaObject() { return false; }

    @ExportMessage
    Object getMetaObject() throws com.oracle.truffle.api.interop.UnsupportedMessageException {
        throw com.oracle.truffle.api.interop.UnsupportedMessageException.create();
    }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return "lean4j::" + descriptor.nativeName() + "/" + descriptor.arity();
    }

    // --- FFM dispatch with type conversion ---

    @TruffleBoundary
    private Object dispatch(Object[] args) throws Throwable {
        List<LeanType> params = descriptor.paramTypes();
        LeanType ret = descriptor.returnType();

        // Build native argument array
        Object[] nativeArgs = new Object[params.size()];
        Arena arena = null;

        try {
            for (int i = 0; i < params.size(); i++) {
                nativeArgs[i] = marshalToNative(args[i], params.get(i),
                        arena == null && params.get(i) == LeanType.STRING
                                ? (arena = Arena.ofConfined()) : arena);
            }

            Object nativeResult = switch (params.size()) {
                case 0 -> handle.invoke();
                case 1 -> handle.invoke(nativeArgs[0]);
                case 2 -> handle.invoke(nativeArgs[0], nativeArgs[1]);
                case 3 -> handle.invoke(nativeArgs[0], nativeArgs[1], nativeArgs[2]);
                default -> handle.invokeWithArguments(nativeArgs);
            };

            return fromNative(nativeResult, ret);
        } finally {
            if (arena != null) arena.close();
        }
    }

    /** Convert polyglot value → native argument for the given LeanType. */
    private static Object marshalToNative(Object val, LeanType type, Arena arena) {
        return switch (type) {
            case UINT32, INT32 -> {
                if (val instanceof Integer i) yield i;
                if (val instanceof Long l) yield l.intValue();
                if (val instanceof Number n) yield n.intValue();
                throw new IllegalArgumentException("Expected int for " + type + ", got " + val.getClass());
            }
            case UINT64, INT64 -> {
                if (val instanceof Long l) yield l;
                if (val instanceof Integer i) yield i.longValue();
                if (val instanceof Number n) yield n.longValue();
                throw new IllegalArgumentException("Expected long for " + type + ", got " + val.getClass());
            }
            case FLOAT32 -> ((Number) val).floatValue();
            case FLOAT64 -> ((Number) val).doubleValue();
            case BOOL -> (boolean) val ? (byte) 1 : (byte) 0;
            case STRING -> {
                if (arena == null) throw new IllegalStateException("Arena required for String");
                yield LeanRuntime.javaStringToLean(val.toString(), arena);
            }
            default -> throw new IllegalArgumentException("Unsupported input type: " + type);
        };
    }

    /** Convert native result → polyglot value for the given LeanType. */
    private static Object fromNative(Object val, LeanType type) {
        return switch (type) {
            case UINT32, INT32 -> val; // int
            case UINT64, INT64 -> val; // long
            case FLOAT32 -> val;       // float
            case FLOAT64 -> val;       // double
            case BOOL -> (byte) val != 0;
            case STRING -> {
                MemorySegment seg = (MemorySegment) val;
                String result = LeanRuntime.leanStringToJava(seg);
                LeanRuntime.dec(seg);  // we own the returned string, release it
                yield result;
            }
            case UNIT -> null;
            default -> val;
        };
    }
}
