package lean4j;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/**
 * Exercises the Python → Lean polyglot path end to end: from GraalPy, eval the lean4j-jit
 * module, build a Leancremental graph via {@code module.api} ({@code getattr(api, "name")}, a
 * member read-then-call) with a Python lambda as the combiner, and assert the result.
 *
 * <p>The companion to {@link LeanJsDemo}: the JS path uses bracket access, Python uses
 * {@code getattr}, but both hit the same {@code readMember → execute} interop. Run via
 * {@code make polyglot-py} (needs GraalPy on the classpath, which the flake vendors).
 */
public final class LeanPyDemo {
    public static void main(String[] args) {
        String ir = System.getProperty("lean4j.ir", System.getProperty("ir"));
        try (Context c = Context.newBuilder("lean4j-jit", "python").allowAllAccess(true).build()) {
            c.getBindings("python").putMember("IR", ir);
            Value r = c.eval("python", String.join("\n",
                "import polyglot",
                "api = polyglot.eval(language='lean4j-jit', string=IR).api",
                "st = getattr(api, 'Leancremental.State.create')()",
                "v1 = getattr(api, 'Leancremental.Var.create')(st, 3.0)",
                "v2 = getattr(api, 'Leancremental.Var.create')(st, 5.0)",
                "watch = getattr(api, 'Leancremental.Var.watch')",
                "prod = getattr(api, 'Leancremental.map2')(watch(v1), watch(v2), lambda x, y: x * y)",
                "obs = getattr(api, 'Leancremental.observe')(prod)",
                "getattr(api, 'Leancremental.State.stabilize')(st)",
                "getattr(api, 'Leancremental.Observer.value!')(obs)"));
            double v = r.asDouble();
            System.out.println("Python → Leancremental.map2(3, 5, lambda x,y: x*y) via module.api  ->  " + v);
            if (v != 15.0) throw new AssertionError("expected 15, got " + v);
            System.out.println("  ✓ Python polyglot path OK");
        }
    }
}
