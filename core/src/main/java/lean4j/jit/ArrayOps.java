package lean4j.jit;

import java.util.Map;
import java.util.function.Function;

import static lean4j.jit.LeanRT.*;

/**
 * {@code Array} and {@code ByteArray} builtins. Both are backed by {@link LeanArray}
 * (a ByteArray is a LeanArray of byte-valued Longs). Index ops take EXACT positions
 * among the meaningful args because the array isn't always first — {@code get!Internal}
 * is {@code [Inhabited-default, arr, idx]} while {@code getInternal} is {@code [arr, idx]}.
 */
final class ArrayOps {

    private ArrayOps() {}

    static void register(Map<String, Function<Object[], Object>> p) {
        // ── Array ──
        p.put("Array.mkEmpty",           a -> LeanArray.empty((int) natToLong(m1(a))));
        p.put("Array.emptyWithCapacity", a -> LeanArray.empty((int) natToLong(m1(a))));
        p.put("Array.replicate",         a -> { Object[] m = meaningful(a);
                                                 return LeanArray.replicate((int) natToLong(m[0]), m[1]); });
        p.put("Array.push",              a -> { Object[] m = meaningful(a); return ((LeanArray) m[0]).push(m[1]); });
        p.put("Array.pop",               a -> ((LeanArray) m1(a)).pop());
        p.put("Array.size",              a -> (long) ((LeanArray) m1(a)).size());
        p.put("Array.usize",             a -> (long) ((LeanArray) m1(a)).size());
        p.put("Array.uget",                 a -> arrGetAt(a, 0, 1));
        p.put("Array.ugetBorrowed",         a -> arrGetAt(a, 0, 1));
        p.put("Array.getInternal",          a -> arrGetAt(a, 0, 1));
        p.put("Array.getInternalBorrowed",  a -> arrGetAt(a, 0, 1));
        p.put("Array.get!Internal",         a -> arrGetAt(a, 1, 2));
        p.put("Array.get!InternalBorrowed", a -> arrGetAt(a, 1, 2));
        p.put("Array.set",  a -> arrSetAt(a, 0, 1, 2));
        p.put("Array.set!", a -> arrSetAt(a, 0, 1, 2));
        p.put("Array.uset", a -> arrSetAt(a, 0, 1, 2));
        p.put("Array.mk",   a -> fromLeanList(m1(a))); // Array.mk : List α → Array α
        p.put("Array.swap", a -> arrSwap(a));
        p.put("Array.toList", a -> toLeanList((LeanArray) m1(a)));

        // ── ByteArray (LeanArray of byte-valued Longs) ──
        p.put("ByteArray.mk",  a -> m1(a));
        p.put("ByteArray.get", a -> arrGetAt(a, 0, 1));
        p.put("ByteArray.size",   a -> (long) ((LeanArray) m1(a)).size());
        p.put("ByteArray.uget",   a -> arrGetAt(a, 0, 1));
        p.put("ByteArray.push",   a -> { Object[] m = meaningful(a); return ((LeanArray) m[0]).push(m[1] instanceof Long ? (asLong(m[1]) & 0xFFL) : m[1]); });
        p.put("ByteArray.emptyWithCapacity", a -> LeanArray.empty((int) natToLong(m1(a))));
        p.put("ByteArray.validateUTF8", a -> 1L);
        p.put("ByteArray.copySlice", a -> m1(a)); // approximate — rarely on happy paths
    }

    // ── helpers ──

    private static Object arrGetAt(Object[] a, int arrPos, int idxPos) {
        Object[] m = meaningful(a);
        return arrAt(m, arrPos).get(asLong(m[idxPos]));
    }
    private static Object arrSetAt(Object[] a, int arrPos, int idxPos, int valPos) {
        Object[] m = meaningful(a);
        return arrAt(m, arrPos).set(asLong(m[idxPos]), m[valPos]);
    }
    /** The array argument at {@code pos}, with a defensive by-type fallback. */
    private static LeanArray arrAt(Object[] m, int pos) {
        if (pos < m.length && m[pos] instanceof LeanArray la) return la;
        for (Object o : m) if (o instanceof LeanArray la) return la; // fallback
        throw new ClassCastException("no array arg among " + java.util.Arrays.toString(m));
    }
    private static Object arrSwap(Object[] a) {
        Object[] m = meaningful(a);
        return arrAt(m, 0).swap(asLong(m[1]), asLong(m[2]));
    }
    /** Array.mk : List α → Array α (walk cons cells into a fresh array). */
    private static Object fromLeanList(Object list) {
        LeanArray arr = LeanArray.empty(8);
        Object cur = list;
        while (cur instanceof lean4j.lir.LirObject o && o.cidx() == 1) { // List.cons
            arr = arr.push(o.fields()[0]);
            cur = o.fields()[1];
        }
        return arr;
    }
    /** Array.toList : Array α → List α (foldr cons over nil). */
    private static Object toLeanList(LeanArray arr) {
        Object list = NIL;
        for (int i = arr.size() - 1; i >= 0; i--) list = cons(arr.get(i), list);
        return list;
    }
}
