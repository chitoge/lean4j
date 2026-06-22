package lean4j.jit;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Registry of Lean primitive operations — the JVM-side analogue of libleanshared.
 *
 * Two flavours:
 *  - Hot scalar arithmetic (UInt32.add, …) → specialized Truffle DSL nodes, kept here
 *    because they're tied to the {@code *Node} classes and the unboxed fast path.
 *  - Everything else → generic {@link LeanPrimNode} externs, grouped by DOMAIN into
 *    one {@code register()} module each ({@link NatOps}, {@link UIntOps},
 *    {@link StringOps}, {@link ArrayOps}, {@link EffectOps}, {@link LeanFS}, …).
 *    When Lean renames or changes a primitive across versions, the fix lives in the
 *    obvious domain module rather than in a 300-line block.
 *
 * This class only coordinates registration and dispatch ({@link #isBuiltin}/{@link #build}).
 */
final class LeanBuiltins {

    private LeanBuiltins() {}

    /** Builds a binary builtin node from two operand nodes. */
    @FunctionalInterface
    interface BinFactory {
        LeanExpressionNode create(LeanExpressionNode left, LeanExpressionNode right);
    }

    /** Builds a unary builtin node from one operand node. */
    @FunctionalInterface
    interface UnFactory {
        LeanExpressionNode create(LeanExpressionNode arg);
    }

    private static final Map<String, BinFactory> BINARY = new HashMap<>();
    private static final Map<String, UnFactory> UNARY = new HashMap<>();
    /** Generic N-ary externs, populated by the per-domain {@code register()} modules. */
    private static final Map<String, Function<Object[], Object>> PRIM = new HashMap<>();

    static {
        // Hot scalar arithmetic: specialized DSL nodes (unboxed long fast path).
        BINARY.put("UInt32.add",    AddU32NodeGen::create);
        BINARY.put("UInt32.mul",    MulU32NodeGen::create);
        BINARY.put("UInt64.add",    AddU64NodeGen::create);
        BINARY.put("UInt64.sub",    SubU64NodeGen::create);
        BINARY.put("Nat.decEq",     NatDecEqNodeGen::create);
        BINARY.put("Nat.sub",       NatSubNodeGen::create);
        BINARY.put("String.append", StringAppendNodeGen::create);
        UNARY.put("UInt64.toNat",   U64ToNatNodeGen::create);

        // Generic externs, by domain. To add/fix a primitive, edit the matching module.
        NatOps.register(PRIM);
        IntOps.register(PRIM);
        UIntOps.register(PRIM);
        StringOps.register(PRIM);
        ArrayOps.register(PRIM);
        FloatOps.register(PRIM);
        EffectOps.register(PRIM);
        LeanFS.register(PRIM);
    }

    static boolean isBuiltin(String name, int arity) {
        if (arity == 2 && BINARY.containsKey(name)) return true;
        if (arity == 1 && UNARY.containsKey(name)) return true;
        return PRIM.containsKey(name);
    }

    static LeanExpressionNode build(String name, LeanExpressionNode[] args) {
        if (args.length == 2 && BINARY.containsKey(name)) {
            return BINARY.get(name).create(args[0], args[1]);
        }
        if (args.length == 1 && UNARY.containsKey(name)) {
            return UNARY.get(name).create(args[0]);
        }
        Function<Object[], Object> prim = PRIM.get(name);
        if (prim != null) return new LeanPrimNode(name, args, prim);
        throw new IllegalArgumentException("Not a builtin: " + name + "/" + args.length);
    }
}
