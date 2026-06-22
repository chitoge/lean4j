package lean4j.jit;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * The compiled root of a single Lean function. Reads call arguments into typed
 * frame slots, then evaluates the body expression. One CallTarget per Lean decl,
 * so the JIT compiles each function independently and inlines across calls.
 */
final class LeanRootNode extends RootNode {

    private final String name;
    @CompilationFinal(dimensions = 1) private final int[] paramSlots;
    @CompilationFinal(dimensions = 1) private final boolean[] paramLong;
    @Child private LeanExpressionNode body;        // null when self-tail (body is under loopNode)
    @Child private LoopNode loopNode;              // OSR-compiled trampoline, when self-tail
    private final com.oracle.truffle.api.source.SourceSection sourceSection;

    LeanRootNode(TruffleLanguage<?> language, FrameDescriptor descriptor,
                 String name, int[] paramSlots, boolean[] paramLong, LeanExpressionNode body,
                 com.oracle.truffle.api.source.SourceSection sourceSection, boolean hasSelfTail,
                 boolean instrumentable) {
        super(language, descriptor);
        this.name = name;
        this.paramSlots = paramSlots;
        this.paramLong = paramLong;
        this.sourceSection = sourceSection;
        if (hasSelfTail) {
            this.loopNode = Truffle.getRuntime().createLoopNode(new LeanTailCall.Trampoline(body));
        } else if (instrumentable) {
            // Wrap at function entry with an instrumentable node (RootTag) so the debugger can
            // break here, suspend, and inspect the frame. ONLY real (own-range) functions —
            // never compiler-generated specializations/closures (origin-inherited range), whose
            // return values can be internal non-interop types the instrumentation would reject.
            this.body = new LeanRootBody(body, sourceSection);
        } else {
            this.body = body;
        }
    }

    /** The Lean source location of this function — drives stack traces, profiler, debugger. */
    @Override
    public com.oracle.truffle.api.source.SourceSection getSourceSection() {
        return sourceSection;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        bindParams(frame, frame.getArguments());
        // Self-tail-recursion → an OSR-compiled LoopNode trampoline; otherwise run the body
        // directly. A tail call rebinds the param slots and returns SENTINEL up the tail path.
        return loopNode != null ? loopNode.execute(frame) : body.executeGeneric(frame);
    }

    @ExplodeLoop
    private void bindParams(VirtualFrame frame, Object[] args) {
        for (int i = 0; i < paramSlots.length; i++) {
            if (paramLong[i]) {
                frame.setLong(paramSlots[i], coerceLong(args[i]));
            } else {
                frame.setObject(paramSlots[i], coerceObject(args[i]));
            }
        }
    }

    private static long coerceLong(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return Integer.toUnsignedLong(i);
        InteropLibrary lib = InteropLibrary.getUncached();
        if (lib.fitsInLong(v)) {
            try { return lib.asLong(v); } catch (UnsupportedMessageException ignored) { }
        }
        throw new IllegalArgumentException("Expected an integer argument, got: " + v);
    }

    private static Object coerceObject(Object v) {
        if (v == null || v instanceof String) return v; // null = erased/world token
        InteropLibrary lib = InteropLibrary.getUncached();
        if (lib.isString(v)) {
            try { return lib.asString(v); } catch (UnsupportedMessageException ignored) { }
        }
        return v;
    }

    @Override
    public String getName() {
        return "lean4j-jit:" + name;
    }

    @Override
    public String toString() {
        return getName();
    }
}
