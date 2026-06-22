package lean4j;

/**
 * Vertical slice spike: exercises Lean→Java call at each level of complexity.
 * Run manually before wiring into a test framework.
 */
public class Lean4JSpike {

    public static void main(String[] args) {
        String libPath = System.getProperty("lean4j.lib",
                "lean-runtime/liblean4j.so");

        System.out.println("=== Lean4J Spike ===");
        System.out.println("Loading: " + libPath);
        LeanRuntime.initialize(libPath);
        System.out.println("Runtime initialized.\n");

        // Step (a): primitive UInt32 — zero boxing, no refcounting
        System.out.println("--- (a) UInt32 arithmetic ---");
        int sum = Lean4J.addUInt32(21, 21);
        System.out.println("addUInt32(21, 21) = " + sum);
        assert sum == 42 : "Expected 42, got " + sum;

        int prod = Lean4J.mulUInt32(6, 7);
        System.out.println("mulUInt32(6, 7) = " + prod);
        assert prod == 42 : "Expected 42, got " + prod;

        // Step (b): UInt64 — still primitive
        System.out.println("\n--- (b) UInt64 Fibonacci ---");
        long[] expected = {0, 1, 1, 2, 3, 5, 8, 13, 21, 34};
        for (int i = 0; i < expected.length; i++) {
            long f = Lean4J.fib(i);
            System.out.printf("fib(%d) = %d%n", i, f);
            assert f == expected[i] : "fib(" + i + ") expected " + expected[i] + ", got " + f;
        }
        long bigFib = Lean4J.fib(50);
        System.out.printf("fib(50) = %d%n", bigFib);

        // Step (c): String → String — boxing + refcount
        System.out.println("\n--- (c) String marshalling ---");
        String greeting = Lean4J.greet("GraalVM");
        System.out.println("greet(\"GraalVM\") = " + greeting);
        assert greeting.equals("Hello from Lean 4, GraalVM!") : "Unexpected: " + greeting;

        String greeting2 = Lean4J.greet("World");
        System.out.println("greet(\"World\") = " + greeting2);

        System.out.println("\n=== All assertions passed ===");
    }
}
