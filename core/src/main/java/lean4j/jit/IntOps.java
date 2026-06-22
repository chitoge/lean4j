package lean4j.jit;

import java.util.Map;
import java.util.function.Function;

import static lean4j.jit.LeanRT.*;

/**
 * {@code Int} builtins — signed, arbitrary precision ({@code Long | BigInteger},
 * sharing the Nat representation). A Nat value is already a valid Int, so
 * {@code Int.ofNat} is identity.
 */
final class IntOps {

    private IntOps() {}

    static void register(Map<String, Function<Object[], Object>> p) {
        p.put("Int.ofNat",  a -> m1(a));               // a Nat is a non-negative Int
        p.put("Int.neg",    a -> intNeg(m1(a)));
        p.put("Int.natAbs", a -> intNatAbs(m1(a)));
        p.put("Int.add",    a -> intAdd(arg1(a), arg2(a)));
        p.put("Int.sub",    a -> intSub(arg1(a), arg2(a)));
        p.put("Int.mul",    a -> intMul(arg1(a), arg2(a)));
        p.put("Int.decEq",  a -> intEq(arg1(a), arg2(a)) ? 1L : 0L);
        p.put("Int.decLt",  a -> intLt(arg1(a), arg2(a)) ? 1L : 0L);
        p.put("Int.decLe",  a -> !intLt(arg2(a), arg1(a)) ? 1L : 0L);
    }
}
