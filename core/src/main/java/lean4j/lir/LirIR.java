package lean4j.lir;

import java.math.BigInteger;

/** Data types for Lean 4 Compiler IR (Lean.Compiler.IR). */
public final class LirIR {

    private LirIR() {}

    // ── Arg ──

    public sealed interface Arg permits Arg.Var, Arg.Erased {
        record Var(int id)    implements Arg {}
        record Erased()       implements Arg {}
    }

    // ── Expr ──

    public sealed interface Expr permits
            Expr.Fap, Expr.Pap, Expr.Ap,
            Expr.Ctor, Expr.Proj, Expr.UProj, Expr.SProj,
            Expr.Reset, Expr.Reuse,
            Expr.Box, Expr.Unbox,
            Expr.Lit, Expr.IsShared {

        record Fap(String fn, Arg[] args)              implements Expr {}
        record Pap(String fn, Arg[] args)              implements Expr {}
        record Ap(int fnId, Arg[] args)                implements Expr {}
        record Ctor(CtorInfo info, Arg[] args)         implements Expr {}
        record Proj(int index, int varId)              implements Expr {}
        record UProj(int index, int varId)             implements Expr {}
        record SProj(int n, int o, int varId)          implements Expr {}
        record Reset(int n, int varId)                 implements Expr {}
        record Reuse(int varId, CtorInfo info, Arg[] args) implements Expr {}
        record Box(String ty, int varId)               implements Expr {}
        record Unbox(int varId)                        implements Expr {}
        record Lit(Object value)                       implements Expr {} // String | Long | BigInteger
        record IsShared(int varId)                     implements Expr {}
    }

    // ── CtorInfo ──

    public record CtorInfo(String name, int cidx, int size, int usize, int ssize) {}

    // ── FnBody ──

    public sealed interface Body permits
            Body.VDecl, Body.JDecl,
            Body.Set, Body.SetTag, Body.USet, Body.SSet,
            Body.Inc, Body.Dec, Body.Del,
            Body.Case, Body.Ret, Body.Jmp, Body.Unreachable {

        record VDecl(int varId, String ty, Expr expr, Body cont) implements Body {}
        record JDecl(int jid, Param[] params, Body jBody, Body cont) implements Body {}
        record Set(int varId, int i, Arg arg, Body cont)     implements Body {}
        record SetTag(int varId, int cidx, Body cont)        implements Body {}
        record USet(int varId, int i, int yid, Body cont)    implements Body {}
        record SSet(int varId, int i, int o, int yid, String ty, Body cont) implements Body {}
        record Inc(int varId, Body cont)                     implements Body {}
        record Dec(int varId, Body cont)                     implements Body {}
        record Del(int varId, Body cont)                     implements Body {}
        record Case(String tid, int varId, String ty, Alt[] alts) implements Body {}
        record Ret(Arg arg)                                  implements Body {}
        record Jmp(int jid, Arg[] args)                      implements Body {}
        record Unreachable()                                 implements Body {}
    }

    // ── Case Alternative ──

    public sealed interface Alt permits Alt.Ctor, Alt.Default {
        record Ctor(CtorInfo info, Body body)  implements Alt {}
        record Default(Body body)              implements Alt {}
    }

    // ── Param ──

    public record Param(int id, boolean borrow, String ty) {}

    // ── Decl ──

    public sealed interface Decl permits Decl.FDecl, Decl.Extern {
        record FDecl(String name, Param[] params, String retTy, Body body) implements Decl {}
        record Extern(String name, Param[] params, String retTy)            implements Decl {}

        default String name() {
            return switch (this) {
                case FDecl f  -> f.name();
                case Extern e -> e.name();
            };
        }
    }
}
