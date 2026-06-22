package lean4j.jit;

import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps Lean function names to their real {@code .lean} source location, so RootNodes
 * carry {@link SourceSection}s — which makes stack traces, the CPU sampler, and the
 * debugger all point at actual Lean source. The data comes from
 * {@code src_ranges.json} (a sidecar the exporter produces from Lean's declaration
 * ranges — serialized in the olean, recoverable for UNMODIFIED library code). The
 * {@code .lean} files are resolved under the {@code -Dlean.src=<root>} directory.
 */
final class LeanSourceMap {

    private record Range(String mod, int line, int endLine) {}
    private static final Pattern ENTRY = Pattern.compile(
            "\"([^\"]+)\":\\{\"mod\":\"([^\"]+)\",\"line\":(\\d+),\"endLine\":(\\d+)\\}");

    private final Map<String, Range> byName = new HashMap<>();
    private final Map<String, Source> sourceByMod = new HashMap<>();
    private final String srcRoot;

    LeanSourceMap(Path irPath) {
        this.srcRoot = System.getProperty("lean.src", "");
        Path sidecar = irPath.resolveSibling("src_ranges.json");
        if (!srcRoot.isEmpty() && Files.exists(sidecar)) {
            try {
                Matcher m = ENTRY.matcher(Files.readString(sidecar));
                while (m.find()) {
                    byName.put(m.group(1),
                            new Range(m.group(2), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))));
                }
            } catch (Exception ignored) { }
        }
    }

    boolean isEmpty() { return byName.isEmpty(); }

    /**
     * True only for functions with their OWN source range (not an origin-inherited one).
     * Instrumentation (debugger breakpoints) is limited to these — the real, user-named
     * functions — never the compiler-generated specializations/closures, whose return
     * values can be internal non-interop types and which you'd never breakpoint on anyway.
     */
    boolean hasOwnRange(String name) { return byName.containsKey(name); }

    // Compiler-generated decls (._redArg / ._lam_N / .spec_N / ._closed_N / _private…)
    // carry no range of their own — inherit their ORIGIN declaration's range.
    private static final Pattern MANGLE_SUFFIX = Pattern.compile(
            "(\\._redArg|\\._lam_\\d+|\\._boxed|\\._closed_\\d+|\\.spec_\\d+|\\._cstage\\d+|\\._elambda_\\d+|\\._at_\\..*)$");
    private static final Pattern PRIVATE_PREFIX = Pattern.compile("^_private\\.[^.]+(\\.[^.]+)*\\.\\d+\\.");
    private final Map<String, Range> resolved = new HashMap<>();

    private Range rangeOf(String name) {
        Range r = byName.get(name);
        if (r != null) return r;
        if (resolved.containsKey(name)) return resolved.get(name);
        String n = name, prev = null;
        Range found = null;
        while (!n.equals(prev)) {
            prev = n;
            Range hit = byName.get(n);
            if (hit != null) { found = hit; break; }
            Matcher m = MANGLE_SUFFIX.matcher(n);
            if (m.find()) { n = n.substring(0, m.start()); continue; }
            String np = PRIVATE_PREFIX.matcher(n).replaceFirst("");
            if (!np.equals(n)) { n = np; continue; }
            int dot = n.lastIndexOf('.');           // last resort: drop the trailing segment
            if (dot > 0) { n = n.substring(0, dot); continue; }
            break;
        }
        resolved.put(name, found);
        return found;
    }

    /** The source section spanning a Lean function's definition (or its origin), or null. */
    SourceSection sectionFor(String name) {
        Range r = rangeOf(name);
        if (r == null) return null;
        Source s = sourceByMod.computeIfAbsent(r.mod, this::buildSource);
        if (s == null) return null;
        try {
            int end = Math.min(r.endLine, s.getLineCount());
            int line = Math.min(Math.max(r.line, 1), s.getLineCount());
            return s.createSection(line, 1, end, s.getLineLength(end) + 1);
        } catch (Exception e) {
            try { return s.createSection(Math.min(r.line, s.getLineCount())); } catch (Exception e2) { return null; }
        }
    }

    private Source buildSource(String mod) {
        Path f = Path.of(srcRoot, mod.replace('.', '/') + ".lean");
        if (!Files.exists(f)) return null;
        try {
            return Source.newBuilder("lean4j-jit", Files.readString(f), f.getFileName().toString())
                    .uri(f.toUri()).build();
        } catch (Exception e) { return null; }
    }
}
