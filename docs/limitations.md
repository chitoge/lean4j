# Current limitations

lean4j is useful today, but it's an interpreter with a deliberate set of trade-offs and
some genuine rough edges. Here's the honest list so nothing surprises you. Most of these
follow from one design choice — consuming standard Lean output instead of forking the
compiler; [design.md](design.md) explains why, and which limits a compiler patch could lift
(fewer than you'd think).

## It's a JIT interpreter, not a compiler

Expect roughly **tens of times slower than native, ahead-of-time-compiled Lean** on
compute-bound code — in the ballpark of 50–85× on tight numeric loops, more on
memory/allocation-bound work. It is, however, faster than Lean's own IR interpreter
(`lean --run`) on compute. The point of lean4j is the JVM ecosystem, polyglot interop, and
debugging — not raw throughput. If you need maximum speed, native Lean is the right tool.

The JIT does kick in (Truffle compiles hot code to native), and `make smoke` proves it's
working — but it's optimizing an interpreter, not producing standalone compiled binaries.

## Debugging is function-level

You can break on functions, walk the stack, and inspect live values — but:

- **No line-by-line stepping.** Breakpoints land on functions, not lines.
- **Variable names are IR-level** (`x_1`, `x_2`), not your source names. The *values* are
  real and inspectable; only the labels are lost.

Both come from the same root cause: Lean erases per-statement source positions and local
variable names during compilation, so there's nothing below the function level to recover.
True line stepping and source names would require threading that information through the
Lean compiler itself — a bigger change than anything in this repo.

There's also a narrower edge: a real (user-named) function that returns a *raw* internal
value like a bare closure or mutable ref — rather than a normal data structure — can trip
the debugger's instrumentation, which interop-checks return values. The common API surface
returns data structures and isn't affected; this is fixed type-by-type as cases come up.

## Polyglot surfacing isn't 100% automatic

`surface` exposes a whole namespace cleanly, and handles defaults, but:

- It also surfaces some **compiler-generated internal variants** (names ending in things
  like `._redArg`) alongside the real ones. They're harmless — you just ignore them — and
  the filter that hides them is heuristic.
- Functions that **consume a typeclass instance at run time** (e.g. one that calls into a
  `BEq`/`Hashable` dictionary) can't be fully auto-surfaced, because the instance to use is
  type-dependent. Most functions don't, but some do.

## Deep non-tail recursion can overflow the stack

Self-tail-recursive functions — which is what Lean's `for`/`fold` loops compile to — are
turned into real loops, so they run in bounded stack and don't overflow. But there's no
general tail-call optimization: deeply nested **non-tail** recursion, or **mutual**
recursion, still uses one JVM stack frame per call and can overflow on very deep inputs.
You can raise the JVM stack size (`-Xss`) as a workaround.

## Runtime fidelity is partial

lean4j reimplements Lean's runtime primitives on the JVM rather than calling Lean's C
runtime. Most are faithful, but some are approximations and a few corners are stubbed. If
your code leans on exact semantics of an unusual primitive, verify it behaves the way you
expect before relying on it.

## Versions are coupled, on purpose

- The **GraalVM JDK and the Truffle libraries must be the same version**, or the optimizing
  JIT silently won't engage. The flake pins both, and `make smoke` is the loud guard if they
  ever drift.
- **Lean must match the library you're lowering** — `.olean` files are version-locked. The
  toolchain (via `elan`) handles this per library, but it does mean lowering needs the right
  Lean version available.

## Platform

The flake targets `x86_64-linux` and `aarch64-linux`. Other platforms aren't wired up.

---

None of these are secret footguns — they're the shape of "a Lean runtime built on Truffle."
If one of them is blocking you, that's useful to know; several (statement-level debugging,
broader surfacing) have clear paths forward.
