package lean4j;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import com.oracle.truffle.tools.profiler.CPUSampler;
import com.oracle.truffle.tools.profiler.CPUSamplerData;
import com.oracle.truffle.tools.profiler.ProfilerNode;
import com.oracle.truffle.api.source.SourceSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Source-located CPU profile of a Lean library, on UNMODIFIED library code. Runs a
 * stabilize-heavy Leancremental workload — a deep chain of {@code map} nodes re-evaluated
 * after thousands of input changes — under Truffle's CPU sampler, then reports the hot Lean
 * functions by {@code .lean} file/line. The combiners are trivial Java lambdas on purpose,
 * so the time lands in the <em>incremental engine</em> (stabilize/recompute), which is what
 * you'd want to see.
 *
 * <p>Run via {@code make profile}; needs {@code -Dlean.src} + {@code src_ranges.json}.
 */
public final class LeanProfileDemo {
    public static void main(String[] args) {
        String ir = System.getProperty("lean4j.ir", System.getProperty("ir"));
        try (Context c = Context.newBuilder("lean4j-jit").allowAllAccess(true).allowCreateThread(true).build()) {
            Value m = c.eval("lean4j-jit", ir);
            Value api = m.getMember("api");

            Value st = api.invokeMember("Leancremental.State.create");
            Value x = api.invokeMember("Leancremental.Var.create", st, 1.0);
            Value cur = api.invokeMember("Leancremental.Var.watch", x);
            ProxyExecutable inc = (Value... v) -> v[0].asDouble() + 1.0;
            for (int i = 0; i < 20; i++) cur = api.invokeMember("Leancremental.map", cur, inc);
            api.invokeMember("Leancremental.observe", cur);

            CPUSampler sampler = CPUSampler.find(c.getEngine());
            sampler.setCollecting(true);
            System.out.println("Profiling 5000 stabilizes of a 20-deep map chain…\n");
            for (int r = 0; r < 5000; r++) {
                api.invokeMember("Leancremental.Var.set", x, (double) r);
                api.invokeMember("Leancremental.State.stabilize", st);
            }
            sampler.setCollecting(false);

            // Aggregate self-samples per function across the sampled call tree.
            Map<String, long[]> selfHits = new HashMap<>();
            Map<String, String> locOf = new HashMap<>();
            long samples = 0;
            for (CPUSamplerData d : sampler.getDataList()) {
                samples += d.getSamples();
                for (Collection<ProfilerNode<CPUSampler.Payload>> roots : d.getThreadData().values())
                    for (ProfilerNode<CPUSampler.Payload> root : roots) walk(root, selfHits, locOf);
            }
            if (samples == 0) {
                System.err.println("No samples recorded — the workload was too short to sample.");
                System.exit(1);
            }

            List<Map.Entry<String, long[]>> top = new ArrayList<>(selfHits.entrySet());
            top.sort((p, q) -> Long.compare(q.getValue()[0], p.getValue()[0]));
            System.out.println("total samples: " + samples + "\nhot Lean functions (self-samples · function · source):");
            boolean sawSrc = false;
            int n = 0;
            for (Map.Entry<String, long[]> e : top) {
                String loc = locOf.getOrDefault(e.getKey(), "(no src)");
                if (loc.contains(".lean:")) sawSrc = true;
                System.out.printf("  %6d  %-44s %s%n", e.getValue()[0], strip(e.getKey()), loc);
                if (++n >= 8) break;
            }
            if (!sawSrc) {
                System.err.println("\nNo .lean locations in the profile — pass -Dlean.src=<library checkout>");
                System.err.println("and make sure src_ranges.json was exported next to the IR.");
                System.exit(1);
            }
            System.out.println("\n  ✓ source-located CPU profile on unmodified Lean");
        }
    }

    private static void walk(ProfilerNode<CPUSampler.Payload> node, Map<String, long[]> selfHits, Map<String, String> locOf) {
        String name = node.getRootName();
        if (name != null) {
            selfHits.computeIfAbsent(name, k -> new long[1])[0] += node.getPayload().getSelfHitCount();
            SourceSection s = node.getSourceSection();
            if (s != null) locOf.putIfAbsent(name, s.getSource().getName() + ":" + s.getStartLine());
        }
        for (ProfilerNode<CPUSampler.Payload> ch : node.getChildren()) walk(ch, selfHits, locOf);
    }

    private static String strip(String n) {
        return n != null && n.startsWith("lean4j-jit:") ? n.substring("lean4j-jit:".length()) : n;
    }
}
