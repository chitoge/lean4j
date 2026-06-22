package lean4j.jit;

import com.oracle.truffle.api.dsl.NodeChild;

/** Base for binary Lean builtins. Children evaluate the two operands. */
@NodeChild(value = "leftNode", type = LeanExpressionNode.class)
@NodeChild(value = "rightNode", type = LeanExpressionNode.class)
public abstract class LeanBinaryNode extends LeanExpressionNode {
}
