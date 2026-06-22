package lean4j;

import org.graalvm.polyglot.*;
import org.graalvm.polyglot.proxy.ProxyExecutable;

/**
 * Leancremental — Lean 4's incremental-computation library — driven live from the JVM.
 *
 * <p>Run after building the IR (which now exports the {@code lc*} demo wrappers):
 * {@code java ... -Dir=lean-runtime/leancremental_ir.json -cp ...:core/target/classes LeancrementalDemo}
 *
 * <p>Highlights what this architecture can do that a native Lean binary cannot:
 * <ul>
 *   <li><b>Polyglot operations inside the engine</b> — the prism's two multiplies are a
 *       JavaScript function and a Java lambda, called on Lean's own Float values with no
 *       marshalling, as the incremental graph recomputes.</li>
 *   <li><b>Live embedding</b> — a Lean incremental computation held and driven by a JVM
 *       app: create vars, set inputs, stabilize, read observers, every result verified
 *       against Java's own bookkeeping.</li>
 * </ul>
 * The trade-off (stated honestly below): per-update throughput trails native AOT Lean —
 * this is an interpreter, and the win is interop + embedding, not raw speed.
 */
public final class LeancrementalDemo {
    static Value est(Value r) { return r.hasArrayElements() ? r.getArrayElement(0) : r; }

    public static void main(String[] args) {
        String ir = System.getProperty("ir", "lean-runtime/leancremental_ir.json");
        try (Context c = Context.newBuilder().allowAllAccess(true).allowCreateThread(true).build()) {
            Value m = c.eval("lean4j-jit", ir);

            // ───── 1. The `prism` tutorial — as a POLYGLOT incremental graph ─────
            // volume = (width × depth) × height. First × is a JavaScript function, second
            // is a Java lambda; both run inside Lean's incremental engine on Lean Floats.
            System.out.println("── Tutorial: incremental prism, the two × ops are JS and Java ──");
            Value jsMul = c.eval("js", "(a, b) => a * b");
            ProxyExecutable javaMul = (Value... a) -> a[0].asDouble() * a[1].asDouble();

            Value st     = est(m.invokeMember("lcState", 0));
            Value width  = est(m.invokeMember("lcVar", st, 3.0, 0));
            Value depth  = est(m.invokeMember("lcVar", st, 5.0, 0));
            Value height = est(m.invokeMember("lcVar", st, 4.0, 0));
            Value baseArea = est(m.invokeMember("lcMap2",                  // width×depth — via JS
                    est(m.invokeMember("lcWatch", width, 0)), est(m.invokeMember("lcWatch", depth, 0)), jsMul, 0));
            Value volume = est(m.invokeMember("lcMap2",                    // ×height — via Java
                    baseArea, est(m.invokeMember("lcWatch", height, 0)), javaMul, 0));
            Value obs = est(m.invokeMember("lcObserve", volume, 0));

            est(m.invokeMember("lcStab", st, 0));
            double v1 = est(m.invokeMember("lcVal", obs, 0)).asDouble();
            System.out.printf("   3 × 5 × 4  (JS·area, Java·height) = %.0f%n", v1);
            est(m.invokeMember("lcSet", height, 10.0, 0));
            double stale = est(m.invokeMember("lcVal", obs, 0)).asDouble();
            System.out.printf("   set height=10, no stabilize      → still %.0f (stale, by design)%n", stale);
            est(m.invokeMember("lcStab", st, 0));
            double v2 = est(m.invokeMember("lcVal", obs, 0)).asDouble();
            System.out.printf("   after stabilize                  = %.0f   %s%n", v2,
                    (v1 == 60 && stale == 60 && v2 == 150) ? "✓ matches tutorial" : "✗");

            // ───── 2. Live incremental computation, driven + verified from Java ─────
            int N = 4096;
            System.out.println("\n── Live incremental reduction tree (" + N + " leaves), driven from Java ──");
            Value demo = est(m.invokeMember("lcBuild", N, 0));
            long[] leaf = new long[N];
            long expected = 0;
            for (int i = 0; i < N; i++) { leaf[i] = i; expected += i; }
            long root0 = est(m.invokeMember("lcDVal", demo, 0)).asLong();
            System.out.printf("   initial root (Σ leaves)  = %,d   %s%n", root0, root0 == expected ? "✓" : "✗");
            System.out.println("   changing one leaf dirties only its root-path; the engine recomputes that path.");

            int ROUNDS = 2, BURST = 15_000;
            long cyc = 0;
            System.out.println("   driving update bursts (Java sets leaves, Lean propagates, Java verifies):");
            for (int w = 0; w < ROUNDS; w++) {
                long start = cyc;
                for (int k = 0; k < BURST; k++) {
                    int i = (int) ((start + k) % N); long v = start + k;
                    expected += v - leaf[i]; leaf[i] = v;
                }
                long t0 = System.nanoTime();
                long root = est(m.invokeMember("lcBurst", demo, start, BURST, 0)).asLong();
                double secs = (System.nanoTime() - t0) / 1e9;
                cyc += BURST;
                System.out.printf("     burst %d: %,d updates in %.1fs (%,.0f/s)  root=%,d %s%n",
                        w + 1, BURST, secs, BURST / secs, root, root == expected ? "✓" : "✗");
            }
            System.out.printf("   %,d incremental updates, every root verified against Java's running sum ✓%n", cyc);
            System.out.println("\n   The win is interop + embedding, not raw speed (an interpreter trails native");
            System.out.println("   AOT): a Lean incremental engine runs live in a JVM app, with JS and Java ops");
            System.out.println("   inside the graph and Lean values shared with the host — zero marshalling.");
        }
    }
}
