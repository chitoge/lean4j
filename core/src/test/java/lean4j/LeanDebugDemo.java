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
 * {@code leancremental-export/SrcRanges.lean}), so guest stack traces, the CPU profiler,
 * and the debugger all point at real {@code .lean} source — with NO Lean modification.
 *
 * <p>Here a Java lambda throws while the Leancremental engine recomputes a node; the
 * exception unwinds through the library's call stack, which we print with source lines.
 */
public final class LeanDebugDemo {
    static Value est(Value r) { return r.hasArrayElements() ? r.getArrayElement(0) : r; }

    public static void main(String[] args) {
        String ir = System.getProperty("ir", "lean-runtime/leancremental_ir.json");
        ProxyExecutable boom = (Value... x) -> { throw new RuntimeException("boom! (a Java lambda inside the engine)"); };
        try (Context c = Context.newBuilder("lean4j-jit").allowAllAccess(true).allowCreateThread(true).build()) {
            Value m = c.eval("lean4j-jit", ir);
            Value st = est(m.invokeMember("lcState", 0));
            Value v1 = est(m.invokeMember("lcVar", st, 3.0, 0));
            Value v2 = est(m.invokeMember("lcVar", st, 5.0, 0));
            Value area = est(m.invokeMember("lcMap2",
                    est(m.invokeMember("lcWatch", v1, 0)), est(m.invokeMember("lcWatch", v2, 0)), boom, 0));
            est(m.invokeMember("lcObserve", area, 0));
            System.out.println("stabilize → engine recomputes the node → calls the throwing lambda:\n");
            est(m.invokeMember("lcStab", st, 0));
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
