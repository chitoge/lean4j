package lean4j.jit;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Base class for all Lean IR expression nodes — the values that flow through a
 * compiled Lean function.
 *
 * Mirrors the GraalVM SimpleLanguage convention: a generic {@code executeGeneric}
 * plus typed fast paths ({@code executeLong}) so the partial evaluator can keep
 * primitive {@code u32}/{@code u64} values unboxed end-to-end. A node that knows
 * it produces a long overrides {@link #executeLong}; everything else falls back
 * to unboxing the generic result.
 */
@NodeInfo(language = "lean4j-jit")
public abstract class LeanExpressionNode extends Node {

    /** Evaluate to a boxed value (the universal path). */
    public abstract Object executeGeneric(VirtualFrame frame);

    /** Fast path for scalar (u8/u16/u32/u64/usize) values — avoids boxing. */
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        Object value = executeGeneric(frame);
        if (value instanceof Long l) {
            return l;
        }
        throw new UnexpectedResultException(value);
    }
}
