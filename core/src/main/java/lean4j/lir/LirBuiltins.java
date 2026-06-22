package lean4j.lir;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Implementations of Lean's built-in primitive operations for the JVM interpreter. */
public final class LirBuiltins {

    private LirBuiltins() {}

    /** Returns a map from Lean builtin name → JVM implementation. */
    public static Map<String, Function<Object[], Object>> all() {
        Map<String, Function<Object[], Object>> b = new HashMap<>();

        // ── UInt32 ──
        b.put("UInt32.add", args -> Integer.toUnsignedLong(toInt(args[0]) + toInt(args[1])));
        b.put("UInt32.sub", args -> Integer.toUnsignedLong(toInt(args[0]) - toInt(args[1])));
        b.put("UInt32.mul", args -> Integer.toUnsignedLong(toInt(args[0]) * toInt(args[1])));
        b.put("UInt32.div", args -> Integer.toUnsignedLong(Integer.divideUnsigned(toInt(args[0]), toInt(args[1]))));
        b.put("UInt32.mod", args -> Integer.toUnsignedLong(Integer.remainderUnsigned(toInt(args[0]), toInt(args[1]))));
        b.put("UInt32.decLt", args -> Integer.compareUnsigned(toInt(args[0]), toInt(args[1])) < 0 ? 1L : 0L);
        b.put("UInt32.decLe", args -> Integer.compareUnsigned(toInt(args[0]), toInt(args[1])) <= 0 ? 1L : 0L);
        b.put("UInt32.decEq", args -> args[0].equals(args[1]) ? 1L : 0L);
        b.put("UInt32.toNat", args -> Long.toUnsignedString(toLong(args[0])));

        // ── UInt64 ──
        b.put("UInt64.add", args -> toLong(args[0]) + toLong(args[1]));
        b.put("UInt64.sub", args -> toLong(args[0]) - toLong(args[1]));
        b.put("UInt64.mul", args -> toLong(args[0]) * toLong(args[1]));
        b.put("UInt64.decLt", args -> Long.compareUnsigned(toLong(args[0]), toLong(args[1])) < 0 ? 1L : 0L);
        b.put("UInt64.decLe", args -> Long.compareUnsigned(toLong(args[0]), toLong(args[1])) <= 0 ? 1L : 0L);
        b.put("UInt64.decEq", args -> toLong(args[0]) == toLong(args[1]) ? 1L : 0L);
        b.put("UInt64.toNat", args -> toLong(args[0]));  // Nat as Long (small values)

        // ── Nat ──
        b.put("Nat.decEq", args -> natEq(args[0], args[1]) ? 1L : 0L);
        b.put("Nat.decLt", args -> natLt(args[0], args[1]) ? 1L : 0L);
        b.put("Nat.decLe", args -> (!natLt(args[1], args[0])) ? 1L : 0L);
        b.put("Nat.add", args -> natAdd(args[0], args[1]));
        b.put("Nat.sub", args -> natSub(args[0], args[1]));
        b.put("Nat.mul", args -> natMul(args[0], args[1]));
        b.put("Nat.zero", args -> 0L);
        b.put("Nat.succ", args -> toLong(args[0]) + 1L);

        // ── String ──
        b.put("String.append", args -> toStr(args[0]) + toStr(args[1]));
        b.put("String.decEq", args -> toStr(args[0]).equals(toStr(args[1])) ? 1L : 0L);
        b.put("String.length", args -> (long) toStr(args[0]).length());
        b.put("String.mk", args -> args[0]);

        // ── Bool ──
        b.put("Bool.decEq", args -> args[0].equals(args[1]) ? 1L : 0L);
        b.put("and", args -> (toLong(args[0]) != 0L && toLong(args[1]) != 0L) ? 1L : 0L);
        b.put("or",  args -> (toLong(args[0]) != 0L || toLong(args[1]) != 0L) ? 1L : 0L);
        b.put("not", args -> toLong(args[0]) != 0L ? 0L : 1L);

        return b;
    }

    // ── Type helpers ──

    static String toStr(Object v) {
        if (v instanceof String s) return s;
        try {
            return InteropLibrary.getUncached().asString(v);
        } catch (UnsupportedMessageException e) {
            return v.toString();
        }
    }

    static int toInt(Object v) {
        return switch (v) {
            case Integer i -> i;
            case Long l    -> l.intValue();
            case null      -> 0;
            default        -> throw new ClassCastException("Expected int, got: " + v.getClass());
        };
    }

    static long toLong(Object v) {
        return switch (v) {
            case Long l      -> l;
            case Integer i   -> Integer.toUnsignedLong(i);
            case BigInteger b -> b.longValueExact();
            case null        -> 0L;
            default          -> throw new ClassCastException("Expected long, got: " + v.getClass());
        };
    }

    // ── Nat arithmetic (Long for small, BigInteger for large) ──

    static boolean natEq(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) return la.longValue() == lb.longValue();
        return toBigNat(a).equals(toBigNat(b));
    }

    static boolean natLt(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) return Long.compareUnsigned(la, lb) < 0;
        return toBigNat(a).compareTo(toBigNat(b)) < 0;
    }

    static Object natAdd(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) {
            long result = la + lb;
            // Overflow check: if result < 0 (unsigned overflow into BigInteger territory)
            if (Long.compareUnsigned(result, la) >= 0) return result;
            return toBigNat(a).add(toBigNat(b));
        }
        return toBigNat(a).add(toBigNat(b));
    }

    static Object natSub(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) {
            // Saturating Nat subtraction: 0 if b > a
            return Long.compareUnsigned(lb, la) > 0 ? 0L : la - lb;
        }
        BigInteger ba = toBigNat(a), bb = toBigNat(b);
        BigInteger r = ba.subtract(bb);
        return r.signum() < 0 ? 0L : (r.bitLength() < 63 ? r.longValue() : r);
    }

    static Object natMul(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) {
            // Use BigInteger to detect overflow
            BigInteger r = BigInteger.valueOf(la).multiply(BigInteger.valueOf(lb));
            return r.bitLength() < 63 ? r.longValue() : r;
        }
        return toBigNat(a).multiply(toBigNat(b));
    }

    static BigInteger toBigNat(Object v) {
        return switch (v) {
            case BigInteger b -> b;
            case Long l       -> BigInteger.valueOf(l);
            case Integer i    -> BigInteger.valueOf(Integer.toUnsignedLong(i));
            default           -> throw new ClassCastException("Not a Nat: " + v);
        };
    }
}
