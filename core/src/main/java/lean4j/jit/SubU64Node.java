package lean4j.jit;

import com.oracle.truffle.api.dsl.Specialization;

/** {@code UInt64.sub} — wrapping 64-bit subtract, kept unboxed as a long. */
public abstract class SubU64Node extends LeanBinaryNode {
    @Specialization
    long sub(long a, long b) {
        return a - b;
    }
}
