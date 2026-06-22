package lean4j.jit;

import com.oracle.truffle.api.dsl.Specialization;

/** {@code UInt32.add} — wrapping 32-bit add, kept unboxed as a long. */
public abstract class AddU32Node extends LeanBinaryNode {
    @Specialization
    long add(long a, long b) {
        return (a + b) & 0xFFFF_FFFFL;
    }
}
