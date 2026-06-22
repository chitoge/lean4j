package lean4j.jit;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;

import java.math.BigInteger;

/**
 * Runtime helpers for the Lean IR JIT: Nat bignum arithmetic and String coercion.
 * Nat is represented as a boxed {@link Long} when it fits unsigned-in-63-bits,
 * otherwise as a {@link BigInteger}.
 */
final class LeanRT {

    private LeanRT() {}

    // ── Lean runtime constants (kept here, a dependency-free leaf, so any
    //    builtin module can reference them without static-init ordering hazards) ──

    /** Lean PUnit.unit — returned by effectful ops whose result is the world token. */
    static final Object UNIT = new lean4j.lir.LirObject("PUnit.unit", 0, new Object[0]);
    /** Lean List.nil. */
    static final Object NIL = new lean4j.lir.LirObject("List.nil", 0, new Object[0]);
    /** Build a Lean List.cons cell. */
    static Object cons(Object head, Object tail) {
        return new lean4j.lir.LirObject("List.cons", 1, new Object[]{ head, tail });
    }

    // ── argument helpers (erased/world args arrive as null) ──

    /** First meaningful (non-erased) argument. */
    static Object m1(Object[] a)   { return meaningful(a)[0]; }
    /** First/second meaningful argument (binary ops with erased type/proof slots). */
    static Object arg1(Object[] a) { return meaningful(a)[0]; }
    static Object arg2(Object[] a) { return meaningful(a)[1]; }

    /** Mark a heap object shared (used where an extern aliases a value, e.g. Ref.get). */
    static Object markShared(Object v) {
        if (v instanceof lean4j.lir.LirObject o) o.markShared();
        else if (v instanceof LeanArray ar) ar.markShared();
        return v;
    }

    // ── ByteArray ↔ byte[] (a ByteArray is a LeanArray of byte-valued Longs) ──

    static Object bytesToArray(byte[] b) {
        LeanArray arr = LeanArray.empty(b.length);
        for (byte x : b) arr = arr.push((long) (x & 0xFF));
        return arr;
    }
    static byte[] arrayToBytes(LeanArray arr) {
        byte[] b = new byte[arr.size()];
        for (int i = 0; i < b.length; i++) b[i] = (byte) asLong(arr.get(i));
        return b;
    }

    // ── String ──

    @TruffleBoundary
    static String str(Object v) {
        if (v instanceof String s) return s;
        try {
            return InteropLibrary.getUncached().asString(v);
        } catch (UnsupportedMessageException e) {
            return String.valueOf(v);
        }
    }

    // ── Nat (Long | BigInteger) ──

    static boolean natEq(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) return la.longValue() == lb.longValue();
        return big(a).equals(big(b));
    }

    static boolean natLt(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) return Long.compareUnsigned(la, lb) < 0;
        return big(a).compareTo(big(b)) < 0;
    }

    static Object natSub(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) {
            return Long.compareUnsigned(lb, la) > 0 ? 0L : (Object) (la - lb);
        }
        BigInteger r = big(a).subtract(big(b));
        return r.signum() < 0 ? 0L : norm(r);
    }

    static Object natAdd(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) {
            long r = la + lb;
            if (Long.compareUnsigned(r, la) >= 0) return r; // no unsigned overflow
        }
        return norm(big(a).add(big(b)));
    }

    static Object natMul(Object a, Object b) {
        BigInteger r = big(a).multiply(big(b));
        return norm(r);
    }

    // ── Int (signed Long | BigInteger; mirrors Nat but allows negatives) ──
    // Int values share the Nat representation (Long when small, else BigInteger);
    // `big`/`norm` work unchanged because they're sign-agnostic.

    static Object intAdd(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) {
            try { return Math.addExact(la, lb); } catch (ArithmeticException overflow) { /* fall through */ }
        }
        return norm(big(a).add(big(b)));
    }
    static Object intSub(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) {
            try { return Math.subtractExact(la, lb); } catch (ArithmeticException overflow) { /* fall through */ }
        }
        return norm(big(a).subtract(big(b)));
    }
    static Object intMul(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) {
            try { return Math.multiplyExact(la, lb); } catch (ArithmeticException overflow) { /* fall through */ }
        }
        return norm(big(a).multiply(big(b)));
    }
    static Object intNeg(Object a) {
        if (a instanceof Long l && l != Long.MIN_VALUE) return -l;
        return norm(big(a).negate());
    }
    static boolean intEq(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) return la.longValue() == lb.longValue();
        return big(a).equals(big(b));
    }
    static boolean intLt(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) return la < lb;
        return big(a).compareTo(big(b)) < 0;
    }
    /** |a| as a Nat. */
    static Object intNatAbs(Object a) {
        if (a instanceof Long l && l != Long.MIN_VALUE) return Math.abs(l);
        return norm(big(a).abs());
    }

    /** Drop erased/world args (which arrive as null), keeping real operands in order. */
    static Object[] meaningful(Object[] a) {
        int n = 0;
        for (Object o : a) if (o != null) n++;
        Object[] r = new Object[n];
        int i = 0;
        for (Object o : a) if (o != null) r[i++] = o;
        return r;
    }

    /** Scalar (u8/u16/u32/u64/usize) value of a boxed integer. */
    static long asLong(Object o) {
        return switch (o) {
            case Long l                 -> l;
            case Integer i              -> Integer.toUnsignedLong(i);
            case java.math.BigInteger b -> b.longValue();
            default                     -> throw new ClassCastException("Expected scalar, got: " + o);
        };
    }

    /** A Nat as a long (truncating a BigInteger to its low 64 bits if huge). */
    static long natToLong(Object v) {
        return switch (v) {
            case Long l       -> l;
            case Integer i    -> Integer.toUnsignedLong(i);
            case BigInteger b -> b.longValue();
            default           -> throw new ClassCastException("Not a Nat: " + v);
        };
    }

    @TruffleBoundary
    static BigInteger big(Object v) {
        return switch (v) {
            case BigInteger b -> b;
            case Long l       -> BigInteger.valueOf(l);
            default           -> throw new ClassCastException("Not a Nat: " + v);
        };
    }

    /** Normalize a BigInteger back to a boxed Long when it fits (sign-agnostic). */
    static Object norm(BigInteger b) {
        return b.bitLength() < 63 ? (Object) b.longValue() : (Object) b;
    }
}
