-- Transitive-closure Lean IR exporter for the lean4j JIT runtime.
-- Imports Leancremental, defines host-constructible @[export] shims, then walks
-- from those roots through every fap/pap target, emitting fdecl bodies as JSON
-- and recording opaque externs separately.
import Leancremental
import Tests.Core
import Tests.Query
import Tests.Pure
import Tests.TutorialExamples
import Tests.Actions
import Tests.ConceptsExamples
import Tests.CookbookExamples
import Tests.Parallel
import Tests.ConcurrencyExamples
import Tests.QueriesExamples
import Tests.FederationExamples
import Lean
import Lean.Compiler.IR
import Std.Internal.UV
import Lean4JExport
open Lean Compiler IR

-- ─────────────── host-constructible shims (roots) ───────────────

open Leancremental in
@[export test_sumnat]
def testSumNat : UInt32 :=
  ((Pure.Expr.sumNat #[.const 1, .const 2, .const 3]).eval).toUInt32

open Leancremental in
@[export test_evalmap]
def testEvalMap : UInt32 :=
  (((Pure.Expr.const (7 : Nat)).map (· + 5)).eval).toUInt32

-- Exercises join points (jmp/jdecl): a `for` loop over a range compiles to a
-- tail-recursive join-point loop in Lean IR.
@[export test_loop]
def testLoop : UInt32 := Id.run do
  let mut s : UInt32 := 0
  for i in [0:5] do
    s := s + (i.toUInt32)
  return s

@[export test_join]
def testJoin (n : UInt32) : UInt32 :=
  let x := if n > 3 then n + 10 else n * 20
  let a := x * x
  let b := a + x * n
  let c := b * n + a
  let d := c + b * a
  d + c + b + a + x

-- IO workload: mutable ref + loop. Exercises the IO monad (EST.Out), ST refs,
-- and the erased world token — real effects on the JIT runtime.
@[export test_ioref]
def testIORef : IO UInt32 := do
  let r ← IO.mkRef (0 : UInt32)
  for i in [0:5] do
    r.modify (· + i.toUInt32)
  r.get

open Leancremental in
@[export test_bind]
def testBind : IO Nat := do
  let s ← State.create
  let x ← Var.create s 3
  let z ← bind (Var.watch x) (fun a => map (Var.watch x) (fun b => a + b * 10))
  let o ← observe z
  State.stabilize s
  Var.set x 7
  State.stabilize s
  Observer.value! o

open Leancremental in
@[export test_map3]
def testMap3 : IO Nat := do
  let s ← State.create
  let a ← Var.create s 1; let b ← Var.create s 2; let c ← Var.create s 3
  let z ← map3 (Var.watch a) (Var.watch b) (Var.watch c) (fun x y z => x + y*10 + z*100)
  let o ← observe z; State.stabilize s
  Observer.value! o

open Leancremental in
@[export test_seq]
def testSeq : IO Nat := do
  let s ← State.create
  let x ← Var.create s 0
  let z ← map (Var.watch x) (· + 1)
  let o ← observe z
  let mut acc := 0
  for i in [1:5] do
    Var.set x i; State.stabilize s
    acc := acc + (← Observer.value! o)
  pure acc

@[export test_print]
def testPrint : IO Unit := do
  IO.println "hello from Lean IR on the JVM"
  IO.println "second line"

open Leancremental in
@[export test_incr1]
def testIncr1 : IO Nat := do
  let state ← State.create
  let x ← Var.create state 42
  let obs ← observe (Var.watch x)
  State.stabilize state
  Observer.value! obs

open Leancremental in
@[export test_incr2]
def testIncr2 : IO Nat := do
  let state ← State.create
  let x ← Var.create state 5
  let z ← map (Var.watch x) (fun a => a + 100)
  let obs ← observe z
  State.stabilize state
  Observer.value! obs

open Leancremental in
@[export test_incr]
def testIncr : IO Nat := do
  let state ← State.create
  let x ← Var.create state 13
  let y ← Var.create state 17
  let z ← map2 (Var.watch x) (Var.watch y) (fun a b => a + b)
  let observer ← observe z
  State.stabilize state
  Var.set x 19
  State.stabilize state
  Observer.value! observer

-- Soundness probe: `b` aliases `a`, then `a.set!` must NOT corrupt `b`.
-- Sound (copy-on-shared): 99 + 10 = 109.  Unsound (mutate-in-place): 198.
@[export test_share]
def testShare (n : UInt32) : UInt32 := Id.run do
  let a : Array UInt32 := #[n, 20, 30]
  let b := a
  let c := a.set! 0 99
  return c[0]! + b[0]!

@[export test_core]
def testCore : IO Unit := Leancremental.Tests.Core.runAll

@[export test_all]
def testAll : IO Unit := do
  Leancremental.Tests.Core.runAll
  Leancremental.Tests.Query.runAll
  Leancremental.Tests.PureModel.runAll
  Leancremental.Tests.TutorialExamples.runAll
  Leancremental.Tests.Actions.runAll
  Leancremental.Tests.ConceptsExamples.runAll
  Leancremental.Tests.CookbookExamples.runAll
  Leancremental.Tests.Parallel.runAll
  Leancremental.Tests.ConcurrencyExamples.runAll
  Leancremental.Tests.QueriesExamples.runAll
  Leancremental.Tests.FederationExamples.runAll
  IO.println "leancremental tests passed"

@[export test_nofs]
def testNoFS : IO Unit := do
  Leancremental.Tests.Core.runAll
  Leancremental.Tests.PureModel.runAll
  Leancremental.Tests.TutorialExamples.runAll
  Leancremental.Tests.Actions.runAll
  Leancremental.Tests.ConceptsExamples.runAll
  Leancremental.Tests.Parallel.runAll
  Leancremental.Tests.ConcurrencyExamples.runAll
  Leancremental.Tests.QueriesExamples.runAll
  Leancremental.Tests.FederationExamples.runAll
  IO.println "9 non-filesystem modules passed"

@[export test_query]
def testQuery : IO Unit := do Leancremental.Tests.Query.runAll; IO.println "QUERY OK"
@[export test_cookbook]
def testCookbook : IO Unit := do Leancremental.Tests.CookbookExamples.runAll; IO.println "COOKBOOK OK"

@[export test_strcmp]
def testStrCmp (x y : String) : UInt32 :=
  match String.compare x y with
  | .lt => 0
  | .eq => 1
  | .gt => 2

@[export test_float]
def testFloat : UInt64 :=
  let x : Float := 3.14
  let y : Float := 2.0
  let z := x * y + 1.0
  (z * 100.0).toUInt64

@[export test_bignum]
def testBignum : UInt64 :=
  let a : Nat := 2 ^ 100
  let b : Nat := a >>> 70
  let c : Nat := a % 1000000007
  let neg : Int := (-5) * 7
  let d : Nat := neg.natAbs
  (b + c + d).toUInt64

@[export test_strhash]
def testStrHash (s : String) : UInt64 := String.hash s

open Leancremental in
private def heavy (v : Nat) : Nat := (List.range 3000).foldl (fun a i => a + (v * i) % 7) 0
open Leancremental in
@[export test_parbench]
def testParBench (par : Bool) : IO Nat := do
  let s ← State.create
  let x ← Var.create s 1
  let nodes ← (List.range 64).mapM (fun _ => map (Var.watch x) heavy)
  let obs ← nodes.mapM observe
  let mut total : Nat := 0
  for r in [1:9] do
    Var.set x r
    State.stabilize s par
    for o in obs do total := total + (← Observer.value! o)
  pure total

@[export test_uvsys]
def testUvSys : IO String := do
  let cwd ← Std.Internal.UV.System.cwd
  let home ← Std.Internal.UV.System.osHomedir
  let tmp ← Std.Internal.UV.System.osTmpdir
  let pid ← Std.Internal.UV.System.osGetPid
  let host ← Std.Internal.UV.System.osGetHostname
  let uname ← Std.Internal.UV.System.osUname
  let hr ← Std.Internal.UV.System.hrtime
  let env ← Std.Internal.UV.System.osEnviron
  pure s!"{cwd.length != 0}|{home.length != 0}|{tmp.length != 0}|{pid != 0}|{host.length != 0}|{uname.sysname}|{hr != 0}|{env.size != 0}"

@[export test_subproc]
def testSubproc : IO String := do
  let s1 ← IO.Process.run { cmd := "echo", args := #["hello", "world"] }
  let r ← IO.Process.output { cmd := "sh", args := #["-c", "printf out; printf err 1>&2; exit 3"] }
  let child ← IO.Process.spawn { cmd := "head", args := #["-c", "4"], stdin := .piped, stdout := .piped }
  let (stdin, child) ← child.takeStdin
  stdin.putStr "abcdefgh\n"
  stdin.flush
  let piped ← child.stdout.readToEnd
  let _ ← child.wait
  pure s!"{s1.trim}|{r.exitCode}|{r.stdout.trim}|{r.stderr.trim}|{piped}"

@[export test_stdin]
def testStdin : IO String := do
  let stdin ← IO.getStdin
  let l1 ← stdin.getLine
  let l2 ← stdin.getLine
  pure s!"{l1.trim}+{l2.trim}"

@[export test_exit]
def testExit : IO Unit := IO.Process.exit 7

@[export test_io]
def testIO : IO String := do
  let path : System.FilePath := "/tmp/lean4j_io_test.txt"
  let h ← IO.FS.Handle.mk path .write
  h.putStrLn "héllo line"
  h.putStrLn "second"
  h.flush
  let hr ← IO.FS.Handle.mk path .read
  let l1 ← hr.getLine
  let rest ← hr.readToEnd
  let env1 ← IO.getEnv "PATH"
  let env2 ← IO.getEnv "LEAN4J_NOPE_XYZ"
  let cd ← IO.currentDir
  let rp ← IO.FS.realPath path
  let rb ← IO.getRandomBytes 16
  let t ← IO.monoMsNow
  IO.FS.removeFile path
  let cdOk := (toString cd).length != 0
  let rpOk := (toString rp).endsWith "lean4j_io_test.txt"
  pure s!"{l1.trim}|{rest.trim}|{env1.isSome}|{env2.isNone}|{cdOk}|{rpOk}|{rb.size}|{t != 0}"

@[export test_json]
def testJson : String :=
  match Lean.Json.parse "{\"b\": [1, 2.5, true, null], \"a\": \"x\\ny\", \"n\": 42}" with
  | .ok j => j.compress
  | .error e => "ERR: " ++ e

@[export test_strops]
def testStrops : String := Id.run do
  let s := "héllo, wörld! 123"
  let mut acc := ""
  acc := acc ++ s.drop 3 ++ "|"
  acc := acc ++ s.dropRight 4 ++ "|"
  acc := acc ++ toString s.front ++ "|"
  acc := acc ++ (s.toSubstring.drop 7 |>.takeWhile (· != '!') |>.toString) ++ "|"
  acc := acc ++ String.intercalate "-" (s.splitOn " ") ++ "|"
  acc := acc ++ "  trim me  ".trim
  pure acc

@[export test_trig]
def testTrig : Nat := (Float.sin 1.0 * 1e9).toUInt64.toNat + (Float.atan2 2.0 3.0 * 1e9).toUInt64.toNat

@[export test_namebeq]
def testNameBeq : Nat := Id.run do
  let n1 : Lean.Name := `foo.bar
  let n2 : Lean.Name := `foo.bar
  let n3 : Lean.Name := `foo.baz
  let mut r := 0
  if n1 == n2 then r := r + 1
  if n1 == n3 then r := r + 10
  if (`a.b.c : Lean.Name) == `a.b.c then r := r + 100
  pure r

def heavyConc (v : Nat) : Nat := (List.range 2000).foldl (fun a j => a + (v * 31 + j) % 97) 0

@[export test_concurrency]
def testConcurrency : IO Nat := do
  -- fan-out / fan-in: 64 heavy tasks on real workers
  let tasks ← (List.range 64).mapM (fun i => IO.asTask (pure (heavyConc i)))
  let mut fan := 0
  for t in tasks do
    match Task.get t with
    | .ok v => fan := fan + v
    | .error _ => pure ()
  -- promise round-trip (resolved on main first → order-safe) + Task.map chain
  let prom ← IO.Promise.new
  prom.resolve (heavyConc 100)
  let mapped := (prom.result?).map (fun o => match o with | some v => v + 1 | none => 0)
  pure (fan + Task.get mapped)

-- ════════════ Leancremental, driven from the JVM host ════════════
section LeancrementalDemo
open Leancremental

-- Prism tutorial primitives (Float), each a thin export over the real API.
@[export lc_state]   def lcState : IO State := State.create
@[export lc_var]     def lcVar (s : State) (v : Float) : IO (Var Float) := Var.create s v
@[export lc_watch]   def lcWatch (v : Var Float) : IO (Incr Float) := pure (Var.watch v)
-- Generic combinators: `f` is supplied by the HOST (a Java/JS lambda) — no per-op wrapper.
@[export lc_map2]    def lcMap2 (a b : Incr Float) (f : Float → Float → Float) : IO (Incr Float) := map2 a b f
@[export lc_map]     def lcMap (a : Incr Float) (f : Float → Float) : IO (Incr Float) := map a f
@[export lc_observe] def lcObserve (i : Incr Float) : IO (Observer Float) := observe i
@[export lc_stab]    def lcStab (s : State) : IO Unit := State.stabilize s
@[export lc_set]     def lcSet (v : Var Float) (x : Float) : IO Unit := Var.set v x
@[export lc_val]     def lcVal (o : Observer Float) : IO Float := Observer.value! o

-- Incremental sum-reduction tree (Nat): one observed root over n input leaves;
-- changing one leaf recomputes only the log₂(n) nodes on its path.
structure Demo where
  state : State
  leaves : Array (Var Nat)
  observer : Observer Nat

private partial def pairUp : List (Incr Nat) → IO (List (Incr Nat))
  | a :: b :: rest => return (← map2 a b (· + ·)) :: (← pairUp rest)
  | xs => return xs
private partial def reduceAll (xs : List (Incr Nat)) : IO (Incr Nat) := do
  match xs with
  | [x] => return x
  | []  => throw (IO.userError "reduceAll: empty")
  | _   => reduceAll (← pairUp xs)

@[export lc_build] def lcBuild (n : Nat) : IO Demo := do
  let state ← State.create
  let leaves ← (List.range n).mapM (fun i => Var.create state i)
  let root ← reduceAll (leaves.map Var.watch)
  let observer ← observe root
  State.stabilize state
  pure ⟨state, leaves.toArray, observer⟩

@[export lc_dset]  def lcDSet (d : Demo) (i v : Nat) : IO Unit :=
  match d.leaves[i]? with | some var => Var.set var v | none => pure ()
@[export lc_dstab] def lcDStab (d : Demo) : IO Unit := State.stabilize d.state
@[export lc_dval]  def lcDVal (d : Demo) : IO Nat := Observer.value! d.observer
@[export lc_leaves] def lcLeaves (d : Demo) : IO Nat := pure d.leaves.size

-- A burst of `rounds` deterministic updates (leaf (start+k)%n := start+k, then stabilize),
-- run as ONE host call so the hot incremental loop stays in JIT-compiled Lean. Returns the
-- final root; the host can replay the same pattern to verify it.
@[export lc_burst] def lcBurst (d : Demo) (start rounds : Nat) : IO Nat := do
  let n := d.leaves.size
  for k in [0:rounds] do
    let v := start + k
    match d.leaves[(start + k) % n]? with
    | some var => Var.set var v
    | none => pure ()
    State.stabilize d.state
  Observer.value! d.observer
end LeancrementalDemo

@[export bench_kernel]
def benchKernel (iters : UInt64) : UInt64 := Id.run do
  let mut acc : UInt64 := 1
  for i in [0:iters.toNat] do
    let iu := i.toUInt64
    acc := acc * 6364136223846793005 + iu * iu + 1442695040888963407
  return acc

surface Leancremental

#eval show CoreM Unit from do
  let env ← getEnv
  -- every `bind`-generated wrapper (whole `Bind` namespace) is a root, automatically.
  let bindRoots := env.constants.toList.filterMap (fun (n, _) =>
    if (`Wrap).isPrefixOf n then some n else none)
  -- write to $LEAN4J_OUT/leancremental_ir.json (default: current dir), so this works
  -- on any machine — point LEAN4J_OUT at lean4j's lean-runtime/ to run it directly.
  let dir := (← IO.getEnv "LEAN4J_OUT").getD "."
  Lean4JExport.exportRoots env ([`testSumNat, `testEvalMap, `testLoop, `testJoin, `testIORef, `testShare, `testIncr1, `testIncr2, `testIncr, `testBind, `testMap3, `testSeq, `testPrint, `testCore, `testAll, `testNoFS, `testQuery, `testCookbook, `testStrCmp, `testFloat, `testBignum, `testStrHash, `testParBench, `testConcurrency, `testStrops, `testTrig, `testNameBeq, `testJson, `testIO, `testSubproc, `testStdin, `testExit, `testUvSys, `lcState, `lcVar, `lcWatch, `lcMap2, `lcMap, `lcObserve, `lcStab, `lcSet, `lcVal, `lcBuild, `lcDSet, `lcDStab, `lcDVal, `lcLeaves, `lcBurst, `benchKernel] ++ bindRoots)
    s!"{dir}/leancremental_ir.json"
