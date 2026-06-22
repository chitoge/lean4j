package lean4j.jit;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import java.util.Arrays;

/** Closure construction ({@code pap}) and application ({@code app}). */
final class LeanApplyNodes {

    private LeanApplyNodes() {}

    /** {@code pap fn a1..ak} — capture k args (k < arity) into a closure. */
    static final class Pap extends LeanExpressionNode {
        private final LeanFunctionRegistry registry;
        private final String target;
        @Children private final LeanExpressionNode[] argNodes;
        @CompilationFinal private RootCallTarget callTarget;
        @CompilationFinal private int arity = -1;

        Pap(LeanFunctionRegistry registry, String target, LeanExpressionNode[] argNodes) {
            this.registry = registry;
            this.target = target;
            this.argNodes = argNodes;
        }

        @Override
        @ExplodeLoop
        public Object executeGeneric(VirtualFrame frame) {
            if (callTarget == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                callTarget = registry.lookup(target);
                arity = registry.arity(target);
                if (callTarget == null) {
                    throw CompilerDirectives.shouldNotReachHere("Unknown pap target: " + target);
                }
            }
            Object[] captured = new Object[argNodes.length];
            for (int i = 0; i < argNodes.length; i++) captured[i] = argNodes[i].executeGeneric(frame);
            return new LeanClosure(callTarget, arity, captured, target);
        }
    }

    /** {@code app c b1..bm} — apply closure {@code c} to m more arguments. */
    static final class App extends LeanExpressionNode {
        @Child private LeanExpressionNode fnNode;
        @Children private final LeanExpressionNode[] argNodes;
        @Child private IndirectCallNode callNode = IndirectCallNode.create();

        App(LeanExpressionNode fnNode, LeanExpressionNode[] argNodes) {
            this.fnNode = fnNode;
            this.argNodes = argNodes;
        }

        @Override
        @ExplodeLoop
        public Object executeGeneric(VirtualFrame frame) {
            Object fn = fnNode.executeGeneric(frame);
            Object[] extra = new Object[argNodes.length];
            for (int i = 0; i < argNodes.length; i++) extra[i] = argNodes[i].executeGeneric(frame);
            return apply(callNode, fn, extra);
        }
    }

    /**
     * The closure-application calculus: combine captured + new args, then
     * under-apply (build a bigger closure), exactly apply (call), or
     * over-apply (call, then apply the result to what's left).
     */
    static Object apply(IndirectCallNode callNode, Object fnValue, Object[] extra) {
        if (!(fnValue instanceof LeanClosure closure)) {
            return applyHost(fnValue, extra);  // a host (Java/JS) lambda used as a Lean function
        }
        Object[] all = concat(closure.captured, extra);
        while (true) {
            if (all.length < closure.arity) {
                return closure.withArgs(all);
            }
            Object[] callArgs = (all.length == closure.arity) ? all : Arrays.copyOf(all, closure.arity);
            Object result = closure.javaImpl != null
                ? closure.javaImpl.apply(callArgs)
                : callNode.call(closure.target, callArgs);
            if (all.length == closure.arity) {
                return result;
            }
            // over-application: result must itself be a closure to receive the rest
            if (!(result instanceof LeanClosure next)) {
                throw CompilerDirectives.shouldNotReachHere("over-application of non-closure result");
            }
            Object[] leftover = Arrays.copyOfRange(all, closure.arity, all.length);
            closure = next;
            all = concat(next.captured, leftover);
        }
    }

    /** Apply a closure from non-node (extern) code, calling the target directly. */
    static Object applyUncached(Object fnValue, Object[] extra) {
        if (!(fnValue instanceof LeanClosure closure)) {
            return applyHost(fnValue, extra);  // a host (Java/JS) lambda used as a Lean function
        }
        Object[] all = concat(closure.captured, extra);
        while (true) {
            if (all.length < closure.arity) return closure.withArgs(all);
            Object[] callArgs = (all.length == closure.arity) ? all : Arrays.copyOf(all, closure.arity);
            Object result = closure.javaImpl != null
                ? closure.javaImpl.apply(callArgs)
                : closure.target.call(callArgs);
            if (all.length == closure.arity) return result;
            if (!(result instanceof LeanClosure next)) {
                throw new IllegalStateException("over-application of non-closure");
            }
            Object[] leftover = Arrays.copyOfRange(all, closure.arity, all.length);
            closure = next;
            all = concat(next.captured, leftover);
        }
    }

    /**
     * Apply a host (Java/JS) lambda passed where Lean expects a function — the
     * zero-friction interop path: a guest closure can be a host {@code ProxyExecutable}
     * or functional interface. Lean values (Long/Double/String/LirObject) cross to the
     * host as polyglot Values; the host's primitive result crosses back as a Lean-native
     * scalar. This is what lets Java/JS drive higher-order Lean APIs (map/map2/fold, …)
     * without a per-operation Lean wrapper.
     */
    @TruffleBoundary
    static Object applyHost(Object fnValue, Object[] args) {
        InteropLibrary interop = InteropLibrary.getUncached();
        if (!interop.isExecutable(fnValue)) {
            throw new IllegalStateException("app target is neither a Lean closure nor a host function: " + fnValue);
        }
        try {
            return coerceHostResult(interop.execute(fnValue, args), interop);
        } catch (Exception e) {
            throw new RuntimeException("host function (used as a Lean closure) failed: " + e, e);
        }
    }

    private static Object coerceHostResult(Object r, InteropLibrary interop) {
        // Host primitives already arrive boxed as our native types — the return *type*
        // (long vs double) disambiguates Nat from Float, no guessing needed.
        if (r == null || r instanceof Long || r instanceof Double || r instanceof String
                || r instanceof lean4j.lir.LirObject) return r;
        if (r instanceof Boolean b) return b ? 1L : 0L;
        if (r instanceof Integer i) return (long) i;
        if (r instanceof Float f) return (double) f;
        try {                                   // a wrapped polyglot value from the host
            if (interop.isNull(r)) return null;
            if (interop.isString(r)) return interop.asString(r);
            if (interop.isBoolean(r)) return interop.asBoolean(r) ? 1L : 0L;
            if (interop.fitsInLong(r)) return interop.asLong(r);
            if (interop.fitsInDouble(r)) return interop.asDouble(r);
        } catch (Exception ignored) { }
        return r;
    }

    private static Object[] concat(Object[] a, Object[] b) {
        if (a.length == 0) return b;
        if (b.length == 0) return a;
        Object[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
