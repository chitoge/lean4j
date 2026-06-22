package lean4j.jit;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;

import lean4j.lir.LirObject;

import static lean4j.jit.LeanRT.*;

/**
 * {@code String}, {@code String.Pos}, {@code String.Internal} and {@code Char}
 * builtins. A Lean String is a {@code java.lang.String}; a {@code String.Pos} is a
 * UTF-8 byte offset, so positional ops navigate the UTF-8 byte view.
 * {@code String.append} (arity 2) is also a DSL node; the PRIM entry is unreached.
 */
final class StringOps {

    private StringOps() {}

    static void register(Map<String, Function<Object[], Object>> p) {
        p.put("String.push", a -> { Object[] m = meaningful(a);
            return str(m[0]) + new String(Character.toChars((int) asLong(m[1]))); });
        p.put("String.length", a -> (long) str(m1(a)).codePointCount(0, str(m1(a)).length()));
        p.put("String.decEq",  a -> { Object[] m = meaningful(a); return str(m[0]).equals(str(m[1])) ? 1L : 0L; });
        p.put("String.compare",a -> { Object[] m = meaningful(a); int c = str(m[0]).compareTo(str(m[1])); return (long) (c < 0 ? 0 : c > 0 ? 2 : 1); }); // Ordering: lt=0,eq=1,gt=2
        p.put("String.append", a -> { Object[] m = meaningful(a); return str(m[0]) + str(m[1]); });
        p.put("String.hash",   a -> leanStringHash(str(m1(a))));
        p.put("String.utf8ByteSize", a -> (long) utf8(m1(a)).length);
        p.put("String.ofList", a -> ofCharList(m1(a)));
        p.put("String.getUTF8Byte", a -> { Object[] m = meaningful(a); return (long) (utf8(m[0])[(int) asLong(m[1])] & 0xFF); });

        // String.Pos is a UTF-8 byte offset. get/next/prev/extract navigate the bytes.
        p.put("String.Pos.Raw.get",  a -> { Object[] m = meaningful(a); return (long) cpAt(utf8(m[0]), (int) asLong(m[1])); });
        p.put("String.Pos.Raw.next", a -> { Object[] m = meaningful(a); return (long) nextPos(utf8(m[0]), (int) asLong(m[1])); });
        p.put("String.Pos.next",     a -> { Object[] m = meaningful(a); return (long) nextPos(utf8(m[0]), (int) asLong(m[1])); });
        p.put("String.Pos.Raw.prev", a -> { Object[] m = meaningful(a); return (long) prevPos(utf8(m[0]), (int) asLong(m[1])); });
        p.put("String.decodeChar",   a -> { Object[] m = meaningful(a); return (long) cpAt(utf8(m[0]), (int) asLong(m[1])); });
        p.put("String.Pos.Raw.extract", a -> { Object[] m = meaningful(a);
            byte[] b = utf8(m[0]); int s = (int) asLong(m[1]), e = (int) asLong(m[2]);
            return new String(java.util.Arrays.copyOfRange(b, Math.min(s, b.length), Math.min(e, b.length)), StandardCharsets.UTF_8); });
        p.put("String.Pos.Raw.atEnd", a -> { Object[] m = meaningful(a); return asLong(m[1]) >= utf8(m[0]).length ? 1L : 0L; });
        p.put("String.Pos.Raw.set",   a -> m1(a)); // strings immutable here; set returns the string
        // next'/get' carry a validity proof (erased); same byte navigation as next/get.
        p.put("String.Pos.Raw.next'", a -> { Object[] m = meaningful(a); return (long) nextPos(utf8(m[0]), (int) asLong(m[1])); });
        p.put("String.Pos.Raw.get'",  a -> { Object[] m = meaningful(a); return (long) cpAt(utf8(m[0]), (int) asLong(m[1])); });
        p.put("String.Pos.Raw.isValid", a -> { Object[] m = meaningful(a);
            byte[] b = utf8(m[0]); int pos = (int) asLong(m[1]);
            // valid iff at end, or not in the middle of a UTF-8 multi-byte sequence
            return (pos == b.length || (pos >= 0 && pos < b.length && (b[pos] & 0xC0) != 0x80)) ? 1L : 0L; });
        p.put("String.Internal.contains", a -> { Object[] m = meaningful(a);
            return str(m[0]).indexOf((int) asLong(m[1])) >= 0 ? 1L : 0L; });

        // ── String.Internal: codepoint-indexed navigation over the UTF-8 byte view ──
        p.put("String.Internal.atEnd", a -> { Object[] m = meaningful(a);
            return asLong(m[1]) >= utf8(m[0]).length ? 1L : 0L; });
        p.put("String.Internal.front", a -> (long) cpAt(utf8(m1(a)), 0));
        p.put("String.Internal.get", a -> { Object[] m = meaningful(a);
            return (long) cpAt(utf8(m[0]), (int) asLong(m[1])); });
        p.put("String.Internal.drop", a -> { Object[] m = meaningful(a); // drop n codepoints from front
            byte[] b = utf8(m[0]); int n = (int) asLong(m[1]), i = 0;
            for (int k = 0; k < n && i < b.length; k++) i = nextPos(b, i);
            return new String(b, i, b.length - i, StandardCharsets.UTF_8); });
        p.put("String.Internal.dropRight", a -> { Object[] m = meaningful(a); // drop n codepoints from end
            byte[] b = utf8(m[0]); int n = (int) asLong(m[1]), i = b.length;
            for (int k = 0; k < n && i > 0; k++) i = prevPos(b, i);
            return new String(b, 0, i, StandardCharsets.UTF_8); });
        p.put("String.Pos.Raw.Internal.min", a -> Math.min(asLong(arg1(a)), asLong(arg2(a))));
        p.put("String.Pos.set", a -> { Object[] m = meaningful(a); // set the codepoint at byte pos to c
            byte[] b = utf8(m[0]); int pos = (int) asLong(m[1]), cp = (int) asLong(m[2]);
            int q = nextPos(b, pos);
            return new String(b, 0, pos, StandardCharsets.UTF_8)
                 + new String(Character.toChars(cp))
                 + new String(b, q, b.length - q, StandardCharsets.UTF_8); });

        // ── Substring.Raw = mk(str, startPos, stopPos); positions are absolute byte indices ──
        p.put("Substring.Raw.Internal.isEmpty", a -> { Object[] f = subF(m1(a));
            return asLong(f[1]) >= asLong(f[2]) ? 1L : 0L; });
        p.put("Substring.Raw.Internal.toString", a -> { Object[] f = subF(m1(a));
            byte[] b = utf8(f[0]); int s = (int) asLong(f[1]), e = (int) asLong(f[2]);
            return new String(b, s, Math.max(0, e - s), StandardCharsets.UTF_8); });
        p.put("Substring.Raw.Internal.front", a -> { Object[] f = subF(m1(a));
            int s = (int) asLong(f[1]), e = (int) asLong(f[2]);
            return s < e ? (long) cpAt(utf8(f[0]), s) : 0L; });
        p.put("Substring.Raw.Internal.get", a -> { Object[] m = meaningful(a); Object[] f = subF(m[0]);
            return (long) cpAt(utf8(f[0]), (int) asLong(m[1])); });
        p.put("Substring.Raw.Internal.prev", a -> { Object[] m = meaningful(a); Object[] f = subF(m[0]);
            int s = (int) asLong(f[1]);
            return (long) Math.max(s, prevPos(utf8(f[0]), (int) asLong(m[1]))); });
        p.put("Substring.Raw.Internal.drop", a -> { Object[] m = meaningful(a); Object[] f = subF(m[0]);
            byte[] b = utf8(f[0]); int s = (int) asLong(f[1]), e = (int) asLong(f[2]), n = (int) asLong(m[1]);
            for (int k = 0; k < n && s < e; k++) s = nextPos(b, s);
            return new LirObject("Substring.Raw.mk", 0, new Object[]{ f[0], (long) s, f[2] }); });
        p.put("Substring.Raw.Internal.extract", a -> { Object[] m = meaningful(a); Object[] f = subF(m[0]);
            long s = asLong(f[1]), e = asLong(f[2]);
            long bp = Math.max(s, Math.min(e, asLong(m[1])));
            long ep = Math.max(s, Math.min(e, asLong(m[2])));
            return new LirObject("Substring.Raw.mk", 0, new Object[]{ f[0], bp, ep }); });
        p.put("Substring.Raw.Internal.takeWhile", a -> { Object[] m = meaningful(a); Object[] f = subF(m[0]);
            Object pred = m[1];
            byte[] b = utf8(f[0]); int s = (int) asLong(f[1]), e = (int) asLong(f[2]), i = s;
            while (i < e) {
                Object r = LeanApplyNodes.applyUncached(pred, new Object[]{ (long) cpAt(b, i) });
                if (asLong(r) == 0) break;
                i = nextPos(b, i);
            }
            return new LirObject("Substring.Raw.mk", 0, new Object[]{ f[0], (long) s, (long) i }); });

        // Lean.Name structural equality (anonymous=0, str=1[pre,str], num=2[pre,nat]).
        // Cached hashes live in packed scalar fields, not in fields(), so comparing the
        // object fields is exact.
        p.put("Lean.Name.beq", a -> nameBeq(arg1(a), arg2(a)) ? 1L : 0L);

        // memcmpStr(haystack, needle, hayPos, needlePos, len) → 1 if bytes match (JSON parser).
        p.put("String.Slice.Pattern.Internal.memcmpStr", a -> {
            Object[] m = meaningful(a);
            byte[] h = utf8(m[0]), n = utf8(m[1]);
            int hp = (int) asLong(m[2]), np = (int) asLong(m[3]), len = (int) asLong(m[4]);
            for (int k = 0; k < len; k++) {
                if (hp + k >= h.length || np + k >= n.length || h[hp + k] != n[np + k]) return 0L;
            }
            return 1L;
        });

        // String.Internal.* — the implementations the public String methods delegate to.
        p.put("String.Internal.length",   a -> (long) str(m1(a)).codePointCount(0, str(m1(a)).length()));
        p.put("String.Internal.isEmpty",  a -> str(m1(a)).isEmpty() ? 1L : 0L);
        p.put("String.Internal.append",   a -> { Object[] m = meaningful(a); return str(m[0]) + str(m[1]); });
        p.put("String.Internal.next",     a -> { Object[] m = meaningful(a); return (long) nextPos(utf8(m[0]), (int) asLong(m[1])); });
        p.put("String.Internal.pushn",    a -> { Object[] m = meaningful(a);
            String r = str(m[0]); String c = new String(Character.toChars((int) asLong(m[1]))); long n = asLong(m[2]);
            StringBuilder sb = new StringBuilder(r); for (long k = 0; k < n; k++) sb.append(c); return sb.toString(); });
        p.put("String.Internal.extract",  a -> byteExtract(a));
        p.put("String.extract",           a -> byteExtract(a));
        p.put("String.Internal.posOf",    a -> { Object[] m = meaningful(a);
            byte[] b = utf8(m[0]); int cp = (int) asLong(m[1]); String s = new String(b, StandardCharsets.UTF_8);
            int idx = s.indexOf(cp); return (long) (idx < 0 ? b.length : s.substring(0, idx).getBytes(StandardCharsets.UTF_8).length); });
        p.put("String.Internal.offsetOfPos", a -> { Object[] m = meaningful(a); return asLong(m[1]); }); // pos already a byte offset
        p.put("String.decidableLT",       a -> { Object[] m = meaningful(a); return str(m[0]).compareTo(str(m[1])) < 0 ? 1L : 0L; });
        p.put("String.toList",            a -> strToCharList(str(m1(a))));
        p.put("String.toUTF8",            a -> bytesToArray(utf8(m1(a))));
        p.put("String.ofByteArray",       a -> new String(arrayToBytes((LeanArray) m1(a)), StandardCharsets.UTF_8));

        p.put("Char.ofNatAux",            a -> asLong(meaningful(a)[0]));
    }

    // ── UTF-8 helpers ──

    /** UTF-8 byte view of a Lean String. */
    private static byte[] utf8(Object s) {
        return str(s).getBytes(StandardCharsets.UTF_8);
    }
    /** Fields of a {@code Substring.Raw} structure: [str, startPos, stopPos]. */
    private static Object[] subF(Object sub) {
        return ((LirObject) sub).fields();
    }
    /** Structural equality of two {@code Lean.Name} values. */
    private static boolean nameBeq(Object a, Object b) {
        if (a == b) return true;
        if (!(a instanceof LirObject x) || !(b instanceof LirObject y)) return false;
        if (x.cidx() != y.cidx()) return false;
        if (x.cidx() == 0) return true;                    // anonymous
        Object[] fx = x.fields(), fy = y.fields();
        if (!nameBeq(fx[0], fy[0])) return false;          // compare prefix Name
        return x.cidx() == 1 ? str(fx[1]).equals(str(fy[1])) // str
                             : natEq(fx[1], fy[1]);          // num
    }
    /** Codepoint starting at byte offset {@code i}. */
    private static int cpAt(byte[] b, int i) {
        if (i >= b.length) return 0;
        String s = new String(b, i, b.length - i, StandardCharsets.UTF_8);
        return s.isEmpty() ? 0 : s.codePointAt(0);
    }
    /** Byte offset of the next codepoint after {@code i}. */
    private static int nextPos(byte[] b, int i) {
        if (i >= b.length) return b.length;
        int n = 1;
        int c = b[i] & 0xFF;
        if (c >= 0xF0) n = 4; else if (c >= 0xE0) n = 3; else if (c >= 0xC0) n = 2;
        return Math.min(i + n, b.length);
    }
    /** Byte offset of the codepoint before {@code i}. */
    private static int prevPos(byte[] b, int i) {
        int j = i - 1;
        while (j > 0 && (b[j] & 0xC0) == 0x80) j--; // skip continuation bytes
        return Math.max(j, 0);
    }
    /** String.extract by UTF-8 byte offsets [start, end). */
    private static Object byteExtract(Object[] a) {
        Object[] m = meaningful(a);
        byte[] b = utf8(m[0]);
        int s = (int) asLong(m[1]), e = (int) asLong(m[2]);
        s = Math.max(0, Math.min(s, b.length)); e = Math.max(s, Math.min(e, b.length));
        return new String(java.util.Arrays.copyOfRange(b, s, e), StandardCharsets.UTF_8);
    }
    /**
     * Lean's `lean_string_hash` — MurmurHash2-64A over the UTF-8 bytes, seed 11,
     * little-endian word reads. Verified bit-exact against native `lean --run` for
     * a range of strings. Returns the UInt64 hash as a long bit-pattern.
     */
    private static long leanStringHash(String s) {
        final long m = 0xc6a4a7935bd1e995L;
        final int r = 47;
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        int n = data.length;
        long h = 11L ^ (n * m); // seed 11
        int i = 0;
        for (; i + 8 <= n; i += 8) {
            long k = readLE64(data, i);
            k *= m; k ^= k >>> r; k *= m;
            h ^= k; h *= m;
        }
        long tail = 0;
        for (int j = 0; i + j < n; j++) tail |= (long) (data[i + j] & 0xFF) << (8 * j);
        if (i < n) { h ^= tail; h *= m; }
        h ^= h >>> r; h *= m; h ^= h >>> r;
        return h;
    }
    private static long readLE64(byte[] b, int off) {
        long v = 0;
        for (int j = 0; j < 8; j++) v |= (long) (b[off + j] & 0xFF) << (8 * j);
        return v;
    }
    /** String.ofList : List Char → String. */
    private static Object ofCharList(Object list) {
        StringBuilder sb = new StringBuilder();
        Object cur = list;
        while (cur instanceof lean4j.lir.LirObject o && o.cidx() == 1) { // List.cons
            sb.appendCodePoint((int) asLong(o.fields()[0]));
            cur = o.fields()[1];
        }
        return sb.toString();
    }
    /** String → List Char. */
    private static Object strToCharList(String s) {
        int[] cps = s.codePoints().toArray();
        Object list = NIL;
        for (int i = cps.length - 1; i >= 0; i--) list = cons((long) cps[i], list);
        return list;
    }
}
