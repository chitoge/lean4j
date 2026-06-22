package lean4j;

import org.graalvm.polyglot.*;
import org.graalvm.polyglot.proxy.ProxyExecutable;
public final class LeanBindingDemo {
  public static void main(String[] a) {
    try (Context c = Context.newBuilder("lean4j-jit").allowAllAccess(true).allowCreateThread(true).build()) {
      Value m = c.eval("lean4j-jit", System.getProperty("lean4j.ir", System.getProperty("ir")));
      Value api = m.getMember("api");
      System.out.println("module.api exposes (the documented names you'd find in Leancremental's docs):");
      api.getMemberKeys().stream().sorted().forEach(k -> System.out.println("   " + k));
      ProxyExecutable mul = (Value... x) -> x[0].asDouble() * x[1].asDouble();
      System.out.println("\nbuilding the graph with the SAME names the library documents:");
      Value st   = api.invokeMember("Leancremental.State.create");
      Value v1   = api.invokeMember("Leancremental.Var.create", st, 3.0);
      Value v2   = api.invokeMember("Leancremental.Var.create", st, 5.0);
      Value w1   = api.invokeMember("Leancremental.Var.watch", v1);
      Value w2   = api.invokeMember("Leancremental.Var.watch", v2);
      Value prod = api.invokeMember("Leancremental.map2", w1, w2, mul);
      Value obs  = api.invokeMember("Leancremental.observe", prod);
      api.invokeMember("Leancremental.State.stabilize", st);
      double v   = api.invokeMember("Leancremental.Observer.value!", obs).asDouble();
      System.out.printf("\n   3 × 5 = %.0f   %s%n", v, v == 15.0 ? "✓" : "✗");
    }
  }
}
