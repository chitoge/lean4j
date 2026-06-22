package lean4j.jit;

/**
 * Thrown by {@code IO.Process.exit} — Lean's {@code exit : UInt8 → IO α} has no
 * returnable value, so termination must propagate as a throwable. In an embedded
 * interpreter we do NOT call {@code System.exit} (that would kill the host JVM);
 * instead the embedder catches this and decides. Guest {@code try}/{@code catch} is
 * IR-level {@code case} on {@code EST.Out} and cannot intercept a Java throwable, so
 * this unwinds cleanly to the host invocation boundary.
 */
public final class LeanExit extends RuntimeException {
    public final int code;
    public LeanExit(int code) {
        super("Lean IO.Process.exit(" + code + ")");
        this.code = code;
    }
}
