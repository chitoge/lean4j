package lean4j.jit;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;

/**
 * {@code Nat.decEq} — returns 1 if the two Nats are equal, else 0 (a u8). The
 * fast path handles small Nats (boxed Long) without touching BigInteger.
 */
public abstract class NatDecEqNode extends LeanBinaryNode {

    @Specialization(guards = {"isLong(a)", "isLong(b)"})
    long eqLong(Object a, Object b) {
        return ((Long) a).longValue() == ((Long) b).longValue() ? 1L : 0L;
    }

    @Specialization(replaces = "eqLong")
    @TruffleBoundary
    long eqGeneric(Object a, Object b) {
        return LeanRT.natEq(a, b) ? 1L : 0L;
    }

    static boolean isLong(Object o) {
        return o instanceof Long;
    }
}
