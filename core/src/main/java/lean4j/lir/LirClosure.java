package lean4j.lir;

/** A Lean partial application (pap) — a function with some arguments already captured. */
public final class LirClosure {
    private final String fn;
    private final Object[] captured;
    private final LirInterp interp;

    LirClosure(String fn, Object[] captured, LirInterp interp) {
        this.fn = fn;
        this.captured = captured;
        this.interp = interp;
    }

    Object apply(Object[] moreArgs) {
        Object[] all = new Object[captured.length + moreArgs.length];
        System.arraycopy(captured, 0, all, 0, captured.length);
        System.arraycopy(moreArgs, 0, all, captured.length, moreArgs.length);
        return interp.call(fn, all);
    }
}
