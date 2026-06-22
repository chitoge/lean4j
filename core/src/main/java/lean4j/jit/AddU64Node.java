package lean4j.jit;

import com.oracle.truffle.api.dsl.Specialization;

/** {@code UInt64.add} — wrapping 64-bit add, kept unboxed as a long. */
public abstract class AddU64Node extends LeanBinaryNode {
    @Specialization
    long add(long a, long b) {
        return a + b;
    }
}
