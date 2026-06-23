# Getting started

This page gets you from a fresh clone to a verified build. It assumes nothing beyond
[Nix](https://nixos.org/download) with flakes enabled.

## The toolchain, in one command

lean4j needs three things that are fussy to install by hand and version-sensitive: a
specific GraalVM (so the JIT actually engages), the matching Truffle libraries, and Lean
(at whatever version your library was built with). The flake provides all of them, so you
don't touch your global environment:

```bash
nix develop
```

That drops you into a shell with:

- **GraalVM CE 25.0.2** — the JDK whose Graal compiler the optimizing Truffle runtime
  binds to.
- the **Truffle / polyglot / GraalJS** libraries, pinned to match.
- **Lean**, via [`elan`](https://github.com/leanprover/elan) — which reads each project's
  `lean-toolchain` file and installs the *exact* Lean version it needs. This matters
  because Lean's compiled `.olean` files are version-locked; elan getting the right
  version is what makes lowering *any* library work, not just this repo's examples.
- **gcc**, for building a library's `.olean`s before you lower them.

There's a lighter shell too, if you only want to *run* a pre-made IR and never lower
anything yourself:

```bash
nix develop .#runtime   # GraalVM + Truffle jars only, no Lean
```

## Build and verify

Inside the shell:

```bash
make java     # compile the interpreter
make smoke    # the important one — see below
```

`make smoke` lowers the tiny in-repo Lean file, warms a hot function, and **asserts that
Truffle actually compiled it**. That check matters more than it looks: if the GraalVM JDK
and the Truffle libraries ever drift out of sync, Truffle silently falls back to
interpreter-only — the build still passes, the tests still pass, but the JIT is just gone.
`make smoke` turns that silent failure into a loud one. A healthy run ends with:

```
SMOKE OK — Truffle JIT engaged (compilation observed).
```

## Run something

```bash
make jit-example   # lowers lean-runtime/Lean4J.lean and calls it from Java
```

You should see it call `addUInt32`, `fib`, and `greet` from a Java program. That's the
whole pipeline working end to end. Where to next:

- [Lower your own Lean library and run it](generate-ir-and-run.md)
- [Call Lean from Java / JavaScript](polyglot.md)
- [Debug Lean code](debugging.md)

## The make targets

| Target | What it does |
|---|---|
| `make java` | compile the interpreter (no Lean needed) |
| `make smoke` | lower the in-repo example, warm it, **assert the JIT engaged** |
| `make jit-example` | lower the in-repo example and call it from Java |
| `make json-example` | lower Lean's standard-library JSON parser and call it |
| `make bench` | A/B benchmark: the JIT vs the tree-walker |
| `make leancremental` | run a lowered library's own test suite on the JVM |
| `make polyglot` | call a lowered library by documented names, with a Java lambda |
| `make incremental` | incremental-recompute showcase (the formulas are in Java) |
| `make polyglot-js` | the same call from JavaScript (asserts the guest member-call path) |
| `make polyglot-py` | the same call from Python / GraalPy |
| `make debug` | live debugger — breakpoint, walk the stack, inspect the frame |
| `make trace` | post-mortem source-located stack trace |
| `make coverage` | source-located code coverage of a lowered library |
| `make profile` | source-located CPU profile of a lowered library |

The `leancremental` / `polyglot` / `incremental` / `polyglot-js` / `polyglot-py` / `debug` /
`trace` / `coverage` / `profile` targets are demos **wired to the Leancremental example** —
they call its functions by name, so they need *its* IR present first (they tell you how to
lower it if it's missing). To run these capabilities against *your own* library you write a
short runner; [generate-ir-and-run.md](generate-ir-and-run.md) explains both.

Each demo's source lives under [`core/src/test/java/lean4j/`](../core/src/test/java/lean4j/),
the exporter scripts under [`examples/`](../examples/), and the reusable exporter library at
[`lean-export/Lean4JExport.lean`](../lean-export/Lean4JExport.lean).

## Without Nix

If you'd rather use your own tools, everything the flake sets via the environment is
overridable in the `Makefile`: `GRAALVM`, `M2` (a Maven-layout dir holding the Truffle
jars), `GVER`, and `lean` on your `PATH`. You'll need GraalVM CE 25.0.2, the
Truffle 25.0.2 libraries, and a Lean toolchain matching whatever you lower. The Nix path
is strongly recommended — getting the GraalVM/Truffle versions to match by hand is exactly
the pain the flake removes.

## A note for NixOS users

`elan` downloads upstream prebuilt Lean binaries, which expect a standard Linux dynamic
loader. On NixOS that won't resolve out of the box — enable `programs.nix-ld` (or run Lean
inside an FHS shell) so those binaries can start. On other Linux distros and macOS it just
works.
