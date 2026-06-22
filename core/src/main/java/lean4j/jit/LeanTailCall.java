package lean4j.jit;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.RepeatingNode;

/**
 * Self-tail-recursion → loop (manual TCO; Truffle does not do this automatically).
 *
 * <p>Lean compiles {@code for}/{@code fold} to self-tail-recursive helper functions
 * ({@code Std.Range.forIn.loop}, {@code List.foldl}, …). Interpreted naively each
 * iteration is a real call → a Java frame → stack overflow past ~1M iterations. When the
 * translator sees a tail-position call to the enclosing function, it emits {@link Node}:
 * it evaluates the next arguments, writes them straight into the function's parameter
 * slots, and returns the {@link #SENTINEL}. {@link LeanRootNode}'s trampoline loops while
 * it sees the sentinel — bounded stack, no per-iteration allocation or exception, and the
 * loop JIT-compiles cleanly.
 */
final class LeanTailCall {

    private LeanTailCall() {}

    /** Returned up the (executeGeneric) tail path to signal "loop with the rebound params". */
    static final Object SENTINEL = new Object();

    /**
     * The trampoline body, wrapped in a Truffle {@code LoopNode} by {@link LeanRootNode}
     * so the loop is OSR-compiled (a plain Java {@code while} around the body is not).
     * Re-runs the body until it returns a real value instead of {@link #SENTINEL}.
     */
    static final class Trampoline extends com.oracle.truffle.api.nodes.Node implements RepeatingNode {
        @Child private LeanExpressionNode body;
        Trampoline(LeanExpressionNode body) { this.body = body; }

        @Override
        public boolean executeRepeating(VirtualFrame frame) {
            throw CompilerDirectives.shouldNotReachHere(); // executeRepeatingWithValue is used
        }

        @Override
        public Object executeRepeatingWithValue(VirtualFrame frame) {
            Object r = body.executeGeneric(frame);
            return r == SENTINEL ? RepeatingNode.CONTINUE_LOOP_STATUS : r;
        }
    }

    static final class Node extends LeanExpressionNode {
        @Children private final LeanExpressionNode[] argNodes;
        @CompilationFinal(dimensions = 1) private final int[] paramSlots;
        @CompilationFinal(dimensions = 1) private final boolean[] paramLong;

        Node(LeanExpressionNode[] argNodes, int[] paramSlots, boolean[] paramLong) {
            this.argNodes = argNodes;
            this.paramSlots = paramSlots;
            this.paramLong = paramLong;
        }

        @Override
        @ExplodeLoop
        public Object executeGeneric(VirtualFrame frame) {
            // Evaluate ALL args before writing any slot — args may read params being rebound.
            Object[] vals = new Object[argNodes.length];   // scalar-replaced once compiled
            for (int i = 0; i < argNodes.length; i++) {
                vals[i] = argNodes[i].executeGeneric(frame);
            }
            for (int i = 0; i < paramSlots.length; i++) {
                if (paramLong[i]) frame.setLong(paramSlots[i], LeanRT.asLong(vals[i]));
                else frame.setObject(paramSlots[i], vals[i]);
            }
            return SENTINEL;
        }
    }
}
