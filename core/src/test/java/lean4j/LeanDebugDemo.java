package lean4j;

import org.graalvm.polyglot.*;
import org.graalvm.polyglot.proxy.ProxyExecutable;

/**
 * Debugging UNMODIFIED Lean libraries from the JVM — something native AOT Lean can't
 * offer (compiled to C, you'd be in gdb on lean_object* refcounting).
 *
 * <p>Run with the source root pointing at the library checkout, e.g.
 * {@code -Dir=lean-runtime/leancremental_ir.json -Dlean.src=/path/to/Leancremental}.
 * The interpreter attaches Truffle SourceSections to every RootNode from
 * {@code src_ranges.json} (declaration ranges Lean serializes into the olean — see
 * {@code examples/leancremental/SrcRanges.lean}), so guest stack traces, the CPU profiler,
 * and the debugger all point at real {@code .lean} source — with NO Lean modification.
 *
 * <p>Here a Java lambda throws while the Leancremental engine recomputes a node; the
 * exception unwinds through the library's call stack, which we print with source lines.
 */
public final class LeanDebugDemo {
    public static void main(String[] args) {
        String ir = System.getProperty("lean4j.ir", System.getProperty("ir", "lean-runtime/leancremental_ir.json"));
        ProxyExecutable boom = (Value... x) -> { throw new RuntimeException("boom! (a Java lambda inside the engine)"); };
        try (Context c = Context.newBuilder("lean4j-jit").allowAllAccess(true).allowCreateThread(true).build()) {
            Value m = c.eval("lean4j-jit", ir);
            // drive the library by its documented names via module.api (the same path as the
            // polyglot/debug demos); the api handles the IO world token and unwrapping.
            Value api = m.getMember("api");
            Value st = api.invokeMember("Leancremental.State.create");
            Value v1 = api.invokeMember("Leancremental.Var.create", st, 3.0);
            Value v2 = api.invokeMember("Leancremental.Var.create", st, 5.0);
            Value area = api.invokeMember("Leancremental.map2",
                    api.invokeMember("Leancremental.Var.watch", v1),
                    api.invokeMember("Leancremental.Var.watch", v2), boom);
            api.invokeMember("Leancremental.observe", area);
            System.out.println("stabilize → engine recomputes the node → calls the throwing lambda:\n");
            api.invokeMember("Leancremental.State.stabilize", st);
        } catch (PolyglotException e) {
            System.out.println("guest error: " + e.getMessage() + "\n");
            System.out.println("Lean stack trace through UNMODIFIED Leancremental, source-located:");
            int located = 0;
            for (PolyglotException.StackFrame f : e.getPolyglotStackTrace()) {
                if (!f.isGuestFrame()) continue;
                SourceSection s = f.getSourceLocation();
                if (s != null) { located++;
                    System.out.printf("   %-56s → %s:%d%n", f.getRootName(), s.getSource().getName(), s.getStartLine()); }
            }
            System.out.println("\n   " + located + " library frames mapped to real .lean source lines"
                    + (located == 0 ? "  (pass -Dlean.src=<checkout>)" : ""));
        }
    }
}
