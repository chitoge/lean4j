package lean4j.jit;

import java.util.Map;
import java.util.function.Function;

import static lean4j.jit.LeanRT.*;

/**
 * Fixed-width unsigned integer builtins: UInt8/16/32/64 and USize, all carried as
 * a Java long. {@code UInt32.add/mul}, {@code UInt64.add/sub} and {@code UInt64.toNat}
 * are DSL nodes (unboxed fast path); everything else lives here.
 */
final class UIntOps {

    private UIntOps() {}

    static void register(Map<String, Function<Object[], Object>> p) {
        // ── UInt32 arithmetic + comparison (wrapping 32-bit; unsigned compare) ──
        p.put("UInt32.ofNat", a -> natToLong(m1(a)) & 0xFFFF_FFFFL);
        p.put("UInt32.sub",   a -> (asLong(arg1(a)) - asLong(arg2(a))) & 0xFFFF_FFFFL);
        p.put("UInt32.div",   a -> Long.divideUnsigned(asLong(arg1(a)), asLong(arg2(a))) & 0xFFFF_FFFFL);
        p.put("UInt32.mod",   a -> Long.remainderUnsigned(asLong(arg1(a)), asLong(arg2(a))) & 0xFFFF_FFFFL);
        p.put("UInt32.decLt", a -> Long.compareUnsigned(asLong(arg1(a)), asLong(arg2(a))) < 0 ? 1L : 0L);
        p.put("UInt32.decLe", a -> Long.compareUnsigned(asLong(arg1(a)), asLong(arg2(a))) <= 0 ? 1L : 0L);
        p.put("UInt32.decEq", a -> asLong(arg1(a)) == asLong(arg2(a)) ? 1L : 0L);
        p.put("UInt32.toNat", a -> asLong(m1(a)));
        p.put("UInt32.land",       a -> asLong(arg1(a)) & asLong(arg2(a)));
        p.put("UInt32.lor",        a -> asLong(arg1(a)) | asLong(arg2(a)));
        p.put("UInt32.shiftLeft",  a -> (asLong(arg1(a)) << (asLong(arg2(a)) & 31)) & 0xFFFF_FFFFL);
        p.put("UInt32.toUInt16",   a -> asLong(m1(a)) & 0xFFFFL);
        p.put("UInt32.ofNatLT",       a -> natToLong(arg1(a)) & 0xFFFF_FFFFL);
        p.put("UInt32.ofNatTruncate", a -> natToLong(m1(a)) & 0xFFFF_FFFFL);
        p.put("UInt32.ofBitVec",      a -> asLong(m1(a)) & 0xFFFF_FFFFL);

        // ── UInt64 comparison / bitops / conversions (add/sub/toNat are DSL nodes) ──
        p.put("UInt64.decLt", a -> Long.compareUnsigned(asLong(arg1(a)), asLong(arg2(a))) < 0 ? 1L : 0L);
        p.put("UInt64.decLe", a -> Long.compareUnsigned(asLong(arg1(a)), asLong(arg2(a))) <= 0 ? 1L : 0L);
        p.put("UInt64.decEq", a -> asLong(arg1(a)) == asLong(arg2(a)) ? 1L : 0L);
        p.put("UInt64.mul",   a -> asLong(arg1(a)) * asLong(arg2(a)));
        p.put("UInt64.ofNat",     a -> natToLong(m1(a)));
        p.put("UInt64.toNat",     a -> asLong(m1(a))); // DSL node shadows arity-1 calls; kept for parity
        p.put("UInt64.toUSize",   a -> asLong(m1(a)));
        p.put("UInt64.shiftRight",a -> asLong(arg1(a)) >>> (asLong(arg2(a)) & 63));
        p.put("UInt64.shiftLeft", a -> asLong(arg1(a)) << (asLong(arg2(a)) & 63));
        p.put("UInt64.xor",       a -> asLong(arg1(a)) ^ asLong(arg2(a)));
        p.put("UInt64.land",      a -> asLong(arg1(a)) & asLong(arg2(a)));
        p.put("UInt64.lor",       a -> asLong(arg1(a)) | asLong(arg2(a)));

        // ── UInt16 ──
        p.put("UInt16.decLt",      a -> Long.compareUnsigned(asLong(arg1(a)) & 0xFFFF, asLong(arg2(a)) & 0xFFFF) < 0 ? 1L : 0L);
        p.put("UInt16.lor",        a -> (asLong(arg1(a)) | asLong(arg2(a))) & 0xFFFFL);
        p.put("UInt16.shiftLeft",  a -> (asLong(arg1(a)) << (asLong(arg2(a)) & 15)) & 0xFFFFL);
        p.put("UInt16.toUInt32",   a -> asLong(m1(a)) & 0xFFFFL);

        // ── UInt8 ──
        p.put("UInt8.decEq", a -> (asLong(arg1(a)) & 0xFF) == (asLong(arg2(a)) & 0xFF) ? 1L : 0L);
        p.put("UInt8.toNat", a -> asLong(m1(a)) & 0xFFL);
        p.put("UInt8.land",  a -> (asLong(arg1(a)) & asLong(arg2(a))) & 0xFFL);
        p.put("UInt8.ofNat", a -> natToLong(m1(a)) & 0xFFL);
        p.put("UInt8.decLe", a -> (asLong(arg1(a)) & 0xFF) <= (asLong(arg2(a)) & 0xFF) ? 1L : 0L);
        p.put("UInt32.toUInt8", a -> asLong(m1(a)) & 0xFFL);
        p.put("UInt64.ofNatLT", a -> natToLong(arg1(a)));

        // mixHash = lean_uint64_mix_hash — the building block of every Hashable instance.
        p.put("mixHash", a -> {
            long m = 0xc6a4a7935bd1e995L; int r = 47;
            long h = asLong(arg1(a)), k = asLong(arg2(a));
            k *= m; k ^= k >>> r; k ^= m; h ^= k; h *= m; return h;
        });

        // ── USize ──
        p.put("USize.ofNat",   a -> natToLong(m1(a)));
        p.put("USize.add",     a -> asLong(arg1(a)) + asLong(arg2(a)));
        p.put("USize.mul",     a -> asLong(arg1(a)) * asLong(arg2(a)));
        p.put("USize.decEq",   a -> asLong(arg1(a)) == asLong(arg2(a)) ? 1L : 0L);
        p.put("USize.decLt",   a -> Long.compareUnsigned(asLong(arg1(a)), asLong(arg2(a))) < 0 ? 1L : 0L);
        p.put("USize.decLe",   a -> Long.compareUnsigned(asLong(arg1(a)), asLong(arg2(a))) <= 0 ? 1L : 0L);
        p.put("USize.sub",     a -> asLong(arg1(a)) - asLong(arg2(a)));
        p.put("USize.land",    a -> asLong(arg1(a)) & asLong(arg2(a)));
        p.put("USize.shiftLeft",  a -> asLong(arg1(a)) << (asLong(arg2(a)) & 63));
        p.put("USize.shiftRight", a -> asLong(arg1(a)) >>> (asLong(arg2(a)) & 63));
        p.put("USize.ofNatLT", a -> natToLong(arg1(a)));
        p.put("USize.toNat",   a -> asLong(m1(a)));
        p.put("USize.repr",    a -> Long.toUnsignedString(asLong(m1(a))));
    }
}
