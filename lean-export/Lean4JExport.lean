import Lean
import Lean.Compiler.IR
open Lean Compiler IR

/-! The lean4j IR exporter: serialize a Lean library's compiled IR (`Lean.Compiler.IR`)
    to the JSON that lean4j-jit loads. Import this and call `Lean4JExport.exportRoots`
    from a tiny `#eval` script; you never copy this code. (It imports `Lean.Compiler.IR`,
    so it tracks that compiler API — a library far from this Lean version may see drift.) -/

namespace Lean4JExport

-- ───────────────────────── JSON helpers ─────────────────────────

private def jStr (s : String) : String :=
  let e := s.replace "\\" "\\\\" |>.replace "\"" "\\\"" |>.replace "\n" "\\n"
             |>.replace "\r" "\\r" |>.replace "\t" "\\t"
  "\"" ++ e ++ "\""
private def jNum (n : Nat) : String := toString n
private def jBool (b : Bool) : String := if b then "true" else "false"
private def jArr (xs : List String) : String := "[" ++ ",".intercalate xs ++ "]"
private def jObj (kvs : List (String × String)) : String :=
  "{" ++ ",".intercalate (kvs.map fun (k, v) => jStr k ++ ":" ++ v) ++ "}"

-- ───────────────────────── serializers ─────────────────────────

private partial def serTy : IRType → String
  | .void => jStr "void" | .erased => jStr "erased"
  | .object => jStr "obj" | .tobject => jStr "tobj" | .tagged => jStr "tagged"
  | .uint8 => jStr "u8" | .uint16 => jStr "u16" | .uint32 => jStr "u32"
  | .uint64 => jStr "u64" | .usize => jStr "usize"
  | .float => jStr "float" | .float32 => jStr "float32"
  | .struct n ts => jObj [("tag", jStr "struct"), ("name", jStr (n.map Name.toString |>.getD "")),
                          ("types", jArr (ts.toList.map serTy))]
  | .union n ts  => jObj [("tag", jStr "union"), ("name", jStr n.toString),
                          ("types", jArr (ts.toList.map serTy))]

private def serVarId (x : VarId) : String := jNum x.idx
private def serJoinId (j : JoinPointId) : String := jNum j.idx
private def serArg : Arg → String
  | .var x  => jObj [("tag", jStr "var"), ("id", serVarId x)]
  | .erased => jObj [("tag", jStr "erased")]
private def serLit : LitVal → String
  | .num n => jObj [("tag", jStr "num"), ("val", jStr (toString n))]
  | .str s => jObj [("tag", jStr "str"), ("val", jStr s)]
private def serCtorInfo (i : CtorInfo) : String :=
  jObj [("name", jStr i.name.toString), ("cidx", jNum i.cidx), ("size", jNum i.size),
        ("usize", jNum i.usize), ("ssize", jNum i.ssize)]

private def serExpr : IR.Expr → String
  | .ctor i ys      => jObj [("tag", jStr "ctor"), ("info", serCtorInfo i), ("args", jArr (ys.toList.map serArg))]
  | .reset n x      => jObj [("tag", jStr "reset"), ("n", jNum n), ("id", serVarId x)]
  | .reuse x i _ ys => jObj [("tag", jStr "reuse"), ("id", serVarId x), ("info", serCtorInfo i), ("args", jArr (ys.toList.map serArg))]
  | .proj i x       => jObj [("tag", jStr "proj"), ("i", jNum i), ("id", serVarId x)]
  | .uproj i x      => jObj [("tag", jStr "uproj"), ("i", jNum i), ("id", serVarId x)]
  | .sproj n o x    => jObj [("tag", jStr "sproj"), ("n", jNum n), ("o", jNum o), ("id", serVarId x)]
  | .fap c ys       => jObj [("tag", jStr "fap"), ("fn", jStr c.toString), ("args", jArr (ys.toList.map serArg))]
  | .pap c ys       => jObj [("tag", jStr "pap"), ("fn", jStr c.toString), ("args", jArr (ys.toList.map serArg))]
  | .ap x ys        => jObj [("tag", jStr "ap"), ("id", serVarId x), ("args", jArr (ys.toList.map serArg))]
  | .box t x        => jObj [("tag", jStr "box"), ("ty", serTy t), ("id", serVarId x)]
  | .unbox x        => jObj [("tag", jStr "unbox"), ("id", serVarId x)]
  | .lit v          => jObj [("tag", jStr "lit"), ("val", serLit v)]
  | .isShared x     => jObj [("tag", jStr "isShared"), ("id", serVarId x)]

private def serParam (p : Param) : String :=
  jObj [("id", serVarId p.x), ("borrow", jBool p.borrow), ("ty", serTy p.ty)]

private def serAlt (serB : FnBody → String) : Alt → String
  | .ctor i b  => jObj [("tag", jStr "ctor"), ("info", serCtorInfo i), ("body", serB b)]
  | .default b => jObj [("tag", jStr "default"), ("body", serB b)]

private partial def serBody : FnBody → String
  | .vdecl x ty e b    => jObj [("tag", jStr "vdecl"), ("id", serVarId x), ("ty", serTy ty), ("expr", serExpr e), ("cont", serBody b)]
  | .jdecl j xs v b    => jObj [("tag", jStr "jdecl"), ("jid", serJoinId j), ("params", jArr (xs.toList.map serParam)), ("body", serBody v), ("cont", serBody b)]
  | .set x i y b       => jObj [("tag", jStr "set"), ("id", serVarId x), ("i", jNum i), ("arg", serArg y), ("cont", serBody b)]
  | .setTag x i b      => jObj [("tag", jStr "setTag"), ("id", serVarId x), ("cidx", jNum i), ("cont", serBody b)]
  | .uset x i y b      => jObj [("tag", jStr "uset"), ("id", serVarId x), ("i", jNum i), ("yid", serVarId y), ("cont", serBody b)]
  | .sset x i o y ty b => jObj [("tag", jStr "sset"), ("id", serVarId x), ("i", jNum i), ("o", jNum o), ("yid", serVarId y), ("ty", serTy ty), ("cont", serBody b)]
  | .inc x n _ _ b     => jObj [("tag", jStr "inc"), ("id", serVarId x), ("n", jNum n), ("cont", serBody b)]
  | .dec x n _ _ b     => jObj [("tag", jStr "dec"), ("id", serVarId x), ("n", jNum n), ("cont", serBody b)]
  | .del x b           => jObj [("tag", jStr "del"), ("id", serVarId x), ("cont", serBody b)]
  | .case tid x xTy cs => jObj [("tag", jStr "case"), ("tid", jStr tid.toString), ("id", serVarId x), ("ty", serTy xTy), ("alts", jArr (cs.toList.map (serAlt serBody)))]
  | .ret x             => jObj [("tag", jStr "ret"), ("arg", serArg x)]
  | .jmp j ys          => jObj [("tag", jStr "jmp"), ("jid", serJoinId j), ("args", jArr (ys.toList.map serArg))]
  | .unreachable       => jObj [("tag", jStr "unreachable")]

private def serDecl : Decl → String
  | .fdecl f xs ty b _ => jObj [("tag", jStr "fdecl"), ("name", jStr f.toString), ("params", jArr (xs.toList.map serParam)), ("retTy", serTy ty), ("body", serBody b)]
  | .extern f xs ty _  => jObj [("tag", jStr "extern"), ("name", jStr f.toString), ("params", jArr (xs.toList.map serParam)), ("retTy", serTy ty)]

-- ─────────────── reference collection (fap/pap targets) ───────────────

private partial def refsExpr : IR.Expr → List Name
  | .fap c _ => [c]
  | .pap c _ => [c]
  | _        => []

private partial def refsBody : FnBody → List Name
  | .vdecl _ _ e b    => refsExpr e ++ refsBody b
  | .jdecl _ _ v b    => refsBody v ++ refsBody b
  | .set _ _ _ b      => refsBody b
  | .setTag _ _ b     => refsBody b
  | .uset _ _ _ b     => refsBody b
  | .sset _ _ _ _ _ b => refsBody b
  | .inc _ _ _ _ b    => refsBody b
  | .dec _ _ _ _ b    => refsBody b
  | .del _ b          => refsBody b
  | .case _ _ _ cs    => cs.toList.flatMap (fun a => refsBody a.body)
  | .ret _ => [] | .jmp _ _ => [] | .unreachable => []

private def refsDecl : Decl → List Name
  | .fdecl _ _ _ b _ => refsBody b
  | .extern .. => []

-- ─────────────── transitive closure walk ───────────────

private partial def walk (env : Environment) (worklist : List Name)
    (seen : Std.HashSet Name) (fdecls externs : List String) (inits : List String)
    : List String × List String × List String :=
  match worklist with
  | [] => (fdecls, externs, inits)
  | n :: rest =>
    if seen.contains n then walk env rest seen fdecls externs inits
    else
      let seen := seen.insert n
      -- A module-level `initialize` global has an init-fn; record the mapping and
      -- pull the initializer into the closure so its IR is interpreted.
      let (extraWork, inits) := match getInitFnNameFor? env n with
        | some initFn => ([initFn], inits ++ [jObj [("global", jStr n.toString), ("initFn", jStr initFn.toString)]])
        | none => ([], inits)
      match IR.findEnvDecl env n with
      | some (d@(.fdecl ..)) =>
        walk env (rest ++ refsDecl d ++ extraWork) seen (fdecls ++ [serDecl d]) externs inits
      | some (.extern ..) =>
        walk env (rest ++ extraWork) seen fdecls (externs ++ [jStr n.toString]) inits
      | none =>
        walk env (rest ++ extraWork) seen fdecls (externs ++ [jStr n.toString]) inits

def exportRoots (env : Environment) (roots : List Name) (path : String) : IO Unit := do
  let (fdecls, externs, inits) := walk env roots {} [] [] []
  let json := jObj [("decls", jArr fdecls), ("opaqueExterns", jArr externs), ("inits", jArr inits)]
  IO.FS.writeFile path json
  IO.println s!"[lean4j-ir] {fdecls.length} fdecls, {externs.length} externs, {inits.length} inits → {path}"

end Lean4JExport

-- ════════════ `surface Ns` — point at a namespace, surface its WHOLE API ════════════
-- Generates a POLYMORPHIC `Wrap.<fn>` per host-relevant def: drops optParam args (Lean's
-- elaborator bakes the defaults), keeps type/instance binders (erased at runtime, so no
-- monomorphization), de-inlines. module.api routes the original dotted name → the wrapper.
-- The user writes ONE line; no per-function specs, no types, library untouched.
open Lean Meta Elab Command in
private def lean4jOptDefault? (e : Lean.Expr) : Option Lean.Expr :=
  if e.isAppOfArity ``optParam 2 then some e.appArg! else none
open Lean Meta Elab Command in
private def lean4jMkWrapper (fn : Name) : MetaM (Option (Lean.Expr × Lean.Expr)) := do
  let info ← getConstInfo fn
  forallTelescope info.type fun xs resultTy => do
    let mut keep : Array Lean.Expr := #[]
    let mut args : Array Lean.Expr := #[]
    for x in xs do
      match lean4jOptDefault? (← inferType x) with
      | some d => args := args.push d
      | none   => keep := keep.push x; args := args.push x
    let type ← mkForallFVars keep resultTy
    let val  ← mkLambdaFVars keep (mkAppN (mkConst fn (info.levelParams.map Level.param)) args)
    if type.hasFVar || val.hasFVar then return none      -- skip non-closed (dependent) wrappers
    if (← isProp resultTy) then return none              -- skip proofs/decidability
    return some (type, val)
open Lean Meta Elab Command in
private def lean4jGenerated (n : Name) : Bool :=
  let s := n.toString
  ["noConfusion","casesOn",".rec",".below","brecOn","ndrec","injEq","sizeOf","binductionOn",
   ".eq_",".match_",".proof_"].any (fun m => (s.splitOn m).length > 1)
open Lean Meta Elab Command in
elab "surface " ns:ident : command => do
  let nsName := ns.getId
  let cs := (← getEnv).constants.toList
  let mut ok := 0
  for (n, info) in cs do
    if !(nsName.isPrefixOf n) then continue
    if !(info matches .defnInfo _) then continue
    if n.isInternalDetail || isPrivateName n || lean4jGenerated n then continue
    try
      match ← liftTermElabM (lean4jMkWrapper n) with
      | none => pure ()
      | some (type, val) =>
        let wname := `Wrap ++ n
        liftCoreM (addAndCompile (Declaration.defnDecl { name := wname, levelParams := info.levelParams, type := type, value := val, hints := ReducibilityHints.regular 0, safety := DefinitionSafety.safe }))
        ok := ok + 1
    catch _ => pure ()
  logInfo s!"surface: {ok} functions from {nsName}"
