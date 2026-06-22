# lean4j

**Run compiled Lean 4 on the JVM.**

lean4j takes a Lean 4 library, lowers it to a compact intermediate representation (IR),
and runs that IR on [GraalVM](https://www.graalvm.org/)'s Truffle framework — a
JIT-compiling interpreter on the JVM. Once your Lean code is running there, two things
come for free that you don't get from native Lean:

- **Polyglot interop.** Call your Lean functions from Java or JavaScript like any other
  library — pass native lambdas, get back values you can read. No FFI, no `lean_object*`.
- **Source-level debugging.** Set a breakpoint on a Lean function, suspend, walk the
  call stack with real `.lean` file/line locations, and inspect the live values — all on
  **unmodified** library code. (Native Lean compiles to C, so debugging there means gdb
  on refcounted C structs.)

It's a JIT *interpreter*, not an ahead-of-time compiler, so it trades raw speed for that
interop and tooling. If you want the JVM ecosystem, polyglot embedding, or a real
debugger for Lean, that's the trade lean4j makes.

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
| Call Lean from Java / JavaScript | [docs/polyglot.md](docs/polyglot.md) |
| Debug Lean with breakpoints and a live stack | [docs/debugging.md](docs/debugging.md) |
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
