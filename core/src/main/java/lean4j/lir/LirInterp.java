package lean4j.lir;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import lean4j.lir.LirIR.*;

import java.math.BigInteger;
import java.util.Map;
import java.util.function.Function;

/**
 * Recursive interpreter for Lean 4 Compiler IR.
 *
 * Types are preserved at the JVM level: u32→Long, u64→Long, obj(String)→String,
 * tobj/tagged(Nat)→Long or BigInteger. No lean_object* pointers on the C heap.
 * inc/dec/del are no-ops — JVM GC manages object lifetimes instead.
 */
public final class LirInterp {

    private static final int FRAME_SIZE = 128;

    private final Map<String, Decl> decls;
    private final Map<String, Function<Object[], Object>> builtins;

    public LirInterp(Map<String, Decl> decls) {
        this.decls = decls;
        this.builtins = LirBuiltins.all();
    }

    /** Call a named function with the given arguments. */
    @TruffleBoundary
    public Object call(String name, Object[] args) {
        Decl decl = decls.get(name);
        if (decl == null)    throw new RuntimeException("Unknown function: " + name);
        if (!(decl instanceof Decl.FDecl fd))
            throw new RuntimeException("Cannot call extern: " + name);

        Object[] frame = new Object[FRAME_SIZE];
        Param[] params = fd.params();
        if (args.length != params.length)
            throw new IllegalArgumentException(name + " expects " + params.length + " args, got " + args.length);
        for (int i = 0; i < params.length; i++) {
            frame[params[i].id()] = args[i];
        }
        return execBody(fd.body(), frame);
    }

    // ── Body execution ──

    private Object execBody(Body body, Object[] frame) {
        // Iterative where possible (avoids stack overflow for long vdecl chains)
        while (true) {
            switch (body) {
                case Body.VDecl vd -> {
                    frame[vd.varId()] = evalExpr(vd.expr(), frame);
                    body = vd.cont();
                }
                case Body.Inc inc  -> body = inc.cont();
                case Body.Dec dec  -> body = dec.cont();
                case Body.Del del  -> body = del.cont();
                case Body.Set set  -> {
                    // Mutate constructor field — treat as no-op in JVM (immutable objects)
                    body = set.cont();
                }
                case Body.SetTag st -> body = st.cont();
                case Body.USet us   -> body = us.cont();
                case Body.SSet ss   -> body = ss.cont();
                case Body.Ret r     -> { return evalArg(r.arg(), frame); }
                case Body.Unreachable u -> throw new RuntimeException("Lean unreachable reached");
                case Body.Case cs   -> { return execCase(cs, frame); }
                case Body.JDecl jd  -> { return execJDecl(jd, frame); }
                case Body.Jmp jmp   -> {
                    Object[] resolved = resolveArgs(jmp.args(), frame);
                    throw new JmpSignal(jmp.jid(), resolved);
                }
            }
        }
    }

    private Object execCase(Body.Case cs, Object[] frame) {
        Object scrutinee = frame[cs.varId()];
        long tag = LirBuiltins.toLong(scrutinee);
        for (Alt alt : cs.alts()) {
            switch (alt) {
                case Alt.Default def -> { return execBody(def.body(), frame); }
                case Alt.Ctor ctor  -> {
                    if (ctor.info().cidx() == tag) return execBody(ctor.body(), frame);
                }
            }
        }
        throw new RuntimeException("No matching case alt for " + cs.tid() + " tag=" + tag);
    }

    private Object execJDecl(Body.JDecl jd, Object[] frame) {
        // Trampoline: catch JmpSignal and re-run jBody with bound params.
        // The loop handles recursive join points (looping via jmp to self).
        Body current = jd.cont();
        while (true) {
            try {
                return execBody(current, frame);
            } catch (JmpSignal sig) {
                if (sig.jid == jd.jid()) {
                    Param[] params = jd.params();
                    Object[] resolved = sig.resolvedArgs;
                    for (int i = 0; i < params.length; i++) {
                        frame[params[i].id()] = resolved[i];
                    }
                    current = jd.jBody();
                } else {
                    throw sig;
                }
            }
        }
    }

    // ── Expr evaluation ──

    private Object evalExpr(Expr expr, Object[] frame) {
        return switch (expr) {
            case Expr.Fap fap   -> dispatch(fap.fn(), resolveArgs(fap.args(), frame));
            case Expr.Pap pap   -> new LirClosure(pap.fn(), resolveArgs(pap.args(), frame), this);
            case Expr.Ap ap     -> {
                LirClosure cl = (LirClosure) frame[ap.fnId()];
                yield cl.apply(resolveArgs(ap.args(), frame));
            }
            case Expr.Ctor ct   -> new LirObject(ct.info().name(), ct.info().cidx(), resolveArgs(ct.args(), frame));
            case Expr.Proj p    -> ((LirObject) frame[p.varId()]).fields()[p.index()];
            case Expr.UProj p   -> ((LirObject) frame[p.varId()]).fields()[p.index()];
            case Expr.SProj p   -> ((LirObject) frame[p.varId()]).fields()[p.n()];
            case Expr.Reset r   -> frame[r.varId()]; // treat as identity
            case Expr.Reuse r   -> new LirObject(r.info().name(), r.info().cidx(), resolveArgs(r.args(), frame));
            case Expr.Box b     -> frame[b.varId()]; // box = identity in JVM (already object)
            case Expr.Unbox u   -> frame[u.varId()]; // unbox = identity in JVM
            case Expr.Lit l     -> l.value();
            case Expr.IsShared s -> 0L; // always return "not shared" — JVM GC handles it
        };
    }

    // ── Dispatch ──

    private Object dispatch(String fn, Object[] args) {
        Function<Object[], Object> builtin = builtins.get(fn);
        if (builtin != null) return builtin.apply(args);
        return call(fn, args);
    }

    // ── Arg resolution ──

    private Object[] resolveArgs(Arg[] args, Object[] frame) {
        Object[] vals = new Object[args.length];
        for (int i = 0; i < args.length; i++) vals[i] = evalArg(args[i], frame);
        return vals;
    }

    private Object evalArg(Arg arg, Object[] frame) {
        return switch (arg) {
            case Arg.Var v   -> frame[v.id()];
            case Arg.Erased e -> null;
        };
    }

    // ── Join point support ──

    static final class JmpSignal extends RuntimeException {
        final int jid;
        final Object[] resolvedArgs;
        JmpSignal(int jid, Object[] resolvedArgs) {
            super(null, null, true, false); // lightweight: no stack trace
            this.jid = jid;
            this.resolvedArgs = resolvedArgs;
        }
    }
}
