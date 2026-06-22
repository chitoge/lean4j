package lean4j.jit;

import com.oracle.truffle.api.dsl.NodeChild;

/** Base for unary Lean builtins. The child evaluates the single operand. */
@NodeChild(value = "argNode", type = LeanExpressionNode.class)
public abstract class LeanUnaryNode extends LeanExpressionNode {
}
