package lean4j.jit;

import java.math.BigInteger;
import java.util.Map;
import java.util.function.Function;

import static lean4j.jit.LeanRT.*;

/**
 * {@code Float} builtins — IEEE-754 doubles. A Lean Float is carried as a boxed
 * {@link Double} in an Object slot (float/float32 are not scalar frame kinds here;
 * a native {@code FrameSlotKind.Double} would be a perf-only optimization, off the
 * hot path). Float constants enter via {@code Float.ofScientific}, not literals.
 */
final class FloatOps {

    private FloatOps() {}

    static void register(Map<String, Function<Object[], Object>> p) {
        // ofScientific(m, sign, e) = m × 10^(sign ? -e : e). parseDouble gives the
        // correctly-rounded nearest double, matching Lean's decimal→Float semantics.
        p.put("Float.ofScientific", a -> {
            Object[] m = meaningful(a);
            long e = natToLong(m[2]);
            long signedExp = (asLong(m[1]) != 0) ? -e : e;
            return Double.parseDouble(String.valueOf(m[0]) + "E" + signedExp);
        });

        // arithmetic
        p.put("Float.add", a -> d(arg1(a)) + d(arg2(a)));
        p.put("Float.sub", a -> d(arg1(a)) - d(arg2(a)));
        p.put("Float.mul", a -> d(arg1(a)) * d(arg2(a)));
        p.put("Float.div", a -> d(arg1(a)) / d(arg2(a)));
        p.put("Float.neg", a -> -d(m1(a)));

        // comparison (→ u8)
        p.put("Float.beq",   a -> d(arg1(a)) == d(arg2(a)) ? 1L : 0L);
        p.put("Float.decLt", a -> d(arg1(a)) <  d(arg2(a)) ? 1L : 0L);
        p.put("Float.decLe", a -> d(arg1(a)) <= d(arg2(a)) ? 1L : 0L);

        // math
        p.put("Float.scaleB", a -> Math.scalb(d(arg1(a)), (int) asLong(arg2(a)))); // x × 2^i
        p.put("Float.sqrt",  a -> Math.sqrt(d(m1(a))));
        p.put("Float.floor", a -> Math.floor(d(m1(a))));
        p.put("Float.ceil",  a -> Math.ceil(d(m1(a))));
        p.put("Float.round", a -> (double) Math.round(d(m1(a))));
        p.put("Float.abs",   a -> Math.abs(d(m1(a))));
        p.put("Float.exp",   a -> Math.exp(d(m1(a))));
        p.put("Float.log",   a -> Math.log(d(m1(a))));
        p.put("Float.pow",   a -> Math.pow(d(arg1(a)), d(arg2(a))));
        // trig / hyperbolic (IEEE doubles via java.lang.Math)
        p.put("Float.sin",   a -> Math.sin(d(m1(a))));
        p.put("Float.cos",   a -> Math.cos(d(m1(a))));
        p.put("Float.tan",   a -> Math.tan(d(m1(a))));
        p.put("Float.asin",  a -> Math.asin(d(m1(a))));
        p.put("Float.acos",  a -> Math.acos(d(m1(a))));
        p.put("Float.atan",  a -> Math.atan(d(m1(a))));
        p.put("Float.atan2", a -> Math.atan2(d(arg1(a)), d(arg2(a))));
        p.put("Float.sinh",  a -> Math.sinh(d(m1(a))));
        p.put("Float.cosh",  a -> Math.cosh(d(m1(a))));
        p.put("Float.tanh",  a -> Math.tanh(d(m1(a))));

        // conversions
        p.put("Float.toString", a -> Double.toString(d(m1(a)))); // may differ slightly from Lean's format
        p.put("Float.toUInt64", a -> (long) d(m1(a)));           // truncate toward zero
        p.put("Float.toUInt32", a -> ((long) d(m1(a))) & 0xFFFF_FFFFL);
        p.put("Float.ofNat",    a -> big(m1(a)).doubleValue());
        p.put("UInt64.toFloat", a -> new BigInteger(Long.toUnsignedString(asLong(m1(a)))).doubleValue()); // unsigned
    }

    /** A Lean Float as a Java double (boxed Double; tolerate a boxed integer defensively). */
    private static double d(Object o) {
        return switch (o) {
            case Double v  -> v;
            case Long l    -> (double) l;
            case Integer i -> (double) i;
            default        -> throw new ClassCastException("Expected Float, got: " + o);
        };
    }
}
