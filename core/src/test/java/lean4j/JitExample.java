package lean4j;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/**
 * Demo: Lean 4 IR compiled to native code via PE-friendly Truffle nodes.
 *
 * Same functions as LirExample, but the "lean4j-jit" language builds real
 * Truffle AST nodes, so on the optimizing runtime hot functions JIT-compile.
 */
public class JitExample {

    public static void main(String[] args) throws Exception {
        String irPath = System.getProperty("lean4j.ir", "lean-runtime/lean4j_ir.json");

        System.out.println("=== Lean 4 IR — JIT-compiled Truffle nodes ===\n");
        System.out.println("Loading IR from: " + irPath);

        try (Context ctx = Context.newBuilder().allowAllAccess(true).build()) {
            Value mod = ctx.eval("lean4j-jit", irPath);
            System.out.println("Loaded: " + mod);
            System.out.println("Functions: " + mod.getMemberKeys() + "\n");

            System.out.println("--- UInt32 arithmetic ---");
            int sum = mod.invokeMember("addUInt32", 21, 21).asInt();
            System.out.println("addUInt32(21, 21) = " + sum);
            assert sum == 42 : sum;
            int product = mod.invokeMember("mulUInt32", 6, 7).asInt();
            System.out.println("mulUInt32(6, 7) = " + product);
            assert product == 42 : product;

            System.out.println("\n--- String functions ---");
            String greeting = mod.invokeMember("greet", "World").asString();
            System.out.println("greet(\"World\") = " + greeting);
            assert "Hello from Lean 4, World!".equals(greeting) : greeting;

            System.out.println("\n--- Fibonacci ---");
            long fib10 = mod.invokeMember("fib", 10L).asLong();
            System.out.println("fib(10) = " + fib10);
            assert fib10 == 55L : fib10;
            long fib30 = mod.invokeMember("fib", 30L).asLong();
            System.out.println("fib(30) = " + fib30);
            assert fib30 == 832040L : fib30;

            System.out.println("\n--- JavaScript calling JIT-compiled Lean ---");
            if (ctx.getEngine().getLanguages().containsKey("js")) {
                String js = """
                    const mod = Polyglot.eval('lean4j-jit', '%s');
                    const r = mod.greet('JavaScript') + ' / fib(20)=' + mod.fib(20);
                    r;
                    """.formatted(irPath);
                System.out.println("JS: " + ctx.eval("js", js).asString());
            }

            System.out.println("\n=== JIT example passed ===");
        }
    }
}
