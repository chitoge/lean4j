package lean4j.jit;

import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.GenerateWrapper;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.ProbeNode;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.interop.NodeLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.source.SourceSection;

/**
 * An instrumentation wrapper at a Lean function's entry — tagged {@code RootTag}/
 * {@code RootBodyTag} with the function's source section, so the Truffle debugger can set
 * a breakpoint on the function, suspend, and walk the live stack. Delegates execution to
 * the real body. Built by {@link LeanRootNode} when source info is available.
 */
@GenerateWrapper
@ExportLibrary(NodeLibrary.class)
public class LeanRootBody extends LeanExpressionNode implements InstrumentableNode {

    @Child private LeanExpressionNode body;
    private final SourceSection section;

    public LeanRootBody(LeanExpressionNode body, SourceSection section) {
        this.body = body;
        this.section = section;
    }

    /** Copy constructor required by {@code @GenerateWrapper}. */
    protected LeanRootBody(LeanRootBody other) {
        this.section = other.section;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return body.executeGeneric(frame);
    }

    @Override
    public boolean isInstrumentable() {
        return section != null;
    }

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        // StatementTag too: it's the haltable anchor a line breakpoint binds to. With only
        // function-level positions the whole body is "one statement" — coarse but it lets
        // the debugger break on entering the function and inspect the live frame.
        return tag == StandardTags.RootTag.class
                || tag == StandardTags.RootBodyTag.class
                || tag == StandardTags.StatementTag.class;
    }

    @Override
    public SourceSection getSourceSection() {
        return section;
    }

    @Override
    public WrapperNode createWrapper(ProbeNode probe) {
        return new LeanRootBodyWrapper(this, this, probe);
    }

    // ── NodeLibrary: expose the live frame's variables to the debugger ──
    @ExportMessage boolean hasScope(@SuppressWarnings("unused") Frame frame) { return true; }

    @ExportMessage
    Object getScope(Frame frame, @SuppressWarnings("unused") boolean nodeEnter) {
        return new LeanScope(frame);
    }
}
