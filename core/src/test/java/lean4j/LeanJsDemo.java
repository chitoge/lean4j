package lean4j;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/**
 * Exercises the JavaScript → Lean polyglot path end to end: from JS, eval the lean4j-jit
 * module, build a Leancremental graph via {@code module.api} (bracket access by documented
 * name) with a JS lambda as the combiner, and assert the result.
 *
 * <p>This guards the guest-language member-call path specifically — {@code api['name'](...)}
 * does a member <em>read</em> then call, which silently broke once when the api was
 * invoke-only. The Java demos (invokeMember) don't cover it; this does. Run via
 * {@code make polyglot-js}.
 */
public final class LeanJsDemo {
    public static void main(String[] args) {
        String ir = System.getProperty("lean4j.ir", System.getProperty("ir"));
        try (Context c = Context.newBuilder("lean4j-jit", "js").allowAllAccess(true).build()) {
            c.getBindings("js").putMember("IR", ir);
            Value r = c.eval("js",
                "(function() {" +
                "  const api = Polyglot.eval('lean4j-jit', IR).api;" +
                "  const st = api['Leancremental.State.create']();" +
                "  const v1 = api['Leancremental.Var.create'](st, 3.0);" +
                "  const v2 = api['Leancremental.Var.create'](st, 5.0);" +
                "  const prod = api['Leancremental.map2'](" +
                "      api['Leancremental.Var.watch'](v1), api['Leancremental.Var.watch'](v2), (a, b) => a * b);" +
                "  const obs = api['Leancremental.observe'](prod);" +
                "  api['Leancremental.State.stabilize'](st);" +
                "  return api['Leancremental.Observer.value!'](obs);" +
                "})()");
            double v = r.asDouble();
            System.out.println("JS → Leancremental.map2(3, 5, (a,b)=>a*b) via module.api  ->  " + v);
            if (v != 15.0) throw new AssertionError("expected 15, got " + v);
            System.out.println("  ✓ JS polyglot path OK");
        }
    }
}
