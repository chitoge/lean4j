# Debugging Lean

This is where running on the JVM really pays off. Native Lean compiles to C, so debugging
a compiled Lean program means gdb on `lean_object*` refcounting. On lean4j you debug at the
Lean level — function names, real `.lean` locations, and live Lean values — on **unmodified**
library code.

There are two things you get: source-located stack traces, and a live debugger.

## What you need first

Both rely on a `src_ranges.json` file, which maps each function to its `.lean` location.
The exporter produces it alongside the IR when you lower a library (see
[generate-ir-and-run.md](generate-ir-and-run.md)). At run time you point the interpreter at
your library's sources so it can read them:

```
-Dlean.src=/path/to/your-library     # the directory holding the .lean files
```

That's the whole setup: lower with `src_ranges.json`, then pass `-Dlean.src`.

## Source-located stack traces

When guest code throws, the exception's stack trace shows the Lean call chain with file and
line — including frames inside compiler-generated code, which inherit their origin
function's location. `make trace`
([`LeanDebugDemo.java`](../core/src/test/java/lean4j/LeanDebugDemo.java)) runs exactly this: a
Java lambda throws while Leancremental's engine recomputes a node, and the exception comes
back source-located:

```
guest error: ... boom! (a Java lambda inside the engine)

Lean stack trace through the library, source-located:
   Internal.State.stabilizeOne   → Internal.lean:668
   State.stabilizeInner          → State.lean:507
   State.stabilizeLocked         → State.lean:479
```

You read these off a `PolyglotException` like any other guest stack trace; each frame's
`getSourceLocation()` is the `.lean` position. This needs no special setup beyond
`src_ranges.json` + `-Dlean.src` — it's just there.

## The live debugger

lean4j wires up Truffle's debugger instrument — the same one Chrome DevTools drives — so you
can set a breakpoint on a Lean function, suspend execution, walk the live stack, and inspect
the frame's variables.

The repo includes a runnable demo
([`LeanLiveDebugDemo.java`](../core/src/test/java/lean4j/LeanLiveDebugDemo.java)) wired to a
specific example library (it breaks on a known function), so once you've lowered that library
you can watch the whole thing work:

```bash
make debug DEBUG_SRC=/path/to/the-example-library
```

For your *own* library you write the same few lines yourself (it's the standard Truffle
`Debugger` API — see below). The demo prints what a hit looks like:

```
⏸  BREAKPOINT hit — live Lean call stack:
   State.stabilize                  State.lean:515
   live frame variables:
      x_1 = State.mk(0, ref(#[...]))     ← the function's argument, a real Lean value
      x_2 = 0
      x_3 = ⊥                            ← a local not yet bound at this point
```

Programmatically it's the standard Truffle `Debugger` API: start a session, install a
`Breakpoint` on a source line, and in the suspend callback read `event.getStackFrames()` and
each frame's scope. Because it's the same instrument as the Chrome inspector, you can also
attach a browser-based debugger to the running JVM.

## Coverage and profiling

The same source sections drive Truffle's coverage and CPU-profiler tools, so you get both on
**unmodified** library code, source-located. (Like the debugger, this is function-level — the
entries are functions, not lines.)

**Coverage** — `make coverage`
([`LeanCoverageDemo.java`](../core/src/test/java/lean4j/LeanCoverageDemo.java)) points
Truffle's coverage instrument at the interpreter, runs the library's own `Tests.Core`, and
reports which of the library's functions that suite touched. Because lean4j translates every
declaration up front, the denominator is the whole surfaced set — so the number is *real*,
not "everything that ran is 100% covered":

```
coverage: 194 / 298 library functions  (65%)
…
some functions Tests.Core never reached:
  Leancremental.FederatedState.advanceFrontier   Federation.lean:89
```

**Profiling** — `make profile`
([`LeanProfileDemo.java`](../core/src/test/java/lean4j/LeanProfileDemo.java)) runs a
stabilize-heavy workload under the CPU sampler and reports the hot Lean functions by `.lean`
line. With trivial Java combiners, the time lands squarely in the incremental engine:

```
hot Lean functions (self-samples · function · source):
      39  Leancremental.Internal.State.stabilizeOne       Internal.lean:668
      31  Leancremental.Internal.State.enqueueRecompute   Internal.lean:222
      25  Leancremental.Internal.State.drainRecomputeHeap Internal.lean:715
```

Both rely on the same `src_ranges.json` + `-Dlean.src` as the debugger, and fail loudly if
they're missing rather than silently dropping the source locations.

## What you can and can't see

Be clear-eyed about the granularity — it's **function-level**, and that's a real boundary,
not a rough edge that'll be filed off soon:

- **Breakpoints** land on functions, not individual lines. You break on `stabilize`, not on
  "line 520 inside stabilize."
- **Variable values are real and inspectable** — a Lean structure expands to its fields, an
  array to its elements.
- **Variable *names* are IR-level** (`x_1`, `x_2`, …), not your source names (`width`,
  `acc`). Lean erases source variable names during compilation, the same reason it erases
  per-line positions.

So the honest pitch is: *break on a function, walk the stack, and inspect everything live* —
which already beats gdb-on-C decisively. Line-by-line stepping and source variable names
would require changes to the Lean compiler itself; see [limitations.md](limitations.md).
