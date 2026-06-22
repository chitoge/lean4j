package lean4j.lir;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import lean4j.lir.LirIR.Decl;
import lean4j.lir.LirIR.Param;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Lean 4 IR module loaded from lean4j_ir.json.
 * Exposes Lean functions as Truffle members callable from any GraalVM language.
 *
 * Unlike {@link LeanModule}, this runs entirely on the JVM —
 * no FFM, no C heap. String stays String, UInt32 stays int.
 */
@ExportLibrary(InteropLibrary.class)
public final class LirModule implements TruffleObject {

    private final Map<String, CallTarget> functions;

    LirModule(Map<String, CallTarget> functions) {
        this.functions = functions;
    }

    static LirModule load(LirLanguage lang, Map<String, Decl> decls) {
        LirInterp interp = new LirInterp(decls);
        Map<String, CallTarget> fns = new LinkedHashMap<>();
        for (Decl decl : decls.values()) {
            if (!(decl instanceof Decl.FDecl fd)) continue;
            Param[] params = fd.params();
            LirRootNode root = new LirRootNode(lang, fd.name(), params, interp);
            fns.put(fd.name(), root.getCallTarget());
        }
        return new LirModule(fns);
    }

    // ── InteropLibrary ──

    @ExportMessage boolean hasMembers() { return true; }

    @ExportMessage
    @TruffleBoundary
    Object getMembers(@SuppressWarnings("unused") boolean includeInternal) {
        return new StringArray(functions.keySet().toArray(String[]::new));
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberReadable(String member) { return functions.containsKey(member); }

    @ExportMessage
    @TruffleBoundary
    Object readMember(String member) throws UnknownIdentifierException {
        CallTarget ct = functions.get(member);
        if (ct == null) throw UnknownIdentifierException.create(member);
        return new LirCallable(member, ct);
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberInvocable(String member) { return functions.containsKey(member); }

    @ExportMessage
    @TruffleBoundary
    Object invokeMember(String member, Object[] args)
            throws UnknownIdentifierException, UnsupportedTypeException, ArityException, UnsupportedMessageException {
        CallTarget ct = functions.get(member);
        if (ct == null) throw UnknownIdentifierException.create(member);
        return ct.call(args);
    }

    @ExportMessage boolean isMemberModifiable(String m) { return false; }
    @ExportMessage boolean isMemberInsertable(String m) { return false; }
    @ExportMessage void writeMember(String m, Object v) throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }
    @ExportMessage boolean hasMetaObject() { return false; }
    @ExportMessage Object getMetaObject() throws UnsupportedMessageException { throw UnsupportedMessageException.create(); }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return "Lean4J-IR[" + functions.size() + " decls]";
    }

    // ── Callable wrapper for readMember ──

    @ExportLibrary(InteropLibrary.class)
    static final class LirCallable implements TruffleObject {
        private final String name;
        private final CallTarget ct;
        LirCallable(String name, CallTarget ct) { this.name = name; this.ct = ct; }

        @ExportMessage boolean isExecutable() { return true; }
        @ExportMessage
        @TruffleBoundary
        Object execute(Object[] args) { return ct.call(args); }
        @ExportMessage
        Object toDisplayString(boolean e) { return "LirFn[" + name + "]"; }
    }

    // ── StringArray for getMembers ──

    @ExportLibrary(InteropLibrary.class)
    static final class StringArray implements TruffleObject {
        private final String[] names;
        StringArray(String[] names) { this.names = names; }
        @ExportMessage boolean hasArrayElements() { return true; }
        @ExportMessage long getArraySize() { return names.length; }
        @ExportMessage boolean isArrayElementReadable(long i) { return i >= 0 && i < names.length; }
        @ExportMessage Object readArrayElement(long i) { return names[(int) i]; }
    }
}
