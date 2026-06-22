package lean4j.jit;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;

import java.util.function.Function;

/**
 * A generic N-ary primitive ({@code extern}) call: evaluates its argument nodes
 * and applies a Java implementation. Used for array/Nat/usize runtime ops where
 * arity varies (and erased type/proof args appear); the hot scalar arithmetic
 * keeps its specialized DSL nodes instead.
 */
final class LeanPrimNode extends LeanExpressionNode {

    private final String name;
    @Children private final LeanExpressionNode[] args;
    private final Function<Object[], Object> impl;

    LeanPrimNode(String name, LeanExpressionNode[] args, Function<Object[], Object> impl) {
        this.name = name;
        this.args = args;
        this.impl = impl;
    }

    @Override
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame frame) {
        Object[] values = new Object[args.length];
        for (int i = 0; i < args.length; i++) values[i] = args[i].executeGeneric(frame);
        return run(values);
    }

    @TruffleBoundary
    private Object run(Object[] values) {
        return impl.apply(values);
    }
}
