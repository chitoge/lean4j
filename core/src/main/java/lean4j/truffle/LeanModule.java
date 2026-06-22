package lean4j.truffle;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import lean4j.LeanRuntime;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.Map;

/**
 * A loaded Lean 4 shared library exposed as a GraalVM Truffle polyglot object.
 *
 * Members are the exported Lean functions, accessible by native symbol name or Lean name.
 * Usage from JavaScript: {@code lean.lean4j_add_uint32(21, 21)} → 42
 * Usage from Python:     {@code lean.lean4j_greet("World")} → "Hello from Lean 4, World!"
 */
@ExportLibrary(InteropLibrary.class)
public final class LeanModule implements TruffleObject {

    private final String moduleName;
    private final Map<String, LeanFunction> functions;

    LeanModule(String moduleName, Map<String, LeanFunction> functions) {
        this.moduleName = moduleName;
        this.functions = functions;
    }

    // --- InteropLibrary ---

    @ExportMessage
    boolean hasMembers() { return true; }

    @ExportMessage
    @TruffleBoundary
    Object getMembers(@SuppressWarnings("unused") boolean includeInternal) {
        return new StringArray(functions.keySet().toArray(String[]::new));
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberReadable(String member) {
        return functions.containsKey(member);
    }

    @ExportMessage
    @TruffleBoundary
    Object readMember(String member) throws UnknownIdentifierException {
        LeanFunction fn = functions.get(member);
        if (fn == null) throw UnknownIdentifierException.create(member);
        return fn;
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberInvocable(String member) {
        return functions.containsKey(member);
    }

    @ExportMessage
    @TruffleBoundary
    Object invokeMember(String member, Object[] args)
            throws UnknownIdentifierException,
                   com.oracle.truffle.api.interop.UnsupportedTypeException,
                   com.oracle.truffle.api.interop.ArityException,
                   UnsupportedMessageException {
        LeanFunction fn = functions.get(member);
        if (fn == null) throw UnknownIdentifierException.create(member);
        return fn.execute(args);
    }

    @ExportMessage
    boolean isMemberModifiable(@SuppressWarnings("unused") String member) { return false; }

    @ExportMessage
    boolean isMemberInsertable(@SuppressWarnings("unused") String member) { return false; }

    @ExportMessage
    void writeMember(String member, @SuppressWarnings("unused") Object value)
            throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
    boolean hasMetaObject() { return false; }

    @ExportMessage
    Object getMetaObject() throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return "Lean4J[" + moduleName + "]";
    }

    // --- Factory ---

    /**
     * Load a Lean 4 shared library and bind its exported functions.
     * The manifest must be present alongside the .so as <name>_manifest.json.
     */
    @TruffleBoundary
    static LeanModule load(String libraryPath, LeanManifest manifest) {
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = SymbolLookup.libraryLookup(libraryPath, Arena.global());

        Map<String, LeanFunction> fns = new HashMap<>();

        for (LeanFunctionDescriptor desc : manifest.functions()) {
            FunctionDescriptor fd = buildFunctionDescriptor(desc);
            MethodHandle handle = linker.downcallHandle(
                    lookup.find(desc.nativeName()).orElseThrow(() ->
                            new RuntimeException("Symbol not found: " + desc.nativeName())),
                    fd);
            LeanFunction fn = new LeanFunction(desc, handle);
            // Register by both native name and Lean name
            fns.put(desc.nativeName(), fn);
            if (!desc.leanName().equals(desc.nativeName())) {
                fns.put(desc.leanName(), fn);
            }
        }

        return new LeanModule(manifest.module(), fns);
    }

    private static FunctionDescriptor buildFunctionDescriptor(LeanFunctionDescriptor desc) {
        ValueLayout[] params = desc.paramTypes().stream()
                .map(LeanModule::toLeanValueLayout)
                .toArray(ValueLayout[]::new);

        if (desc.returnType() == LeanType.UNIT) {
            return FunctionDescriptor.ofVoid(params);
        }
        return FunctionDescriptor.of(toLeanValueLayout(desc.returnType()), params);
    }

    private static ValueLayout toLeanValueLayout(LeanType type) {
        return switch (type) {
            case UINT32, INT32, BOOL -> ValueLayout.JAVA_INT;
            case UINT64, INT64       -> ValueLayout.JAVA_LONG;
            case FLOAT32             -> ValueLayout.JAVA_FLOAT;
            case FLOAT64             -> ValueLayout.JAVA_DOUBLE;
            case STRING, OBJECT      -> ValueLayout.ADDRESS;
            case UNIT                -> throw new IllegalArgumentException("UNIT has no value layout");
        };
    }

    // --- Minimal TruffleObject for member name array ---

    @ExportLibrary(InteropLibrary.class)
    static final class StringArray implements TruffleObject {
        private final String[] names;
        StringArray(String[] names) { this.names = names; }

        @ExportMessage boolean hasArrayElements() { return true; }
        @ExportMessage long getArraySize() { return names.length; }
        @ExportMessage boolean isArrayElementReadable(long idx) { return idx >= 0 && idx < names.length; }
        @ExportMessage Object readArrayElement(long idx) { return names[(int) idx]; }
    }
}
