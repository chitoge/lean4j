package lean4j.jit;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;

/**
 * A call to another Lean function ({@code fap} to a user decl). Uses a
 * {@link DirectCallNode} so the partial evaluator can inline the callee and the
 * JIT can compile self-recursion (e.g. {@code fib.go}) with on-stack replacement.
 *
 * The target is resolved lazily on first execution: the whole function table is
 * built before anything runs, so by call time every CallTarget exists.
 */
final class LeanCallNode extends LeanExpressionNode {

    private final LeanFunctionRegistry registry;
    private final String target;
    @Children private final LeanExpressionNode[] argNodes;
    @Child @CompilationFinal private DirectCallNode callNode;

    LeanCallNode(LeanFunctionRegistry registry, String target, LeanExpressionNode[] argNodes) {
        this.registry = registry;
        this.target = target;
        this.argNodes = argNodes;
    }

    @CompilationFinal private boolean isGlobal;
    @CompilationFinal private boolean resolved;

    @Override
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame frame) {
        if (!resolved) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            isGlobal = registry.isGlobal(target);
            if (!isGlobal) {
                RootCallTarget ct = registry.lookup(target);
                if (ct == null) {
                    throw CompilerDirectives.shouldNotReachHere("Unknown Lean function: " + target);
                }
                callNode = insert(DirectCallNode.create(ct));
            }
            resolved = true;
        }
        // A module-level `initialize` global: its IR body is ⊥; the value comes
        // from running its initializer (cached after first access).
        if (isGlobal) {
            return registry.globalValue(target);
        }
        Object[] args = new Object[argNodes.length];
        for (int i = 0; i < argNodes.length; i++) {
            args[i] = argNodes[i].executeGeneric(frame);
        }
        // Cooperate with safepoint-based tools (CPU sampler, cancellation). Lean has no
        // TCO here, so deep loops recurse through calls — polling here gives the sampler
        // a point to capture the guest stack. Cheap: a thread-local check the JIT folds.
        TruffleSafepoint.poll(this);
        return callNode.call(args);
    }
}
