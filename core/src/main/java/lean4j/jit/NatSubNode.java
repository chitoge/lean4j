package lean4j.jit;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;

/**
 * {@code Nat.sub} — truncated (saturating-at-zero) Nat subtraction. Fast path
 * handles small Nats (boxed Long); falls back to BigInteger for large values.
 */
public abstract class NatSubNode extends LeanBinaryNode {

    @Specialization(guards = {"isLong(a)", "isLong(b)"})
    Object subLong(Object a, Object b) {
        long x = (Long) a, y = (Long) b;
        return Long.compareUnsigned(y, x) > 0 ? 0L : (Object) (x - y);
    }

    @Specialization(replaces = "subLong")
    @TruffleBoundary
    Object subGeneric(Object a, Object b) {
        return LeanRT.natSub(a, b);
    }

    static boolean isLong(Object o) {
        return o instanceof Long;
    }
}
