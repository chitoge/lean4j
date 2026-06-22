package lean4j.jit;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;

/** {@code String.append} — concatenation. Lean String is java.lang.String here. */
public abstract class StringAppendNode extends LeanBinaryNode {
    @Specialization
    @TruffleBoundary
    Object append(Object a, Object b) {
        return LeanRT.str(a) + LeanRT.str(b);
    }
}
