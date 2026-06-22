package lean4j.jit;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import lean4j.lir.LirObject;

/**
 * Ergonomic binding surface over the raw Lean function registry — reached via
 * {@code module.api}. The host calls any function by its CLEAN Lean name with just its
 * logical arguments; this layer adapts Lean's calling convention automatically:
 * <ul>
 *   <li>erased params (type arguments) → filled with {@code null};</li>
 *   <li>the trailing IO world token → filled with {@code null};</li>
 *   <li>an {@code EST.Out.ok}/{@code error} result is unwrapped (error → host exception).</li>
 * </ul>
 *
 * <p>This is the prototype of the "surface the whole library spec" idea: no hand-written
 * shims, no mangled names. Its boundary (found empirically) is functions that take real
 * typeclass-instance params or non-trivial defaults — those need the auto-wrapper tool
 * (which lets Lean's elaborator monomorphize), not this raw proxy.
 */
@ExportLibrary(InteropLibrary.class)
public final class LeanApi implements TruffleObject {

    private final LeanFunctionRegistry registry;

    LeanApi(LeanFunctionRegistry registry) { this.registry = registry; }

    @ExportMessage boolean hasMembers() { return true; }

    // `surface Ns` generates a hidden `Wrap.<origName>` wrapper per function; calling the
    // ORIGINAL name routes to it, so the host uses exactly the library's documented names.
    private static final String PREFIX = "Wrap.";
    private String resolve(String member) {
        String w = PREFIX + member;
        return registry.lookup(w) != null ? w : member;
    }

    @ExportMessage @TruffleBoundary
    Object getMembers(@SuppressWarnings("unused") boolean includeInternal) {
        // the surfaced API: original names that have a `Wrap.` wrapper (prefix stripped).
        java.util.List<String> bound = new java.util.ArrayList<>();
        for (String n : registry.names())
            if (n.startsWith(PREFIX)) bound.add(n.substring(PREFIX.length()));
        return new JitModule.StringArray(
                (bound.isEmpty() ? registry.names().toArray(String[]::new) : bound.toArray(String[]::new)));
    }

    @ExportMessage @TruffleBoundary
    boolean isMemberInvocable(String member) {
        return registry.lookup(PREFIX + member) != null || registry.lookup(member) != null;
    }

    @ExportMessage @TruffleBoundary
    boolean isMemberReadable(String member) {
        return isMemberInvocable(member);
    }

    // Reading a member returns a callable bound to it, so guest languages that access members
    // by READ-then-call work — Python's `getattr(api, "name")(...)` and JS's `api['name'](...)`,
    // not just Java's explicit `invokeMember`.
    @ExportMessage @TruffleBoundary
    Object readMember(String member) throws UnknownIdentifierException {
        if (!isMemberInvocable(member)) throw UnknownIdentifierException.create(member);
        return new BoundMember(this, member);
    }

    @ExportMessage @TruffleBoundary
    Object invokeMember(String member, Object[] args)
            throws UnknownIdentifierException, UnsupportedTypeException, ArityException {
        String target = resolve(member);
        RootCallTarget ct = registry.lookup(target);
        if (ct == null) throw UnknownIdentifierException.create(member);
        int arity = registry.arity(target);
        boolean[] erased = registry.erasedOf(target);
        Object[] host = coerceHostNumbers(args);

        // Build IR args: erased slots → null; the rest take host args left-to-right; any
        // params left over once the host args run out (the world token) → null.
        Object[] irArgs = new Object[arity];
        int h = 0;
        for (int i = 0; i < arity; i++) {
            if (erased != null && erased[i]) irArgs[i] = null;
            else if (h < host.length) irArgs[i] = host[h++];
            else irArgs[i] = null;
        }
        Object r = ct.call(irArgs);
        return unwrapResult(r);
    }

    /** Unwrap an IO result: {@code EST.Out.ok v} → v; {@code EST.Out.error e} → throw. */
    private static Object unwrapResult(Object r) {
        if (r instanceof LirObject o) {
            String c = o.ctorName();
            if ("EST.Out.ok".equals(c) && o.fields().length > 0) return o.fields()[0];
            if ("EST.Out.error".equals(c) && o.fields().length > 0) {
                throw new RuntimeException("Lean IO error: " + describe(o.fields()[0]));
            }
        }
        return r;
    }

    private static String describe(Object err) {
        if (err instanceof LirObject e && e.fields().length > 0 && e.fields()[0] instanceof String s) return s;
        return String.valueOf(err);
    }

    private static Object[] coerceHostNumbers(Object[] args) {
        Object[] out = null;
        for (int i = 0; i < args.length; i++) {
            Object v = args[i], c = v;
            if (v instanceof Integer n)    c = n.longValue();
            else if (v instanceof Short s) c = s.longValue();
            else if (v instanceof Byte b)  c = b.longValue();
            else if (v instanceof Float f) c = (double) f;
            if (c != v) { if (out == null) out = args.clone(); out[i] = c; }
        }
        return out == null ? args : out;
    }

    @ExportMessage Object toDisplayString(@SuppressWarnings("unused") boolean side) {
        return "Lean4J-API[" + registry.names().size() + " functions]";
    }

    /** A member read off {@link LeanApi} — an executable bound to one function name. */
    @ExportLibrary(InteropLibrary.class)
    static final class BoundMember implements TruffleObject {
        private final LeanApi api;
        private final String member;
        BoundMember(LeanApi api, String member) { this.api = api; this.member = member; }

        @ExportMessage boolean isExecutable() { return true; }

        @ExportMessage @TruffleBoundary
        Object execute(Object[] args) throws UnsupportedTypeException, ArityException {
            try {
                return api.invokeMember(member, args);
            } catch (UnknownIdentifierException e) {
                throw new RuntimeException(e);  // unreachable: the member was validated when read
            }
        }

        @ExportMessage Object toDisplayString(@SuppressWarnings("unused") boolean side) {
            return "Lean4J-API." + member;
        }
    }
}
