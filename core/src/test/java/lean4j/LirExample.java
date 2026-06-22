package lean4j;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/**
 * Demo: Lean 4 IR interpreter — JVM-native Lean execution with true type preservation.
 *
 * Functions run on the JVM via a Lean IR interpreter: no FFM, no C heap.
 * String stays java.lang.String, UInt32 stays long — same JVM heap as the host.
 */
public class LirExample {

    public static void main(String[] args) throws Exception {
        String irPath = System.getProperty("lean4j.ir",
                "lean-runtime/lean4j_ir.json");

        System.out.println("=== Lean 4 IR Interpreter (JVM-native) ===\n");
        System.out.println("Loading IR from: " + irPath);

        try (Context ctx = Context.newBuilder()
                .allowAllAccess(true)
                .build()) {

            // Load the Lean IR module (no .so, no FFM)
            Value mod = ctx.eval("lean4j-ir", irPath);
            System.out.println("Loaded: " + mod);
            System.out.println("Functions: " + mod.getMemberKeys() + "\n");

            // ── UInt32 arithmetic (zero externs — pure JVM) ──
            System.out.println("--- UInt32 arithmetic ---");
            int sum = mod.invokeMember("addUInt32", 21, 21).asInt();
            System.out.println("addUInt32(21, 21) = " + sum);
            assert sum == 42 : "Expected 42, got " + sum;

            int product = mod.invokeMember("mulUInt32", 6, 7).asInt();
            System.out.println("mulUInt32(6, 7) = " + product);
            assert product == 42 : "Expected 42, got " + product;

            // ── String functions (obj stays String — no lean_object* marshalling) ──
            System.out.println("\n--- String functions ---");
            String greeting = mod.invokeMember("greet", "World").asString();
            System.out.println("greet(\"World\") = " + greeting);
            assert "Hello from Lean 4, World!".equals(greeting)
                    : "Unexpected: " + greeting;

            String greeting2 = mod.invokeMember("greet", "JVM").asString();
            System.out.println("greet(\"JVM\") = " + greeting2);
            assert "Hello from Lean 4, JVM!".equals(greeting2)
                    : "Unexpected: " + greeting2;

            // ── Fibonacci (recursive, Nat via Long) ──
            System.out.println("\n--- Fibonacci ---");
            long fib10 = mod.invokeMember("fib", 10L).asLong();
            System.out.println("fib(10) = " + fib10);
            assert fib10 == 55L : "Expected 55, got " + fib10;

            long fib20 = mod.invokeMember("fib", 20L).asLong();
            System.out.println("fib(20) = " + fib20);
            assert fib20 == 6765L : "Expected 6765, got " + fib20;

            // ── JavaScript calling Lean IR (shared heap) ──
            System.out.println("\n--- JavaScript calling Lean IR ---");
            boolean jsAvailable = ctx.getEngine().getLanguages().containsKey("js");
            if (jsAvailable) {
                String jsCode = """
                    const mod = Polyglot.eval('lean4j-ir', '%s');
                    const r1 = mod.addUInt32(100, 200);
                    const r2 = mod.greet('JavaScript');
                    const r3 = mod.fib(15);
                    console.log('JS: addUInt32(100,200) = ' + r1);
                    console.log('JS: greet("JavaScript") = ' + r2);
                    console.log('JS: fib(15) = ' + r3);
                    r1 + ' | ' + r2 + ' | ' + r3;
                    """.formatted(irPath);
                Value jsResult = ctx.eval("js", jsCode);
                System.out.println("JS result: " + jsResult.asString());
            } else {
                System.out.println("(GraalJS not available — add js-language.jar to classpath)");
            }

            System.out.println("\n=== All Lean IR tests passed ===");
            System.out.println("Note: Lean String is java.lang.String on the JVM heap.");
            System.out.println("      No C runtime, no FFM, no lean_object* marshalling.");
        }
    }
}
