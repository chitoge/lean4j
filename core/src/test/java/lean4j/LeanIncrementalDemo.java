package lean4j;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;

/**
 * The polyglot payoff, in one screen: a tiny reactive spreadsheet whose <em>cells and
 * incremental engine live in Lean</em> (the Leancremental library, unmodified) but whose
 * <em>formulas are plain Java lambdas</em>. Each lambda prints when it fires, so you can
 * watch Lean re-run only the formulas whose inputs actually changed — the whole graph kept
 * consistent with the minimum work, driven entirely from the JVM. Run via {@code make incremental}.
 */
public final class LeanIncrementalDemo {
    public static void main(String[] args) {
        try (Context c = Context.newBuilder("lean4j-jit").allowAllAccess(true).build()) {
            Value m = c.eval("lean4j-jit", System.getProperty("lean4j.ir", System.getProperty("ir")));
            Value api = m.getMember("api");   // the library, by its documented names

            // The formulas are Java. Each announces itself when Lean chooses to run it.
            ProxyExecutable area  = (Value... x) -> { System.out.println("     ƒ area = w·h"); return x[0].asDouble() * x[1].asDouble(); };
            ProxyExecutable perim = (Value... x) -> { System.out.println("     ƒ perimeter = 2·(w+h)"); return 2 * (x[0].asDouble() + x[1].asDouble()); };
            ProxyExecutable cost  = (Value... x) -> { System.out.println("     ƒ cost = area·price"); return x[0].asDouble() * x[1].asDouble(); };

            // Cells (Vars) and the dependency graph (map2/observe) are Lean's.
            Value st     = api.invokeMember("Leancremental.State.create");
            Value width  = api.invokeMember("Leancremental.Var.create", st, 4.0);
            Value height = api.invokeMember("Leancremental.Var.create", st, 3.0);
            Value price  = api.invokeMember("Leancremental.Var.create", st, 10.0);

            Value w = api.invokeMember("Leancremental.Var.watch", width);
            Value h = api.invokeMember("Leancremental.Var.watch", height);
            Value p = api.invokeMember("Leancremental.Var.watch", price);

            Value areaI  = api.invokeMember("Leancremental.map2", w, h, area);
            Value perimI = api.invokeMember("Leancremental.map2", w, h, perim);
            Value costI  = api.invokeMember("Leancremental.map2", areaI, p, cost);

            Value obsCost  = api.invokeMember("Leancremental.observe", costI);
            Value obsPerim = api.invokeMember("Leancremental.observe", perimI);

            System.out.println("Leancremental as a reactive spreadsheet — cells & engine in Lean,");
            System.out.println("the three formulas in Java. Watch which ones re-run.\n");
            System.out.println("cells:  width = 4   height = 3   price = 10");

            System.out.println("\n▸ first stabilize — everything computes once");
            stabilize(api, st);
            report(api, obsCost, obsPerim);

            System.out.println("\n▸ price 10 → 12 — only `cost` reads price");
            api.invokeMember("Leancremental.Var.set", price, 12.0);
            stabilize(api, st);
            report(api, obsCost, obsPerim);
            System.out.println("     (area & perimeter were not touched)");

            System.out.println("\n▸ width 4 → 5 — width feeds all three");
            api.invokeMember("Leancremental.Var.set", width, 5.0);
            stabilize(api, st);
            report(api, obsCost, obsPerim);

            System.out.println("\nYou wrote three Java lambdas; Lean ran each only when its inputs actually");
            System.out.println("changed. The graph stays consistent with the minimum work — no hand-written");
            System.out.println("Lean, no manual recompute, all driven from the JVM.");
        }
    }

    static void stabilize(Value api, Value st) {
        api.invokeMember("Leancremental.State.stabilize", st);
    }

    static void report(Value api, Value cost, Value perim) {
        System.out.printf("   → cost = %.1f   perimeter = %.1f%n",
                api.invokeMember("Leancremental.Observer.value!", cost).asDouble(),
                api.invokeMember("Leancremental.Observer.value!", perim).asDouble());
    }
}
