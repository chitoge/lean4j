# Design and trade-offs

Most of lean4j's character — what it does well and where it stops — follows from a single
decision, so it's worth stating plainly.

## The one decision: consume standard output, never fork Lean

lean4j runs what `lake build` already produces. The exporter reads the compiled IR out of
the `.olean`s; the interpreter runs that IR. We do **not** patch the Lean compiler, and we
do not ship a modified toolchain.

That's the whole strength. It means lean4j works on **any** Lean library, **unmodified**,
built with the **stock** toolchain — no per-library porting, no patched compiler for users to
install. When Lean moves, you re-export and carry on. The entire ecosystem is reachable
because we ask nothing of it.

It's also the ceiling. The IR we read is the compiler's output *after* erasure:

- **types are gone** — they're erased to `erased` params; polymorphism has already been
  compiled away;
- **per-statement source positions are gone** — only *declaration* ranges survive (via
  `declRangeExt`), which is what gives us function-level `.lean` locations;
- **local names are gone** — they're renumbered `x_1, x_2, …`.

No consumer of that IR can show more than survived it. The function-level granularity you see
in the debugger, coverage, and profiler isn't an unfinished feature — it's an **information
floor**. The line info and the name `width` were discarded before we ever saw the program.

## Two kinds of fidelity — and only one needs a compiler patch

It's tempting to lump all the gaps together as "lower fidelity, would need a fork." That's
not right, and the distinction matters:

- **Source-information fidelity** — line-level breakpoints, source variable names,
  statement-level coverage. This *is* floored by the no-fork choice. Breaking it means the
  compiler has to *emit more*: thread source positions through IR lowering, keep an
  IR-var → source-name map. There's no way to recover discarded information after the fact, so
  this genuinely requires patching Lean — i.e. a fork.

- **Runtime / semantic fidelity** — whether our Java reimplementations of Lean's primitives
  (`String.hash`, `Float`, big `Nat`/`Int`, the concurrency model, the IO surface) match
  native Lean exactly. This is **not** floored at all. It's ordinary engineering inside the
  no-fork model; a primitive that's currently an approximation can be made bit-exact without
  touching the compiler.

So the honest claim is narrow: **a compiler patch would buy line-level tooling and real
variable names — and nothing for runtime correctness, which is reachable as-is.**

## The trade

| | No-fork (what lean4j does) | Fork / patch the compiler |
|---|---|---|
| **Reach** | any library, stock toolchain, unmodified | only libraries built with *your* patched toolchain |
| **Upkeep** | re-export to track upstream Lean | maintain a Lean fork; track upstream by hand |
| **Source tooling** | function-level (break on `stabilize`, not line 520) | line-level, real variable names |
| **Runtime fidelity** | reachable to ~100% by engineering | same |

For what this approach is *for*, the no-fork ceiling is the right call. Function-level,
source-located debugging / coverage / profiling on unmodified library code already beats the
native-Lean alternative (gdb on `lean_object*`) decisively, and "runs on stock Lean" is worth
more than line numbers. A fork would trade away the one property that makes lean4j interesting
for a marginal gain.

## What it's good for — and what it isn't

- **Good for:** JVM/polyglot interop (call Lean from Java, JS, Python; pass host lambdas);
  source-located tooling that native Lean can't easily offer; prototyping and embedding Lean
  logic where the JVM ecosystem is the point.
- **Not the right tool for:** maximum-fidelity or maximum-throughput production. It's a
  JIT *interpreter* — tens of times slower than native AOT Lean — and some runtime primitives
  are still approximations. For correctness-critical, performance-critical deployment, native
  Lean is the answer.

The lever that would most *increase* lean4j's usefulness is therefore **not** a compiler
patch — it's the runtime-fidelity work (which makes embedding credible) plus a real
demonstration of a Lean-verified component doing actual work inside a host application. Both
live entirely within the no-fork model.

For the concrete, current limitations that fall out of all this, see
[limitations.md](limitations.md).
