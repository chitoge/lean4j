import Lean4JExport
import Lean
open Lean

/-! Standalone example: lower a STANDARD-LIBRARY part (Lean's JSON parser) to IR — no
    external library needed. From inside lean4j (in `nix develop`):
        lake build                                  -- builds the Lean4JExport library once
        LEAN4J_OUT="$PWD/lean-runtime" lake env lean examples/JsonExport.lean
    then call it from the JVM with `make json-example` (see docs/polyglot.md). -/

-- a tiny host-friendly entry point: parse with Lean's own parser, hand back normalized JSON
def jsonRoundtrip (s : String) : String :=
  match Lean.Json.parse s with
  | .ok j    => toString j
  | .error e => s!"parse error: {e}"

#eval show CoreM Unit from do
  let env ← getEnv
  let dir := (← IO.getEnv "LEAN4J_OUT").getD "."
  -- export our wrapper plus Lean's parser entry point
  Lean4JExport.exportRoots env [`jsonRoundtrip, `Lean.Json.parse] s!"{dir}/json_ir.json"
