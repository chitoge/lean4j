package lean4j.lir;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

import lean4j.lir.LirIR.Decl;
import lean4j.lir.LirIR.Param;

/**
 * A Truffle RootNode for a single Lean IR function.
 * Arguments are passed via {@link VirtualFrame#getArguments()}.
 */
final class LirRootNode extends RootNode {

    private final String name;
    private final Param[] params;
    private final LirInterp interp;

    LirRootNode(LirLanguage lang, String name, Param[] params, LirInterp interp) {
        super(lang);
        this.name = name;
        this.params = params;
        this.interp = interp;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        // Convert Truffle polyglot values to JVM types expected by the interpreter
        Object[] jvmArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            jvmArgs[i] = coerce(args[i], i < params.length ? params[i].ty() : "obj");
        }
        return interp.call(name, jvmArgs);
    }

    /** Coerce a polyglot value to the JVM type expected by the Lean IR. */
    private static Object coerce(Object val, String ty) {
        return switch (ty) {
            case "u32", "u64", "usize" -> toLong(val);
            case "obj"     -> toJavaString(val);
            default        -> val;
        };
    }

    private static Object toJavaString(Object v) {
        if (v instanceof String) return v;
        var il = com.oracle.truffle.api.interop.InteropLibrary.getUncached();
        if (il.isString(v)) {
            try { return il.asString(v); } catch (com.oracle.truffle.api.interop.UnsupportedMessageException e) { /* fall through */ }
        }
        return v;
    }

    private static long toLong(Object v) {
        return switch (v) {
            case Long l    -> l;
            case Integer i -> Integer.toUnsignedLong(i);
            case com.oracle.truffle.api.interop.TruffleObject to -> {
                try {
                    yield com.oracle.truffle.api.interop.InteropLibrary.getUncached().asLong(to);
                } catch (com.oracle.truffle.api.interop.UnsupportedMessageException e) {
                    throw new RuntimeException("Cannot convert to long: " + to, e);
                }
            }
            default        -> Long.parseLong(v.toString());
        };
    }

    @Override
    public String getName() { return "lean4j-ir:" + name; }
}
