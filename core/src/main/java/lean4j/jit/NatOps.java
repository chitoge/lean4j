package lean4j.jit;

import java.util.Map;
import java.util.function.Function;

import static lean4j.jit.LeanRT.*;

/** {@code Nat} builtins (boxed Long | BigInteger). {@code Nat.decEq}/{@code Nat.sub} are DSL nodes. */
final class NatOps {

    private NatOps() {}

    static void register(Map<String, Function<Object[], Object>> p) {
        p.put("Nat.add",   a -> natAdd(arg1(a), arg2(a)));
        p.put("Nat.decLt", a -> natLt(arg1(a), arg2(a)) ? 1L : 0L);
        p.put("Nat.decLe", a -> !natLt(arg2(a), arg1(a)) ? 1L : 0L);
        p.put("Nat.mul",   a -> natMul(arg1(a), arg2(a)));
        // Arbitrary precision (BigInteger), like natAdd/natMul — no 64-bit truncation.
        p.put("Nat.div",   a -> { java.math.BigInteger d = big(arg2(a)); return d.signum() == 0 ? 0L : norm(big(arg1(a)).divide(d)); });
        p.put("Nat.mod",   a -> { java.math.BigInteger d = big(arg2(a)); return d.signum() == 0 ? arg1(a) : norm(big(arg1(a)).mod(d)); });
        p.put("Nat.pow",   a -> norm(big(arg1(a)).pow((int) natToLong(arg2(a)))));
        p.put("Nat.log2",  a -> { java.math.BigInteger n = big(m1(a)); return n.signum() <= 0 ? 0L : (long) (n.bitLength() - 1); });
        p.put("Nat.shiftLeft",  a -> norm(big(arg1(a)).shiftLeft((int) natToLong(arg2(a)))));
        p.put("Nat.shiftRight", a -> norm(big(arg1(a)).shiftRight((int) natToLong(arg2(a)))));
    }
}
