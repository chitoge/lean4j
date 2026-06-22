-- Core Lean4J bridge definitions
-- These functions are exported with clean C names for JVM interop.

@[export lean4j_add_uint32]
def addUInt32 (a b : UInt32) : UInt32 := a + b

@[export lean4j_mul_uint32]
def mulUInt32 (a b : UInt32) : UInt32 := a * b

@[export lean4j_greet]
def greet (name : String) : String := s!"Hello from Lean 4, {name}!"

@[export lean4j_fib]
def fib (n : UInt64) : UInt64 :=
  let rec go (a b : UInt64) : Nat → UInt64
    | 0 => a
    | n + 1 => go b (a + b) n
  go 0 1 n.toNat
