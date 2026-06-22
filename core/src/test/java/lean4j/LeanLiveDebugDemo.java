package lean4j;

import org.graalvm.polyglot.*;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import com.oracle.truffle.api.debug.*;
import java.net.URI;
public final class LeanLiveDebugDemo {
  static Value est(Value r){ return r.hasArrayElements()? r.getArrayElement(0): r; }
  public static void main(String[] a) throws Exception {
    try (Context c = Context.newBuilder("lean4j-jit").allowAllAccess(true).allowCreateThread(true).build()) {
      Value m = c.eval("lean4j-jit", System.getProperty("lean4j.ir", System.getProperty("ir")));
      Debugger dbg = Debugger.find(c.getEngine());
      DebuggerSession session = dbg.startSession(event -> {
        System.out.println("\n⏸  BREAKPOINT hit — live Lean call stack:");
        DebugStackFrame top = event.getTopStackFrame();
        var ss = top.getSourceSection();
        System.out.printf("     %-52s %s%n", top.getName(),
            ss != null ? ss.getSource().getName()+":"+ss.getStartLine() : "");
        DebugScope scope = top.getScope();
        if (scope != null) {
          System.out.println("     live frame variables:");
          int n=0;
          for (DebugValue v : scope.getDeclaredValues()) {
            String disp = v.toDisplayString();
            if (disp.length()>60) disp = disp.substring(0,57)+"...";
            System.out.printf("        %-6s = %s%n", v.getName(), disp);
            if (++n>=6) { System.out.println("        …"); break; }
          }
        }
        event.prepareContinue();
      });
      // breakpoint on Leancremental.State.stabilize (State.lean line 515)
      URI uri = java.nio.file.Path.of("" + System.getProperty("lean.src", "Leancremental") + "/Leancremental/Core/State.lean").toUri();
      session.install(Breakpoint.newBuilder(uri).lineIs(515).build());

      // build a tiny graph and stabilize → should suspend at the breakpoint
      ProxyExecutable mul = (Value... x) -> x[0].asDouble()*x[1].asDouble();
      Value st = est(m.invokeMember("lcState", 0));
      Value v1 = est(m.invokeMember("lcVar", st, 3.0, 0));
      Value v2 = est(m.invokeMember("lcVar", st, 5.0, 0));
      Value prod = est(m.invokeMember("lcMap2", est(m.invokeMember("lcWatch",v1,0)), est(m.invokeMember("lcWatch",v2,0)), mul, 0));
      est(m.invokeMember("lcObserve", prod, 0));
      System.out.println("calling stabilize (breakpoint armed on State.stabilize)…");
      est(m.invokeMember("lcStab", st, 0));
      System.out.println("…stabilize returned.");
      session.close();
    }
  }
}
