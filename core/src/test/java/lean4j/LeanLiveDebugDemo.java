package lean4j;

import org.graalvm.polyglot.*;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import com.oracle.truffle.api.debug.*;
import java.net.URI;
public final class LeanLiveDebugDemo {
  public static void main(String[] a) throws Exception {
    try (Context c = Context.newBuilder("lean4j-jit").allowAllAccess(true).allowCreateThread(true).build()) {
      Value m = c.eval("lean4j-jit", System.getProperty("lean4j.ir", System.getProperty("ir")));
      Value api = m.getMember("api");   // drive the library by its documented names
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

      // build a tiny graph and stabilize → should suspend at the breakpoint. Drive the
      // library by its documented names via module.api (the same path as `make polyglot`);
      // the api handles the IO world token and result unwrapping.
      ProxyExecutable mul = (Value... x) -> x[0].asDouble()*x[1].asDouble();
      Value st = api.invokeMember("Leancremental.State.create");
      Value v1 = api.invokeMember("Leancremental.Var.create", st, 3.0);
      Value v2 = api.invokeMember("Leancremental.Var.create", st, 5.0);
      Value prod = api.invokeMember("Leancremental.map2",
          api.invokeMember("Leancremental.Var.watch", v1),
          api.invokeMember("Leancremental.Var.watch", v2), mul);
      api.invokeMember("Leancremental.observe", prod);
      System.out.println("calling stabilize (breakpoint armed on State.stabilize)…");
      api.invokeMember("Leancremental.State.stabilize", st);
      System.out.println("…stabilize returned.");
      session.close();
    }
  }
}
