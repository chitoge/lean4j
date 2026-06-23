# lean4j

**Run Lean 4 on the JVM — a debuggable, polyglot interpreter.**

lean4j takes a Lean 4 library, lowers it to a compact intermediate representation (IR),
and *interprets* that IR on [GraalVM](https://www.graalvm.org/)'s Truffle framework (which
JITs the hot paths). It runs Lean's compiled IR, not its native binary, and reimplements the
runtime on the JVM — so it's not a faithful, fast drop-in for native Lean (see
[docs/design.md](docs/design.md)). What it *is* good at: two things you don't get from native
Lean at all —

- **Polyglot interop.** Call your Lean functions from Java, JavaScript, or Python like any
  other library — pass native lambdas, get back values you can read. No FFI, no `lean_object*`.
- **Source-level tooling.** Set a breakpoint on a Lean function, walk the stack, and inspect
  live values — plus source-located profiling and coverage — all on **unmodified** library
  code, located to real `.lean` lines. (Native Lean compiles to C, so this would otherwise
  mean gdb on refcounted C structs.)

Reach for lean4j when you want the JVM ecosystem, polyglot embedding, or real source-level
tooling for Lean libraries — not when you need native speed or bit-exact fidelity.
[docs/design.md](docs/design.md) and [docs/limitations.md](docs/limitations.md) are honest
about where that line is.

## Quickstart

You need [Nix](https://nixos.org/download) with flakes enabled. The flake brings the
exact toolchain — GraalVM, the Truffle libraries, and Lean (via elan) — so there's
nothing to install by hand.

```bash
nix develop          # drops you into a shell with the whole toolchain
make smoke           # builds, then proves the JIT actually engages
make jit-example     # lowers a tiny Lean module and runs it from the JVM
```

`make jit-example` lowers the small Lean file in `lean-runtime/Lean4J.lean` (a few
functions: `addUInt32`, `fib`, `greet`) and calls them from a Java program — your first
end-to-end run.

## Where to go next

The docs are split by what you're trying to do:

| If you want to… | Read |
|---|---|
| Get set up and build | [docs/getting-started.md](docs/getting-started.md) |
| Lower a Lean library to IR and run it | [docs/generate-ir-and-run.md](docs/generate-ir-and-run.md) |
| Call Lean from Java / JavaScript / Python | [docs/polyglot.md](docs/polyglot.md) |
| Debug, profile, and measure coverage on Lean | [docs/debugging.md](docs/debugging.md) |
| Understand the approach and its trade-offs | [docs/design.md](docs/design.md) |
| Know what doesn't work yet | [docs/limitations.md](docs/limitations.md) |

## How it fits together

```
  your Lean library        lean4j's exporter            lean4j-jit (this repo)
  ────────────────         ─────────────────            ──────────────────────
  *.lean  ──lean build──▶  compiled IR  ──Export.lean──▶  IR JSON  ──▶  GraalVM/Truffle
                                                                          │
                                                  ┌───────────────────────┼───────────────────────┐
                                                  ▼                       ▼                       ▼
                                            run it on the JVM      call from Java/JS        set breakpoints,
                                                                                            inspect Lean values
```

The IR is a build artifact — you generate it from a library, it isn't checked in. See
[docs/generate-ir-and-run.md](docs/generate-ir-and-run.md) for how.


## License

MIT License. See [LICENSE](LICENSE).
