package lean4j;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import com.oracle.truffle.tools.coverage.CoverageTracker;
import com.oracle.truffle.tools.coverage.SourceCoverage;
import com.oracle.truffle.tools.coverage.RootCoverage;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.source.SourceSection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Source-located code coverage of a Lean library, on UNMODIFIED library code. Points
 * Truffle's coverage instrument at the lean4j-jit interpreter, runs the library's own
 * {@code Tests.Core}, and reports which of the library's functions that suite touched —
 * by {@code .lean} file/line. Because lean4j translates every declaration up front, the
 * denominator is the whole surfaced set, so the coverage number is real (partial), not the
 * vacuous "everything that ran is 100% covered".
 *
 * <p>Run via {@code make coverage}; needs {@code -Dlean.src} + {@code src_ranges.json}, like
 * the debugger.
 */
public final class LeanCoverageDemo {
    public static void main(String[] args) {
        String ir = System.getProperty("lean4j.ir", System.getProperty("ir"));
        try (Context c = Context.newBuilder("lean4j-jit").allowAllAccess(true).allowCreateThread(true).build()) {
            CoverageTracker tracker = c.getEngine().getInstruments().get("coverage").lookup(CoverageTracker.class);
            SourceSectionFilter filter = SourceSectionFilter.newBuilder().tagIs(StandardTags.RootTag.class).build();
            tracker.start(new CoverageTracker.Config(filter, true));  // start before eval → all decls load

            Value m = c.eval("lean4j-jit", ir);
            System.out.println("Running the library's own Tests.Core under coverage…\n");
            m.invokeMember("testCore", 0);
            tracker.end();

            List<RootCoverage> roots = new ArrayList<>();
            for (SourceCoverage sc : tracker.getCoverage()) roots.addAll(Arrays.asList(sc.getRoots()));
            if (roots.isEmpty()) {
                System.err.println("No source-located functions to cover — pass -Dlean.src=<library checkout>");
                System.err.println("and make sure src_ranges.json was exported next to the IR.");
                System.exit(1);
            }

            int total = roots.size(), covered = 0;
            for (RootCoverage rc : roots) if (rc.isCovered()) covered++;
            System.out.printf("coverage: %d / %d library functions  (%.0f%%)%n%n", covered, total, 100.0 * covered / total);

            roots.sort((x, y) -> Long.compare(y.getCount(), x.getCount()));
            System.out.println("most-exercised functions:");
            int n = 0;
            for (RootCoverage rc : roots) if (rc.isCovered()) { System.out.printf("  %8d×  %s%n", rc.getCount(), loc(rc)); if (++n >= 5) break; }
            System.out.println("\nsome functions Tests.Core never reached:");
            n = 0;
            for (RootCoverage rc : roots) if (!rc.isCovered()) { System.out.println("  " + loc(rc)); if (++n >= 5) break; }

            if (!(covered > 0 && covered < total))
                throw new AssertionError("expected 0 < covered < total, got " + covered + "/" + total);
            System.out.println("\n  ✓ source-located coverage on unmodified Lean (" + (total - covered) + " functions untouched)");
        }
    }

    private static String loc(RootCoverage rc) {
        SourceSection s = rc.getSourceSection();
        String where = s != null ? s.getSource().getName() + ":" + s.getStartLine() : "(no src)";
        String name = rc.getName();
        if (name != null && name.startsWith("lean4j-jit:")) name = name.substring("lean4j-jit:".length());
        return String.format("%-46s %s", name, where);
    }
}
