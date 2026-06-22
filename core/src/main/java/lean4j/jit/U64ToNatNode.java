package lean4j.jit;

import com.oracle.truffle.api.dsl.Specialization;

/**
 * {@code UInt64.toNat} — reinterpret a u64 as a Nat. The long becomes a boxed
 * Nat (Long when it fits); downstream Nat ops unbox it on their fast path.
 */
public abstract class U64ToNatNode extends LeanUnaryNode {
    @Specialization
    Object toNat(long v) {
        return v;
    }
}
