-- Exports the self-contained example's IR (Lean4J.lean) to lean4j_ir.json for the runtime.
-- DELIBERATELY bare-lean: it carries its own small serializer and has NO lake/library
-- dependency, so the smoke / jit-example / bench path needs nothing but `lean` + the jars.
-- The reusable serializer that user-facing exporters import lives in
-- lean-export/Lean4JExport.lean; this internal example intentionally does not use it.
-- Run via: LEAN_PATH=lean-runtime lean -R lean-runtime lean-runtime/LeanIRExport.lean
import Lean
import Lean.Compiler.IR
import Lean4J

open Lean Compiler IR

-- ── JSON helpers ──

private def jStr (s : String) : String :=
  let e := s.replace "\\" "\\\\"
             |>.replace "\"" "\\\""
             |>.replace "\n" "\\n"
             |>.replace "\r" "\\r"
             |>.replace "\t" "\\t"
  "\"" ++ e ++ "\""

private def jNum (n : Nat) : String := toString n
private def jBool (b : Bool) : String := if b then "true" else "false"
private def jArr (xs : List String) : String := "[" ++ ",".intercalate xs ++ "]"
private def jObj (kvs : List (String × String)) : String :=
  "{" ++ ",".intercalate (kvs.map fun (k, v) => jStr k ++ ":" ++ v) ++ "}"

-- ── IRType ──

private partial def serTy : IRType → String
  | .void      => jStr "void"
  | .erased    => jStr "erased"
  | .object    => jStr "obj"
  | .tobject   => jStr "tobj"
  | .tagged    => jStr "tagged"
  | .uint8     => jStr "u8"
  | .uint16    => jStr "u16"
  | .uint32    => jStr "u32"
  | .uint64    => jStr "u64"
  | .usize     => jStr "usize"
  | .float     => jStr "float"
  | .float32   => jStr "float32"
  | .struct nOpt ts => jObj [("tag",   jStr "struct"),
                              ("name",  jStr (nOpt.map Name.toString |>.getD "")),
                              ("types", jArr (ts.toList.map serTy))]
  | .union n ts     => jObj [("tag",   jStr "union"),
                              ("name",  jStr n.toString),
                              ("types", jArr (ts.toList.map serTy))]

-- ── VarId / JoinPointId / Arg ──

private def serVarId (x : VarId) : String := jNum x.idx
private def serJoinId (j : JoinPointId) : String := jNum j.idx

private def serArg : Arg → String
  | .var x  => jObj [("tag", jStr "var"), ("id", serVarId x)]
  | .erased => jObj [("tag", jStr "erased")]

-- ── LitVal ──

private def serLit : LitVal → String
  | .num n => jObj [("tag", jStr "num"), ("val", jStr (toString n))]
  | .str s => jObj [("tag", jStr "str"), ("val", jStr s)]

-- ── CtorInfo ──

private def serCtorInfo (i : CtorInfo) : String :=
  jObj [("name",  jStr i.name.toString),
        ("cidx",  jNum i.cidx),
        ("size",  jNum i.size),
        ("usize", jNum i.usize),
        ("ssize", jNum i.ssize)]

-- ── Expr ──

private def serExpr : IR.Expr → String
  | .ctor i ys      => jObj [("tag",  jStr "ctor"),
                              ("info", serCtorInfo i),
                              ("args", jArr (ys.toList.map serArg))]
  | .reset n x      => jObj [("tag", jStr "reset"), ("n", jNum n), ("id", serVarId x)]
  | .reuse x i _ ys => jObj [("tag",  jStr "reuse"),
                              ("id",   serVarId x),
                              ("info", serCtorInfo i),
                              ("args", jArr (ys.toList.map serArg))]
  | .proj i x       => jObj [("tag", jStr "proj"),  ("i", jNum i), ("id", serVarId x)]
  | .uproj i x      => jObj [("tag", jStr "uproj"), ("i", jNum i), ("id", serVarId x)]
  | .sproj n o x    => jObj [("tag", jStr "sproj"), ("n", jNum n), ("o", jNum o), ("id", serVarId x)]
  | .fap c ys       => jObj [("tag",  jStr "fap"),
                              ("fn",   jStr c.toString),
                              ("args", jArr (ys.toList.map serArg))]
  | .pap c ys       => jObj [("tag",  jStr "pap"),
                              ("fn",   jStr c.toString),
                              ("args", jArr (ys.toList.map serArg))]
  | .ap x ys        => jObj [("tag",  jStr "ap"),
                              ("id",   serVarId x),
                              ("args", jArr (ys.toList.map serArg))]
  | .box t x        => jObj [("tag", jStr "box"),   ("ty", serTy t), ("id", serVarId x)]
  | .unbox x        => jObj [("tag", jStr "unbox"), ("id", serVarId x)]
  | .lit v          => jObj [("tag", jStr "lit"),   ("val", serLit v)]
  | .isShared x     => jObj [("tag", jStr "isShared"), ("id", serVarId x)]

-- ── Param ──

private def serParam (p : Param) : String :=
  jObj [("id", serVarId p.x), ("borrow", jBool p.borrow), ("ty", serTy p.ty)]

-- ── FnBody + Alt (mutual recursion handled by passing serBody as HOF) ──

private def serAlt (serB : FnBody → String) : Alt → String
  | .ctor i b  => jObj [("tag", jStr "ctor"),    ("info", serCtorInfo i), ("body", serB b)]
  | .default b => jObj [("tag", jStr "default"),                          ("body", serB b)]

private partial def serBody : FnBody → String
  | .vdecl x ty e b    => jObj [("tag",  jStr "vdecl"),
                                 ("id",   serVarId x),
                                 ("ty",   serTy ty),
                                 ("expr", serExpr e),
                                 ("cont", serBody b)]
  | .jdecl j xs v b    => jObj [("tag",    jStr "jdecl"),
                                 ("jid",    serJoinId j),
                                 ("params", jArr (xs.toList.map serParam)),
                                 ("body",   serBody v),
                                 ("cont",   serBody b)]
  | .set x i y b        => jObj [("tag",  jStr "set"),
                                  ("id",   serVarId x),
                                  ("i",    jNum i),
                                  ("arg",  serArg y),
                                  ("cont", serBody b)]
  | .setTag x i b       => jObj [("tag",  jStr "setTag"),
                                  ("id",   serVarId x),
                                  ("cidx", jNum i),
                                  ("cont", serBody b)]
  | .uset x i y b       => jObj [("tag",  jStr "uset"),
                                  ("id",   serVarId x),
                                  ("i",    jNum i),
                                  ("yid",  serVarId y),
                                  ("cont", serBody b)]
  | .sset x i o y ty b  => jObj [("tag",  jStr "sset"),
                                  ("id",   serVarId x),
                                  ("i",    jNum i),
                                  ("o",    jNum o),
                                  ("yid",  serVarId y),
                                  ("ty",   serTy ty),
                                  ("cont", serBody b)]
  | .inc x n _ _ b      => jObj [("tag",  jStr "inc"),
                                  ("id",   serVarId x),
                                  ("n",    jNum n),
                                  ("cont", serBody b)]
  | .dec x n _ _ b      => jObj [("tag",  jStr "dec"),
                                  ("id",   serVarId x),
                                  ("n",    jNum n),
                                  ("cont", serBody b)]
  | .del x b            => jObj [("tag",  jStr "del"),
                                  ("id",   serVarId x),
                                  ("cont", serBody b)]
  | .case tid x xTy cs  => jObj [("tag",  jStr "case"),
                                  ("tid",  jStr tid.toString),
                                  ("id",   serVarId x),
                                  ("ty",   serTy xTy),
                                  ("alts", jArr (cs.toList.map (serAlt serBody)))]
  | .ret x              => jObj [("tag", jStr "ret"), ("arg", serArg x)]
  | .jmp j ys           => jObj [("tag",  jStr "jmp"),
                                  ("jid",  serJoinId j),
                                  ("args", jArr (ys.toList.map serArg))]
  | .unreachable        => jObj [("tag", jStr "unreachable")]

-- ── Decl ──

private def serDecl : Decl → String
  | .fdecl f xs ty b _ =>
    jObj [("tag",    jStr "fdecl"),
          ("name",   jStr f.toString),
          ("params", jArr (xs.toList.map serParam)),
          ("retTy",  serTy ty),
          ("body",   serBody b)]
  | .extern f xs ty _ =>
    jObj [("tag",    jStr "extern"),
          ("name",   jStr f.toString),
          ("params", jArr (xs.toList.map serParam)),
          ("retTy",  serTy ty)]

-- ── Export ──

#eval show CoreM Unit from do
  let env ← getEnv
  let names : List Name := [
    `addUInt32, `mulUInt32,
    `greet, `greet._closed_0, `greet._closed_1,
    `fib, `fib.go
  ]
  let mut declJsons : List String := []
  for n in names do
    match IR.findEnvDecl env n with
    | some d => declJsons := declJsons ++ [serDecl d]
    | none   => IO.eprintln s!"[lean4j-ir] WARNING: no IR for {n}"
  let json := jObj [("decls", jArr declJsons)]
  IO.FS.writeFile "lean-runtime/lean4j_ir.json" json
  IO.println s!"[lean4j-ir] Wrote {declJsons.length} decls to lean-runtime/lean4j_ir.json"
