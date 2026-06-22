package lean4j.jit;

import com.oracle.truffle.api.RootCallTarget;

import java.util.function.Function;

/**
 * A Lean closure value — a {@code pap} (partial application). Holds the target
 * function, its arity, and the arguments captured so far ({@code captured.length
 * < arity} for a genuine partial application). Applying more arguments via
 * {@code app} either builds a larger closure or, once full, calls the target.
 *
 * A closure may instead be backed by a Java function ({@link #javaImpl}) — used
 * to inject host effects (e.g. the stdout stream's putStr) into Lean's closure
 * world, so {@code app} can invoke a JVM operation as if it were a Lean function.
 */
public final class LeanClosure {

    static final Object[] NO_ARGS = new Object[0];

    final RootCallTarget target;          // null when Java-backed
    final Function<Object[], Object> javaImpl; // null when Lean-backed
    final int arity;
    final Object[] captured;
    final String name; // for diagnostics

    LeanClosure(RootCallTarget target, int arity, Object[] captured, String name) {
        this.target = target;
        this.javaImpl = null;
        this.arity = arity;
        this.captured = captured;
        this.name = name;
    }

    private LeanClosure(Function<Object[], Object> javaImpl, int arity, Object[] captured, String name) {
        this.target = null;
        this.javaImpl = javaImpl;
        this.arity = arity;
        this.captured = captured;
        this.name = name;
    }

    /** A host-backed closure: applying it (once full) calls the Java function. */
    public static LeanClosure ofJava(Function<Object[], Object> impl, int arity, String name) {
        return new LeanClosure(impl, arity, NO_ARGS, name);
    }

    LeanClosure withArgs(Object[] args) {
        return javaImpl != null
            ? new LeanClosure(javaImpl, arity, args, name)
            : new LeanClosure(target, arity, args, name);
    }

    @Override
    public String toString() {
        return "LeanClosure[" + name + " " + captured.length + "/" + arity + "]";
    }
}
