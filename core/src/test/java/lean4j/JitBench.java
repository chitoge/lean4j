package lean4j;

import com.oracle.truffle.api.Truffle;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/**
 * A/B benchmark: the same Lean {@code fib} run two ways on the optimizing runtime.
 *
 *   v2 "lean4j-ir"  — tree-walking interpreter behind @TruffleBoundary (no PE)
 *   v3 "lean4j-jit" — PE-friendly Truffle nodes (partial-evaluated, JIT-compiled)
 *
 * Run with -Dpolyglot.engine.TraceCompilation=true to watch the v3 CallTargets
 * ("lean4j-jit:fib", "lean4j-jit:fib.go") compile to native code, while the v2
 * interpreter never does.
 */
public class JitBench {

    // fib(90) = 2880067194370816120 — large recursion depth, still inside u64.
    static final long N = 90L;
    static final long EXPECTED = 2880067194370816120L;

    public static void main(String[] args) throws Exception {
        String irPath = System.getProperty("lean4j.ir", "lean-runtime/lean4j_ir.json");

        System.out.println("=== Lean fib JIT benchmark ===");
        System.out.println("Truffle runtime: " + Truffle.getRuntime().getName()
                + "  (" + Truffle.getRuntime().getClass().getSimpleName() + ")");
        System.out.println("Workload: fib(" + N + "), expected " + EXPECTED + "\n");

        try (Context ctx = Context.newBuilder().allowAllAccess(true).build()) {
            Value jit  = ctx.eval("lean4j-jit", irPath).getMember("fib");
            Value tree = ctx.eval("lean4j-ir",  irPath).getMember("fib");

            // Correctness: both must agree before we time anything.
            check("lean4j-jit", jit.execute(N).asLong());
            check("lean4j-ir",  tree.execute(N).asLong());

            // ── v3: PE nodes (JIT) ──
            System.out.println("\n[lean4j-jit] warming up (triggers compilation)...");
            long jitNs = bench(jit, 200_000, 2_000_000);

            // ── v2: tree-walker (no JIT into Lean) ──
            System.out.println("[lean4j-ir]  warming up...");
            long treeNs = bench(tree, 20_000, 200_000);

            System.out.printf("%n%-14s %12s ns/call%n", "backend", "steady-state");
            System.out.printf("%-14s %12d%n", "lean4j-jit", jitNs);
            System.out.printf("%-14s %12d%n", "lean4j-ir",  treeNs);
            System.out.printf("%nspeedup (tree-walker / JIT nodes): %.1fx%n", (double) treeNs / jitNs);
        }
    }

    /** Returns steady-state ns/call after a warmup phase. */
    private static long bench(Value fib, int warmup, int measure) {
        for (int i = 0; i < warmup; i++) fib.execute(N);
        long start = System.nanoTime();
        long sink = 0;
        for (int i = 0; i < measure; i++) sink += fib.execute(N).asLong();
        long elapsed = System.nanoTime() - start;
        if (sink != EXPECTED * (long) measure) throw new AssertionError("bad sink " + sink);
        return elapsed / measure;
    }

    private static void check(String who, long got) {
        if (got != EXPECTED) throw new AssertionError(who + " fib(" + N + ") = " + got + ", expected " + EXPECTED);
        System.out.println("  " + who + " fib(" + N + ") = " + got + "  ✓");
    }
}
