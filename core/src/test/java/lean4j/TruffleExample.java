package lean4j;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

/**
 * Demo: Lean 4 functions called from GraalVM polyglot context.
 *
 * This shows Lean 4 working as a first-class GraalVM language —
 * functions are accessible from JavaScript, Python, and Java.
 */
public class TruffleExample {

    public static void main(String[] args) throws Exception {
        String libPath = System.getProperty("lean4j.lib",
                "lean-runtime/liblean4j.so");

        System.out.println("=== Lean 4 on GraalVM Truffle ===\n");

        try (Context ctx = Context.newBuilder()
                .allowAllAccess(true)
                .build()) {

            // ── 1. Load the Lean 4 library as a polyglot object ──
            System.out.println("Loading Lean 4 library via Truffle...");
            Value lean = ctx.eval("lean4j", libPath);
            System.out.println("Loaded: " + lean);
            System.out.println("Members: " + lean.getMemberKeys() + "\n");

            // ── 2. Call from Java via Polyglot API ──
            System.out.println("--- Java polyglot calls ---");
            Value addFn = lean.getMember("lean4j_add_uint32");
            int sum = addFn.execute(21, 21).asInt();
            System.out.println("lean4j_add_uint32(21, 21) = " + sum);
            assert sum == 42 : "Expected 42";

            Value greetFn = lean.getMember("lean4j_greet");
            String greeting = greetFn.execute("Truffle").asString();
            System.out.println("lean4j_greet(\"Truffle\") = " + greeting);
            assert greeting.equals("Hello from Lean 4, Truffle!") : "Unexpected: " + greeting;

            Value fibFn = lean.getMember("lean4j_fib");
            long fib20 = fibFn.execute(20L).asLong();
            System.out.println("lean4j_fib(20) = " + fib20);
            assert fib20 == 6765L : "Expected 6765";

            // ── 3. Call from JavaScript ──
            System.out.println("\n--- JavaScript polyglot calls ---");
            boolean jsAvailable = ctx.getEngine().getLanguages().containsKey("js");
            if (jsAvailable) {
                String jsCode = """
                    const lean = Polyglot.eval('lean4j', '%s');

                    const result1 = lean.lean4j_add_uint32(100, 200);
                    console.log('JS: lean4j_add_uint32(100, 200) = ' + result1);

                    const result2 = lean.lean4j_greet('JavaScript');
                    console.log('JS: lean4j_greet("JavaScript") = ' + result2);

                    const result3 = lean.lean4j_fib(10);
                    console.log('JS: lean4j_fib(10) = ' + result3);

                    result1 + ' | ' + result2;
                    """.formatted(libPath);

                Value jsResult = ctx.eval("js", jsCode);
                System.out.println("JS result: " + jsResult.asString());
            } else {
                System.out.println("(GraalJS not on classpath — add js-language.jar to enable)");
                System.out.println("When installed, this JavaScript code calls Lean 4 directly:");
                System.out.println("  const lean = Polyglot.eval('lean4j', '/path/to/liblean4j.so');");
                System.out.println("  lean.lean4j_add_uint32(100, 200)  // returns 300");
                System.out.println("  lean.lean4j_greet('JavaScript')   // returns 'Hello from Lean 4, JavaScript!'");
            }

            // ── 4. Call from Python (if available) ──
            System.out.println("\n--- Python polyglot calls (if available) ---");
            boolean pythonAvailable = ctx.getEngine().getLanguages().containsKey("python");
            if (pythonAvailable) {
                String pyCode = """
                    import polyglot
                    lean = polyglot.eval(language='lean4j', string='%s')
                    result = lean.lean4j_greet('Python')
                    print('Python: ' + result)
                    result
                    """.formatted(libPath);
                Value pyResult = ctx.eval("python", pyCode);
                System.out.println("Python result: " + pyResult.asString());
            } else {
                System.out.println("(Python not in this GraalVM — install GraalPy to enable)");
            }

            System.out.println("\n=== All polyglot calls succeeded ===");
        }
    }
}
