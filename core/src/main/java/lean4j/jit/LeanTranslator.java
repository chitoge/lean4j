package lean4j.jit;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;

import lean4j.lir.LirIR.*;
import lean4j.jit.LeanControlNodes.*;
import lean4j.jit.LeanLeafNodes.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates Lean IR ({@link Decl}/{@link Body}/{@link Expr}) into a Truffle AST.
 *
 * Per function: every VarId is assigned a typed frame slot (scalar → Long,
 * obj/tobj/tagged → Object). Lean IR is ANF — each VarId is bound exactly once —
 * so a slot has a single static type; the translator asserts this rather than
 * silently miscompiling if the invariant is ever violated.
 *
 * {@code fap} to a known primitive becomes a specialized builtin node; to another
 * Lean decl, a {@link LeanCallNode}. RC ops (inc/dec/del) are dropped — JVM GC
 * owns lifetimes.
 */
final class LeanTranslator {

    private final JitLanguage language;
    private final LeanFunctionRegistry registry;
    private final LeanSourceMap srcMap;
    private String currentFn;       // function being translated — for self-tail-call detection
    private boolean sawSelfTail;    // set when a self-tail-call node is emitted
    private int[] curParamSlots;    // current function's param slots (for tail-call rebind)
    private boolean[] curParamLong;

    LeanTranslator(JitLanguage language, LeanFunctionRegistry registry, LeanSourceMap srcMap) {
        this.language = language;
        this.registry = registry;
        this.srcMap = srcMap;
    }

    /** Per-function slot allocation: VarId → frame slot index and kind. */
    private static final class Slots {
        final FrameDescriptor descriptor;
        final Map<Integer, Integer> slotOf;
        final Map<Integer, Boolean> longOf;
        Slots(FrameDescriptor d, Map<Integer, Integer> s, Map<Integer, Boolean> l) {
            descriptor = d; slotOf = s; longOf = l;
        }
        int slot(int varId)  { return slotOf.get(varId); }
        boolean isLong(int v){ return longOf.get(v); }
    }

    // ── Public entry: build a RootNode (not yet registered) for one FDecl ──

    LeanRootNode translate(Decl.FDecl fd) {
        Slots slots = allocateSlots(fd);

        int[] paramSlots = new int[fd.params().length];
        boolean[] paramLong = new boolean[fd.params().length];
        for (int i = 0; i < fd.params().length; i++) {
            int id = fd.params()[i].id();
            paramSlots[i] = slots.slot(id);
            paramLong[i] = slots.isLong(id);
        }

        currentFn = fd.name();
        sawSelfTail = false;
        curParamSlots = paramSlots;
        curParamLong = paramLong;
        LeanExpressionNode body = translateBody(fd.body(), slots);
        // Source section on EVERY function (own or origin-inherited) → source-located stack
        // traces; but only INSTRUMENT (allow breakpoints on) functions with their own range.
        return new LeanRootNode(language, slots.descriptor, fd.name(), paramSlots, paramLong, body,
                srcMap == null ? null : srcMap.sectionFor(fd.name()), sawSelfTail,
                srcMap != null && srcMap.hasOwnRange(fd.name()));
    }

    // ── Slot allocation (single-assignment check) ──

    private Slots allocateSlots(Decl.FDecl fd) {
        FrameDescriptor.Builder b = FrameDescriptor.newBuilder();
        Map<Integer, Integer> slotOf = new HashMap<>();
        Map<Integer, Boolean> longOf = new HashMap<>();

        for (Param p : fd.params()) {
            assign(b, slotOf, longOf, p.id(), p.ty(), fd.name());
        }
        collectBodySlots(fd.body(), b, slotOf, longOf, fd.name());

        return new Slots(b.build(), slotOf, longOf);
    }

    private void assign(FrameDescriptor.Builder b, Map<Integer, Integer> slotOf,
                        Map<Integer, Boolean> longOf, int varId, String ty, String fn) {
        if (slotOf.containsKey(varId)) {
            throw new IllegalStateException(
                "Lean IR invariant violated: VarId x_" + varId + " assigned twice in " + fn
                + " (expected single-assignment ANF)");
        }
        boolean isLong = isScalar(ty);
        int slot = b.addSlot(isLong ? FrameSlotKind.Long : FrameSlotKind.Object, "x_" + varId, null);
        slotOf.put(varId, slot);
        longOf.put(varId, isLong);
    }

    private void collectBodySlots(Body body, FrameDescriptor.Builder b,
                                  Map<Integer, Integer> slotOf, Map<Integer, Boolean> longOf, String fn) {
        Body cur = body;
        while (cur != null) {
            switch (cur) {
                case Body.VDecl vd -> { assign(b, slotOf, longOf, vd.varId(), vd.ty(), fn); cur = vd.cont(); }
                case Body.JDecl jd -> {
                    for (Param p : jd.params()) assign(b, slotOf, longOf, p.id(), p.ty(), fn);
                    collectBodySlots(jd.jBody(), b, slotOf, longOf, fn);
                    cur = jd.cont();
                }
                case Body.Inc i    -> cur = i.cont();
                case Body.Dec d    -> cur = d.cont();
                case Body.Del d    -> cur = d.cont();
                case Body.Set s    -> cur = s.cont();
                case Body.SetTag s -> cur = s.cont();
                case Body.USet u   -> cur = u.cont();
                case Body.SSet s   -> cur = s.cont();
                case Body.Case c   -> {
                    for (Alt alt : c.alts()) collectBodySlots(altBody(alt), b, slotOf, longOf, fn);
                    cur = null;
                }
                case Body.Ret r        -> cur = null;
                case Body.Jmp j        -> cur = null;
                case Body.Unreachable u -> cur = null;
            }
        }
    }

    // ── Body translation: vdecl prefix + terminal ──

    private LeanExpressionNode translateBody(Body body, Slots slots) {
        List<LeanControlNodes.Stmt> stmts = new ArrayList<>();
        Body cur = body;
        while (true) {
            switch (cur) {
                case Body.VDecl vd -> {
                    // Self-tail-recursion `let x = currentFn args; ret x` → loop (manual TCO).
                    if (vd.expr() instanceof Expr.Fap fap && fap.fn().equals(currentFn)
                            && vd.cont() instanceof Body.Ret ret
                            && ret.arg() instanceof Arg.Var av && av.id() == vd.varId()) {
                        sawSelfTail = true;
                        LeanExpressionNode tail = new LeanTailCall.Node(
                                translateArgs(fap.args(), slots), curParamSlots, curParamLong);
                        LeanControlNodes.Stmt[] arr = stmts.toArray(new LeanControlNodes.Stmt[0]);
                        return arr.length == 0 ? tail : new Block(arr, tail);
                    }
                    LeanExpressionNode value = translateExpr(vd.expr(), slots);
                    stmts.add(new WriteLocal(slots.slot(vd.varId()), slots.isLong(vd.varId()), value));
                    cur = vd.cont();
                }
                // Object field store (used by the RC-reuse path, which we always take).
                case Body.Set s -> {
                    stmts.add(new LeanControlNodes.SetField(
                        slots.slot(s.varId()), s.i(), translateArg(s.arg(), slots)));
                    cur = s.cont();
                }
                // inc = reference duplication → conservatively mark the object shared
                // (only for object slots; scalars are never inc'd). Enables sound
                // in-place reuse: isShared then reflects real sharing.
                case Body.Inc i -> {
                    if (!slots.isLong(i.varId())) {
                        stmts.add(new LeanControlNodes.MarkShared(slots.slot(i.varId())));
                    }
                    cur = i.cont();
                }
                // dec/del are no-ops under JVM GC.
                case Body.Dec d    -> cur = d.cont();
                case Body.Del d    -> cur = d.cont();
                // Packed scalar / usize field stores.
                case Body.SSet s -> {
                    stmts.add(new LeanControlNodes.SSetField(
                        slots.slot(s.varId()), s.o(),
                        new LeanLeafNodes.ReadLocal(slots.slot(s.yid()), slots.isLong(s.yid()))));
                    cur = s.cont();
                }
                case Body.USet u -> {
                    stmts.add(new LeanControlNodes.USetField(
                        slots.slot(u.varId()), u.i(),
                        new LeanLeafNodes.ReadLocal(slots.slot(u.yid()), slots.isLong(u.yid()))));
                    cur = u.cont();
                }
                // setTag (retag constructor in place) — FBIP cell reuse.
                case Body.SetTag s -> {
                    stmts.add(new LeanControlNodes.SetTag(slots.slot(s.varId()), s.cidx()));
                    cur = s.cont();
                }
                default -> {
                    LeanExpressionNode tail = translateTerminal(cur, slots);
                    LeanControlNodes.Stmt[] arr = stmts.toArray(new LeanControlNodes.Stmt[0]);
                    return arr.length == 0 ? tail : new Block(arr, tail);
                }
            }
        }
    }

    private LeanExpressionNode translateTerminal(Body body, Slots slots) {
        return switch (body) {
            case Body.Ret r -> translateArg(r.arg(), slots);
            case Body.Case c -> translateCase(c, slots);
            case Body.Unreachable u -> new Unreachable();
            case Body.JDecl jd -> translateJDecl(jd, slots);
            case Body.Jmp j -> new LeanControlNodes.Jmp(j.jid(), translateArgs(j.args(), slots));
            default -> throw new IllegalStateException("Unexpected terminal: " + body);
        };
    }

    private LeanExpressionNode translateJDecl(Body.JDecl jd, Slots slots) {
        int[] paramSlots = new int[jd.params().length];
        boolean[] paramLong = new boolean[jd.params().length];
        for (int i = 0; i < jd.params().length; i++) {
            int id = jd.params()[i].id();
            paramSlots[i] = slots.slot(id);
            paramLong[i] = slots.isLong(id);
        }
        LeanExpressionNode body = translateBody(jd.jBody(), slots);
        LeanExpressionNode cont = translateBody(jd.cont(), slots);
        return new LeanControlNodes.JDecl(jd.jid(), paramSlots, paramLong, body, cont);
    }

    private LeanExpressionNode translateCase(Body.Case c, Slots slots) {
        int scrutSlot = slots.slot(c.varId());
        List<Integer> cidx = new ArrayList<>();
        List<LeanExpressionNode> branches = new ArrayList<>();
        LeanExpressionNode defaultBranch = null;
        for (Alt alt : c.alts()) {
            switch (alt) {
                case Alt.Ctor ct -> {
                    cidx.add(ct.info().cidx());
                    branches.add(translateBody(ct.body(), slots));
                }
                case Alt.Default d -> defaultBranch = translateBody(d.body(), slots);
            }
        }
        int[] cidxArr = cidx.stream().mapToInt(Integer::intValue).toArray();
        boolean scalarScrutinee = slots.isLong(c.varId());
        return new Case(scrutSlot, scalarScrutinee, cidxArr,
                branches.toArray(new LeanExpressionNode[0]), defaultBranch);
    }

    // ── Expr translation ──

    private LeanExpressionNode translateExpr(Expr expr, Slots slots) {
        return switch (expr) {
            case Expr.Fap fap -> {
                LeanExpressionNode[] args = translateArgs(fap.args(), slots);
                if (LeanBuiltins.isBuiltin(fap.fn(), args.length)) {
                    yield LeanBuiltins.build(fap.fn(), args);
                }
                yield new LeanCallNode(registry, fap.fn(), args);
            }
            case Expr.Lit l -> translateLit(l.value());
            case Expr.Ctor ct -> new Ctor(ct.info().name(), ct.info().cidx(), translateArgs(ct.args(), slots));
            case Expr.Proj p -> new Proj(slots.slot(p.varId()), p.index());
            case Expr.UProj p -> new LeanLeafNodes.UProj(slots.slot(p.varId()), p.index());
            case Expr.SProj p -> new LeanLeafNodes.SProj(slots.slot(p.varId()), p.o());
            case Expr.Box b -> new LeanLeafNodes.ReadLocal(slots.slot(b.varId()), slots.isLong(b.varId()));
            case Expr.Unbox u -> new LeanLeafNodes.ReadLocal(slots.slot(u.varId()), slots.isLong(u.varId()));
            case Expr.IsShared s -> new LeanLeafNodes.IsShared(slots.slot(s.varId()));
            case Expr.Reset r -> new LeanLeafNodes.ReadLocal(slots.slot(r.varId()), slots.isLong(r.varId()));
            case Expr.Reuse r -> new Ctor(r.info().name(), r.info().cidx(), translateArgs(r.args(), slots));
            case Expr.Pap p -> new LeanApplyNodes.Pap(registry, p.fn(), translateArgs(p.args(), slots));
            case Expr.Ap a -> new LeanApplyNodes.App(
                new LeanLeafNodes.ReadLocal(slots.slot(a.fnId()), slots.isLong(a.fnId())),
                translateArgs(a.args(), slots));
        };
    }

    private LeanExpressionNode translateLit(Object value) {
        // num → Long (small) or BigInteger; str → String. WriteLocal picks the
        // long fast path or boxes based on the target slot kind, so one Long
        // literal node serves both scalar and Nat slots.
        if (value instanceof Long l) return new LongLiteral(l);
        if (value instanceof BigInteger || value instanceof String) return new ObjectLiteral(value);
        return new ObjectLiteral(value);
    }

    private LeanExpressionNode[] translateArgs(Arg[] args, Slots slots) {
        LeanExpressionNode[] nodes = new LeanExpressionNode[args.length];
        for (int i = 0; i < args.length; i++) nodes[i] = translateArg(args[i], slots);
        return nodes;
    }

    private LeanExpressionNode translateArg(Arg arg, Slots slots) {
        return switch (arg) {
            case Arg.Var v   -> new LeanLeafNodes.ReadLocal(slots.slot(v.id()), slots.isLong(v.id()));
            case Arg.Erased e -> new ObjectLiteral(null);
        };
    }

    // ── helpers ──

    private static Body altBody(Alt alt) {
        return switch (alt) {
            case Alt.Ctor c   -> c.body();
            case Alt.Default d -> d.body();
        };
    }

    private static boolean isScalar(String ty) {
        return switch (ty) {
            case "u8", "u16", "u32", "u64", "usize" -> true;
            default -> false; // obj, tobj, tagged, erased, struct, union, float...
        };
    }
}
