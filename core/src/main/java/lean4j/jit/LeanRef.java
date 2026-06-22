package lean4j.jit;

/**
 * A Lean {@code IO.Ref}/{@code ST.Ref} — a mutable cell on the JVM heap.
 *
 * Lean's IO monad threads an (erased-at-runtime) world token, so effects simply
 * run in IR let-order; the ST.Prim ref externs read/write this cell directly.
 */
public final class LeanRef {
    /** volatile: refs can be read/written across task worker threads. */
    volatile Object value;
    LeanRef(Object value) { this.value = value; }

    @Override public String toString() { return "ref(" + value + ")"; }
}
