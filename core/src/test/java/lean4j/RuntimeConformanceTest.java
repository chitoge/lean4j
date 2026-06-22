package lean4j;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/**
 * The lean4j-jit runtime conformance suite: runs real Leancremental library code on the
 * interpreter and asserts every result matches native Lean. Leancremental is the test
 * corpus, not the subject — this checks the *interpreter*. Run via {@code make leancremental}.
 *
 * It calls host-constructible {@code @[export]} shims that exercise, roughly in order: the
 * pure model (pap/app closures, Array.foldl via the Id monad, ctor/proj/case, Nat),
 * join-point control flow, copy-on-shared array soundness, the full incremental engine
 * (State/Var/map/map2/observe/stabilize), primitive fidelity (String.compare/hash, Float,
 * bignum Int/Nat), IO + filesystem + subprocess + UV syscalls, real-threaded concurrency,
 * and finally the library's own 11-module suite ({@code Tests.lean}) — all interpreted from
 * the exported IR, with the opaque externs (Array.push, Nat.add, ...) implemented in Java.
 */
public class RuntimeConformanceTest {

    public static void main(String[] args) throws Exception {
        String irPath = System.getProperty("lean4j.ir", "lean-runtime/leancremental_ir.json");

        System.out.println("=== Leancremental on the lean4j-jit runtime ===\n");
        System.out.println("Loading IR from: " + irPath);

        try (Context ctx = Context.newBuilder("lean4j-jit").allowAllAccess(true)
                .allowCreateThread(true) // Lean Tasks spawn polyglot worker threads
                .in(new java.io.ByteArrayInputStream("alpha\nbeta\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .build()) {
            Value mod = ctx.eval("lean4j-jit", irPath);
            System.out.println("Loaded: " + mod);

            // Pure.Expr.sumNat #[const 1, const 2, const 3] |>.eval  ==  6
            long sum = mod.invokeMember("testSumNat", new Object[]{}).asLong();
            System.out.println("test_sumnat (sumNat [1,2,3].eval) = " + sum);
            assert sum == 6 : "expected 6, got " + sum;

            // ((const 7).map (· + 5)).eval  ==  12
            long mapped = mod.invokeMember("testEvalMap", new Object[]{}).asLong();
            System.out.println("test_evalmap ((const 7).map (+5)).eval = " + mapped);
            assert mapped == 12 : "expected 12, got " + mapped;

            // for i in [0:5] do s += i  ==  0+1+2+3+4 = 10  (exercises jmp/jdecl join points)
            long loop = mod.invokeMember("testLoop", new Object[]{}).asLong();
            System.out.println("test_loop (sum 0..4 via for-loop) = " + loop);
            assert loop == 10 : "expected 10, got " + loop;

            // converging-if with a large shared tail → join points (jmp/jdecl); n=5 → 71490
            long join = mod.invokeMember("testJoin", 5).asLong();
            System.out.println("test_join (join-point control flow, n=5) = " + join);
            assert join == 71490 : "expected 71490, got " + join;

            // IO workload: mutable ref + loop (real effects on the JIT runtime).
            // Result is EST.Out.ok(value); read field 0 via interop array access.
            Value ioRes = mod.invokeMember("testIORef", 0);
            long ioVal = ioRes.hasArrayElements() ? ioRes.getArrayElement(0).asLong() : ioRes.asLong();
            System.out.println("test_ioref (mkRef; for i in 0..4 modify (+i); get) = " + ioVal
                               + "  [returned " + ioRes + "]");
            assert ioVal == 10 : "expected 10, got " + ioVal;

            // SOUNDNESS: b aliases a, then a.set!(0,99) — a shared array must be copied,
            // not mutated in place. Sound = 99+10 = 109; old "always mutate" = 198.
            long shared = mod.invokeMember("testShare", 10).asLong();
            System.out.println("test_share (alias + set! on shared array) = " + shared
                               + (shared == 109 ? "  ✓ sound (copy-on-shared)" : "  ✗ UNSOUND"));
            assert shared == 109 : "expected 109 (sound), got " + shared;

            // THE REAL ENGINE: State/Var/map/map2/observe/stabilize — the full
            // incremental computation engine (thousands of IR fdecls, module-init globals,
            // mutable graph, FBIP cell reuse), correct on the JIT.
            long incr1 = est(mod.invokeMember("testIncr1", 0));
            System.out.println("test_incr1 (var 42 → observe → stabilize) = " + incr1);
            assert incr1 == 42 : "expected 42, got " + incr1;

            long incr2 = est(mod.invokeMember("testIncr2", 0));
            System.out.println("test_incr2 (map (·+100) over var 5) = " + incr2);
            assert incr2 == 105 : "expected 105, got " + incr2;

            long incr = est(mod.invokeMember("testIncr", 0));
            System.out.println("test_incr (map2 13+17 → set x:=19 → 2× stabilize) = " + incr);
            assert incr == 36 : "expected 36, got " + incr;

            // Broader engine operations.
            long bind = est(mod.invokeMember("testBind", 0));
            System.out.println("test_bind (dynamic graph via bind; set 7) = " + bind);
            assert bind == 77 : "expected 77, got " + bind;

            long map3 = est(mod.invokeMember("testMap3", 0));
            System.out.println("test_map3 (map3 over 3 vars) = " + map3);
            assert map3 == 321 : "expected 321, got " + map3;

            long seq = est(mod.invokeMember("testSeq", 0));
            System.out.println("test_seq (4× set+stabilize, accumulate) = " + seq);
            assert seq == 14 : "expected 14, got " + seq;

            // PRIMITIVE FIDELITY — regression tests the full suite does NOT exercise.
            // String.compare: Lean Ordering is lt=0,eq=1,gt=2 (was reversed).
            assertCmp(mod, "a", "b", 0);
            assertCmp(mod, "b", "a", 2);
            assertCmp(mod, "a", "a", 1);
            assertCmp(mod, "ab", "abc", 0);
            assertCmp(mod, "abc", "ab", 2);
            System.out.println("test_strcmp (String.compare Ordering lt=0/eq=1/gt=2) ✓");

            // Float: 3.14*2 + 1 = 7.28; *100 → toUInt64 = 728 (real doubles via ofScientific).
            long fl = mod.invokeMember("testFloat", new Object[]{}).asLong();
            System.out.println("test_float (3.14*2+1)*100 → toUInt64 = " + fl);
            assert fl == 728 : "expected 728, got " + fl;

            // Bignum: 2^100 >>> 70 (=2^30) + 2^100 % 1e9+7 + |(-5)*7| → matches native lean.
            long bn = mod.invokeMember("testBignum", new Object[]{}).asLong();
            System.out.println("test_bignum (2^100 shift/mod + Int natAbs) = " + bn);
            assert bn == 2050113144L : "expected 2050113144, got " + bn;

            // String.hash: exact lean_string_hash (MurmurHash64A, seed 11) vs native lean.
            assertHash(mod, "abc", "13471000911841882655");
            assertHash(mod, "abcdefgh", "4343876127666658534");
            assertHash(mod, "hello world", "1766380345486652199");
            assertHash(mod, "shared:key", "3324907111532598986");
            System.out.println("test_strhash (exact lean_string_hash) ✓");

            // Concurrency stress on REAL threads: 64-way heavy fan-out/fan-in + a
            // Promise round-trip + a Task.map chain. Matches native lean (6237967).
            long conc = est(mod.invokeMember("testConcurrency", 0));
            System.out.println("test_concurrency (64-task fan-out + Promise + Task.map) = " + conc);
            assert conc == 6237967L : "expected 6237967, got " + conc;

            // String/Substring UTF-8 internals (drop/dropRight/front/takeWhile/toString
            // via splitOn+trim), Float trig, and Lean.Name structural equality — vs native.
            Value sv = mod.invokeMember("testStrops", 0);
            String strops = sv.hasArrayElements() ? sv.getArrayElement(0).asString() : sv.asString();
            String expected = "lo, wörld! 123|héllo, wörld!|h|wörld|héllo,-wörld!-123|trim me";
            assert strops.equals(expected) : "strops: expected [" + expected + "], got [" + strops + "]";
            long trig = est(mod.invokeMember("testTrig", 0));
            assert trig == 1429473587L : "expected trig 1429473587, got " + trig;
            long namebeq = est(mod.invokeMember("testNameBeq", 0));
            assert namebeq == 101L : "expected namebeq 101, got " + namebeq;
            System.out.println("test_strops/trig/namebeq (String/Substring internals, Float trig, Name.beq) ✓");

            // Capstone: Lean's OWN JSON parser + serializer (Lean.Json.parse → compress)
            // running on the interpreter — exercises the whole pure stack (parser, Format,
            // RBNode-sorted objects, Float, string escaping). Bit-exact vs native.
            Value jv = mod.invokeMember("testJson", 0);
            String json = jv.hasArrayElements() ? jv.getArrayElement(0).asString() : jv.asString();
            String jexp = "{\"a\":\"x\\ny\",\"b\":[1,2.5,true,null],\"n\":42}";
            assert json.equals(jexp) : "json: expected [" + jexp + "], got [" + json + "]";
            System.out.println("test_json (Lean.Json.parse → compress, full pure stack) ✓");

            // IO/effects: file Handle round-trip (mk/putStr/flush/getLine/readToEnd),
            // getEnv (some+none), currentDir, realPath, getRandomBytes, monoMsNow. vs native.
            Value iv = mod.invokeMember("testIO", 0);
            String io = iv.hasArrayElements() ? iv.getArrayElement(0).asString() : iv.asString();
            String ioExp = "héllo line|second|true|true|true|true|16|true";
            assert io.equals(ioExp) : "io: expected [" + ioExp + "], got [" + io + "]";
            System.out.println("test_io (file Handle I/O + getEnv + clock + random) ✓");

            // Subprocess: Process.run/output (two pipes + asTask stdout on real threads) +
            // spawn/takeStdin/wait with a piped-stdin handoff to `head -c 4`. vs native.
            Value pv = mod.invokeMember("testSubproc", 0);
            String sub = pv.hasArrayElements() ? pv.getArrayElement(0).asString() : pv.asString();
            String subExp = "hello world|3|out|err|abcd";
            assert sub.equals(subExp) : "subproc: expected [" + subExp + "], got [" + sub + "]";
            // stdin streams: getStdin.getLine reads the Context input set above.
            Value tv = mod.invokeMember("testStdin", 0);
            String stdin = tv.hasArrayElements() ? tv.getArrayElement(0).asString() : tv.asString();
            assert stdin.equals("alpha+beta") : "stdin: expected [alpha+beta], got [" + stdin + "]";
            System.out.println("test_subproc/stdin (Process spawn/run/output/wait + stdin streams) ✓");

            // Std.Internal.UV.System synchronous syscalls (cwd/homedir/pid/hostname/uname/
            // hrtime/environ) — invariants vs native (uname.sysname=="Linux", rest non-empty).
            Value uvv = mod.invokeMember("testUvSys", 0);
            String uv = uvv.hasArrayElements() ? uvv.getArrayElement(0).asString() : uvv.asString();
            assert uv.equals("true|true|true|true|true|Linux|true|true") : "uvsys: got [" + uv + "]";
            System.out.println("test_uvsys (UV.System syscalls: cwd/pid/uname/hrtime/environ) ✓");

            // IO.println — host-backed stdout stream (writes appear below):
            System.out.println("--- testPrint output ---");
            mod.invokeMember("testPrint", 0);
            System.out.println("--- end testPrint ---");

            // RUN THE REAL TEST MODULE: Tests.Core.runAll (~29 tests covering maps,
            // cutoffs, bind/branch, fold, freeze, observability, invariants, cycle
            // diagnostics, stale/recompute, and a JSON serialize/parse round-trip).
            Value core = mod.invokeMember("testCore", 0);
            boolean coreOk = core.toString().contains("ok");
            System.out.println("test_core (Tests.Core.runAll, ~29 tests) → " + core
                + (coreOk ? "  ✓ ALL CORE TESTS PASSED" : "  ✗ FAILED"));
            assert coreOk : "Tests.Core.runAll did not return ok: " + core;


            // THE WHOLE SUITE: all 11 Tests.* modules (Tests.lean main verbatim,
            // including the disk-backed snapshot tests). Success = EST.Out.ok(unit)
            // (its own "leancremental tests passed" line prints just above).
            System.out.println("--- Tests.lean main output ---");
            Value all = mod.invokeMember("testAll", 0);
            boolean allOk = all.toString().equals("EST.Out.ok(PUnit.unit)");
            System.out.println("test_all (full suite, all 11 modules) → " + all
                + (allOk ? "  ✓ FULL SUITE PASSED" : "  ✗ FAILED"));
            assert allOk : "Tests.lean main did not complete cleanly: " + all;

            System.out.println("\n=== The real Leancremental engine + Tests.Core run on the JIT runtime ===");
        }
    }

    /** Unwrap an IO result: EST.Out.ok(value) → value, read via interop. */
    private static long est(Value r) {
        return r.hasArrayElements() ? r.getArrayElement(0).asLong() : r.asLong();
    }

    private static void assertHash(Value mod, String str, String expectUnsigned) {
        String got = Long.toUnsignedString(mod.invokeMember("testStrHash", str).asLong());
        assert got.equals(expectUnsigned) : "hash(\"" + str + "\") = " + got + ", expected " + expectUnsigned;
    }

    private static void assertCmp(Value mod, String x, String y, int expect) {
        long got = mod.invokeMember("testStrCmp", x, y).asLong();
        assert got == expect : "compare(\"" + x + "\",\"" + y + "\") = " + got + ", expected " + expect;
    }
}
