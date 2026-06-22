package lean4j;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/**
 * Minimal JIT smoke check: warm the self-tail-recursive {@code fib} (from the in-repo
 * {@code Lean4J.lean}, lowered by {@code make lir-export} — self-contained, no external
 * library) enough to trigger Truffle compilation. Run by {@code make smoke} with
 * {@code -Dpolyglot.engine.TraceCompilation=true}; the Makefile then asserts a compilation
 * event fired — the guard against the OPTIMIZING runtime silently falling back to
 * interpreter-only on a GraalVM/Truffle version mismatch.
 */
public final class LeanSmoke {
    public static void main(String[] args) {
        String ir = System.getProperty("lean4j.ir");
        try (Context c = Context.newBuilder("lean4j-jit").allowAllAccess(true).build()) {
            Value m = c.eval("lean4j-jit", ir);
            long r = 0;
            for (int i = 0; i < 10; i++) {            // ~50M tail-recursive iterations → OSR compile
                r = m.invokeMember("fib", 5_000_000L).asLong();
            }
            System.out.println("smoke: fib warmup ok, result=" + Long.toUnsignedString(r));
        }
    }
}
