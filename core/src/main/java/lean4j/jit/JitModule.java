package lean4j.jit;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import lean4j.lir.LirIR.Decl;

import java.util.Map;

/**
 * A JIT-compiled Lean IR module exposed to any GraalVM language. Each member is
 * a Lean function backed by a {@link LeanRootNode} CallTarget — calling it from
 * Java/JS drives the partial evaluator and the function compiles to native code.
 */
@ExportLibrary(InteropLibrary.class)
public final class JitModule implements TruffleObject {

    private final LeanFunctionRegistry registry;

    JitModule(LeanFunctionRegistry registry) {
        this.registry = registry;
    }

    /** Build a module: translate every FDecl to a CallTarget, register by name. */
    static JitModule load(JitLanguage language, Map<String, Decl> decls, Map<String, String> inits,
                          LeanSourceMap srcMap) {
        LeanFunctionRegistry registry = new LeanFunctionRegistry();
        LeanTranslator translator = new LeanTranslator(language, registry, srcMap);
        for (Decl decl : decls.values()) {
            if (decl instanceof Decl.FDecl fd) {
                LeanRootNode root = translator.translate(fd);
                boolean[] erased = new boolean[fd.params().length];
                for (int i = 0; i < erased.length; i++) {
                    String ty = fd.params()[i].ty();          // erased = type arg; void = IO world token
                    erased[i] = "erased".equals(ty) || "void".equals(ty);
                }
                registry.register(fd.name(), (RootCallTarget) root.getCallTarget(), fd.params().length, erased);
            }
        }
        registry.registerInits(inits);
        return new JitModule(registry);
    }

    // ── InteropLibrary ──

    @ExportMessage boolean hasMembers() { return true; }

    @ExportMessage
    @TruffleBoundary
    Object getMembers(@SuppressWarnings("unused") boolean includeInternal) {
        return new StringArray(registry.names().toArray(String[]::new));
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberReadable(String member) {
        return member.equals("api") || registry.lookup(member) != null;
    }

    @ExportMessage
    @TruffleBoundary
    Object readMember(String member) throws UnknownIdentifierException {
        // `module.api` → an ergonomic binding surface: call any Lean function by its clean
        // name with logical args only (erased type args + world token + EST.Out unwrapping
        // handled automatically). The raw `module.invokeMember` path stays unchanged.
        if (member.equals("api")) return new LeanApi(registry);
        RootCallTarget ct = registry.lookup(member);
        if (ct == null) throw UnknownIdentifierException.create(member);
        return new JitCallable(member, ct);
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberInvocable(String member) { return registry.lookup(member) != null; }

    @ExportMessage
    @TruffleBoundary
    Object invokeMember(String member, Object[] args)
            throws UnknownIdentifierException, UnsupportedTypeException, ArityException, UnsupportedMessageException {
        RootCallTarget ct = registry.lookup(member);
        if (ct == null) throw UnknownIdentifierException.create(member);
        return ct.call(coerceHostArgs(args));
    }

    /**
     * Normalize host (Java/JS) numeric args to Lean's native reps — {@code int}/{@code short}/
     * {@code byte} → {@code Long} (a Nat/Int/UInt), {@code float} → {@code Double} (a Float) —
     * so a host caller can pass plain numbers without knowing the interpreter's boxing.
     */
    private static Object[] coerceHostArgs(Object[] args) {
        Object[] out = null;
        for (int i = 0; i < args.length; i++) {
            Object v = args[i], c = v;
            if (v instanceof Integer n)      c = n.longValue();
            else if (v instanceof Short s)   c = s.longValue();
            else if (v instanceof Byte b)    c = b.longValue();
            else if (v instanceof Float f)   c = (double) f;
            if (c != v) { if (out == null) out = args.clone(); out[i] = c; }
        }
        return out == null ? args : out;
    }

    @ExportMessage Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return "Lean4J-JIT[" + registry.names().size() + " decls]";
    }

    // ── Callable wrapper for readMember ──

    @ExportLibrary(InteropLibrary.class)
    static final class JitCallable implements TruffleObject {
        private final String name;
        private final RootCallTarget ct;
        JitCallable(String name, RootCallTarget ct) { this.name = name; this.ct = ct; }

        @ExportMessage boolean isExecutable() { return true; }
        @ExportMessage Object execute(Object[] args) { return ct.call(args); }
        @ExportMessage Object toDisplayString(boolean e) { return "LeanJitFn[" + name + "]"; }
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
