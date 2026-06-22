package lean4j.lir;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * A Lean ADT constructor value living on the JVM heap — no C runtime needed.
 *
 * Exposed to interop as an array of its fields (so a host caller can read e.g.
 * the payload of an {@code EST.Out.ok}), with the constructor name as the
 * display string. {@code fields} is mutable to support Lean's RC-reuse stores.
 */
@ExportLibrary(InteropLibrary.class)
public final class LirObject implements TruffleObject {

    private String ctorName;
    private int cidx;
    /** Object/tobject fields, read by {@code proj i}, written by {@code set}. */
    private final Object[] fields;
    /**
     * Packed scalar fields (u8/u16/u32/usize stored inline in the Lean object),
     * read by {@code sproj}/{@code uproj}, written by {@code sset}/{@code uset}.
     * Keyed by the field's offset/index; lazily allocated (most ctors have none).
     */
    private java.util.Map<Integer, Object> scalars;
    private java.util.Map<Integer, Object> usizes;
    /**
     * Conservative uniqueness flag for sound in-place reuse: an `inc` (reference
     * duplication) sets this and it never clears. "Never marked" reliably means
     * uniquely referenced, since well-formed Lean IR `inc`s on every duplication.
     */
    private boolean shared = false;

    public LirObject(String ctorName, int cidx, Object[] fields) {
        this.ctorName = ctorName;
        this.cidx = cidx;
        this.fields = fields;
    }

    public String ctorName() { return ctorName; }
    public int cidx()        { return cidx; }
    public Object[] fields() { return fields; }

    /** Retag this constructor in place ({@code setTag}) — the FBIP cell-reuse path. */
    public void setTag(int newCidx) { this.cidx = newCidx; }

    // ── packed scalar fields (sproj/sset by byte offset) ──
    public Object getScalar(int offset) {
        return scalars == null ? 0L : scalars.getOrDefault(offset, 0L);
    }
    public void setScalar(int offset, Object v) {
        if (scalars == null) scalars = new java.util.HashMap<>(4);
        scalars.put(offset, v);
    }
    // ── packed usize fields (uproj/uset by index) ──
    public Object getUsize(int index) {
        return usizes == null ? 0L : usizes.getOrDefault(index, 0L);
    }
    public void setUsize(int index, Object v) {
        if (usizes == null) usizes = new java.util.HashMap<>(4);
        usizes.put(index, v);
    }

    public boolean isShared() { return shared; }
    public void markShared()  { shared = true; }
    /** A fresh, unique shallow copy (for the copy-on-shared write path). */
    public LirObject copyUnique() {
        LirObject c = new LirObject(ctorName, cidx, fields.clone());
        if (scalars != null) c.scalars = new java.util.HashMap<>(scalars);
        if (usizes != null) c.usizes = new java.util.HashMap<>(usizes);
        return c;
    }

    @ExportMessage boolean hasArrayElements() { return true; }
    @ExportMessage long getArraySize() { return fields.length; }
    @ExportMessage boolean isArrayElementReadable(long i) { return i >= 0 && i < fields.length; }
    @ExportMessage Object readArrayElement(long i) {
        Object v = fields[(int) i];
        return v == null ? "" : v; // erased fields surface as empty
    }

    @ExportMessage Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return toString();
    }

    @Override
    public String toString() {
        if (fields.length == 0) return ctorName;
        StringBuilder sb = new StringBuilder(ctorName).append('(');
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(fields[i]);
        }
        return sb.append(')').toString();
    }
}
