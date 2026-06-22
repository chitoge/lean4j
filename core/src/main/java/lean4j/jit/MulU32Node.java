package lean4j.jit;

import com.oracle.truffle.api.dsl.Specialization;

/** {@code UInt32.mul} — wrapping 32-bit multiply, kept unboxed as a long. */
public abstract class MulU32Node extends LeanBinaryNode {
    @Specialization
    long mul(long a, long b) {
        return (a * b) & 0xFFFF_FFFFL;
    }
}
