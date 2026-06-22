package lean4j.jit;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.Arrays;

/**
 * A Lean {@code Array α} on the JVM heap. Lean arrays mutate in place only when
 * uniquely referenced; the {@code shared} flag (set by {@code inc}, never cleared)
 * is a conservative uniqueness signal. Mutators copy-on-shared so a shared array
 * is never corrupted, matching Lean's runtime exclusivity check.
 *
 * <p>Exposes Truffle array interop so it is a valid guest value — required when
 * instrumentation (debugger/profiler) is active (return values are interop-checked),
 * and it lets the debugger expand an array's elements.
 */
@ExportLibrary(InteropLibrary.class)
public final class LeanArray implements TruffleObject {

    private Object[] data;
    private int size;
    private boolean shared = false;

    private LeanArray(Object[] data, int size) { this.data = data; this.size = size; }

    static LeanArray empty(int capacity) { return new LeanArray(new Object[Math.max(capacity, 0)], 0); }

    static LeanArray replicate(int n, Object value) {
        Object[] d = new Object[n];
        Arrays.fill(d, value);
        return new LeanArray(d, n);
    }

    void markShared() { shared = true; }
    boolean isShared() { return shared; }

    /** A fresh, unique copy (logical length only). */
    private LeanArray copyUnique() { return new LeanArray(Arrays.copyOf(data, size), size); }

    int size() { return size; }
    Object get(long i) { return data[(int) i]; }

    /** Push: mutate in place if unique, else copy first. Returns the array to use. */
    LeanArray push(Object value) {
        LeanArray a = shared ? copyUnique() : this;
        if (a.size == a.data.length) a.data = Arrays.copyOf(a.data, Math.max(4, a.data.length * 2));
        a.data[a.size++] = value;
        return a;
    }

    /** Set index i: mutate in place if unique, else copy first. Returns the array to use. */
    LeanArray set(long i, Object value) {
        LeanArray a = shared ? copyUnique() : this;
        if (i >= 0 && i < a.size) a.data[(int) i] = value;
        return a;
    }

    LeanArray pop() {
        LeanArray a = shared ? copyUnique() : this;
        if (a.size > 0) a.data[--a.size] = null;
        return a;
    }

    LeanArray swap(long i, long j) {
        LeanArray a = shared ? copyUnique() : this;
        int ii = (int) i, jj = (int) j;
        if (ii >= 0 && ii < a.size && jj >= 0 && jj < a.size) {
            Object t = a.data[ii]; a.data[ii] = a.data[jj]; a.data[jj] = t;
        }
        return a;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("#[");
        for (int i = 0; i < size; i++) { if (i > 0) sb.append(", "); sb.append(data[i]); }
        return sb.append(']').toString();
    }

    // ── Truffle array interop ──
    @ExportMessage boolean hasArrayElements() { return true; }
    @ExportMessage long getArraySize() { return size; }
    @ExportMessage boolean isArrayElementReadable(long i) { return i >= 0 && i < size; }

    @ExportMessage
    Object readArrayElement(long i) throws InvalidArrayIndexException {
        if (i < 0 || i >= size) throw InvalidArrayIndexException.create(i);
        return data[(int) i];
    }
}
