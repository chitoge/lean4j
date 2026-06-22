import Leancremental
import Tests.Core
import Tests.Parallel
import Tests.ConcurrencyExamples
import Lean
open Lean
def q (s : String) : String := "\"" ++ s ++ "\""
def hasSub (s p : String) : Bool := (s.splitOn p).length != 1
#eval show CoreM Unit from do
  let env ← getEnv
  let mut entries : Array String := #[]
  for (n, _) in env.constants.toList do
    if let some r ← findDeclarationRanges? n then
      if let some idx := env.getModuleIdxFor? n then
        let mod := env.header.moduleNames[idx.toNat]!.toString
        if hasSub mod "Leancremental" || hasSub mod "Tests" then
          let entry := q n.toString ++ ":{" ++ q "mod" ++ ":" ++ q mod ++ ","
            ++ q "line" ++ ":" ++ toString r.range.pos.line ++ ","
            ++ q "endLine" ++ ":" ++ toString r.range.endPos.line ++ "}"
          entries := entries.push entry
  let dir := (← IO.getEnv "LEAN4J_OUT").getD "."
  IO.FS.writeFile s!"{dir}/src_ranges.json"
    ("{" ++ ",".intercalate entries.toList ++ "}")
  IO.println s!"wrote {entries.size} source ranges"
