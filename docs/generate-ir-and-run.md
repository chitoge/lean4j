# Generate IR and run

lean4j doesn't run Lean *source* — it runs Lean's compiled **IR** (the intermediate
representation Lean's own compiler produces). So the first step in any workflow is
*lowering*: turning a Lean library into an IR JSON file that the JVM side can load.

This page covers both the quick self-contained example and lowering your own library.

## The shape of it

```
your *.lean  ──(lake build)──▶  *.olean  ──(an Export.lean)──▶  yourlib_ir.json  ──▶  lean4j-jit
```

The IR file is a **build artifact**. It's generated from a library, it's deterministic
(same input → byte-identical output), and it's deliberately *not* checked into this repo —
you produce it when you need it.

## The 30-second version

This repo ships a tiny example Lean file (`lean-runtime/Lean4J.lean`) so you can see the
whole pipeline without bringing your own library:

```bash
make jit-example
```

That lowers the example to `lean-runtime/lean4j_ir.json` and runs it. Under the hood it's
two steps you can also run separately:

```bash
make lir-export    # lean-runtime/Lean4J.lean  →  lean-runtime/lean4j_ir.json
make jit-example   # load that IR on the JVM and call into it
```

## A standard-library example — no library of your own

You don't even need a library of your own: Lean's **standard library** lowers the same way.
The lightest demo lowers Lean's built-in JSON parser and calls it from the JVM, using the
exporter that ships with this repo:

```bash
nix develop
lake build                                                              # build the exporter once
LEAN4J_OUT="$PWD/lean-runtime" lake env lean examples/JsonExport.lean   # → lean-runtime/json_ir.json
make json-example
```

[`examples/JsonExport.lean`](../examples/JsonExport.lean) is short: it does `import Lean4JExport`
(the exporter library — **you never copy it**), exposes `Lean.Json.parse` plus a tiny
`jsonRoundtrip` wrapper, and writes the IR. `make json-example`
([`JsonExample.java`](../core/src/test/java/lean4j/JsonExample.java)) then parses JSON with
Lean's own parser, from Java:

```
jsonRoundtrip(...) :    {"ok": true, "n": 42, "hello": [1, 2, 3]}
Lean.Json.parse(...):   Except.ok(Lean.Json.obj(...))
```

Same three-step workflow as everything else — lower, then invoke — pointed at a piece of the
standard library instead of your own code.

## Lowering your own library

You point the exporter at your library with a short script — and you **import** the exporter,
you never copy or reimplement it. Two pieces:

**1. Depend on lean4j** in your library's `lakefile.toml`:

```toml
[[require]]
name = "lean4j"
git = "https://github.com/OWNER/lean4j"   # the public mirror, once it's published
# during local development, use a path instead:
# path = "/path/to/lean4j"
```

then `lake update`.

**2. Write a short exporter**, say `MyExport.lean`:

```lean
import Lean4JExport
import MyLib
open Lean

surface MyLib   -- generate clean, host-callable names for the whole namespace

#eval show CoreM Unit from do
  let env ← getEnv
  -- export everything `surface` generated (the Wrap.* wrappers)
  let roots := env.constants.toList.filterMap fun (n, _) =>
    if (`Wrap).isPrefixOf n then some n else none
  Lean4JExport.exportRoots env roots "mylib_ir.json"
```

Run it inside your library:

```bash
lake env lean MyExport.lean      # → mylib_ir.json
```

`lake env` puts your library's modules and the matching Lean toolchain on the path, and
builds the lean4j exporter **as a dependency against your toolchain** — so the IR types line
up automatically. `surface` gives you clean host-callable names (see [polyglot.md](polyglot.md));
if you'd rather just list a few functions, pass their names to `exportRoots` directly. For
[debugging](debugging.md), also emit a `src_ranges.json`.

> **Why a script and not a CLI flag?** Lowering walks your *compiled environment* and follows
> what each function actually calls — so it has to run as Lean code with your library imported.
> That's also what gives `surface` the full elaborator to generate wrappers and fill defaults.
> [`import Lean4JExport`](../lean-export/Lean4JExport.lean) keeps the serializer itself in one
> place; you only write the roots.

## A worked example, start to finish

Let's lower a real library — [Leancremental](https://github.com/chitoge/Leancremental),
an incremental-computation library — and run it, using the repo's exporter scripts
([`Export.lean`](../examples/leancremental/Export.lean) +
[`SrcRanges.lean`](../examples/leancremental/SrcRanges.lean)). Every command here is part of
the verified path. Do it all from inside `nix develop` (you need Lean for the lowering step).

```bash
nix develop          # in the lean4j repo
LEAN4J="$PWD"        # remember where lean4j is

# 1. get the library and build its .oleans (elan picks the right Lean toolchain).
#    TestsSupport builds the library + the Tests modules the example exporter imports.
git clone https://github.com/chitoge/Leancremental
cd Leancremental
lake build TestsSupport

# 2. add lean4j's exporter as a dependency, then lower. The repo's example exporter
#    (Export.lean) `import`s the shared Lean4JExport library — so you require it, not copy it.
cat >> lakefile.toml <<EOF

[[require]]
name = "lean4j"
path = "$LEAN4J"
EOF
lake update lean4j
cp "$LEAN4J"/examples/leancremental/Export.lean "$LEAN4J"/examples/leancremental/SrcRanges.lean .
LEAN4J_OUT="$LEAN4J/lean-runtime" lake env lean Export.lean      # → leancremental_ir.json
LEAN4J_OUT="$LEAN4J/lean-runtime" lake env lean SrcRanges.lean   # → src_ranges.json (for debugging)

# 3. run it
cd "$LEAN4J"
make leancremental                          # the library's own test suite, on the JVM
make polyglot                               # call it by name from Java + a host lambda
make incremental                            # incremental recompute, with formulas in Java
make debug DEBUG_SRC="$LEAN4J/Leancremental" # set a breakpoint, inspect the live frame
```

`make polyglot` and `make incremental` are the [polyglot scenario](polyglot.md); `make debug`
(and `make trace`) are the [debugging scenario](debugging.md) — those pages explain what each
one shows.

## Running your own lowered library

The `make` targets above default to `lean-runtime/leancremental_ir.json`, but they take an
override so you can point them at any IR you've produced:

```bash
make leancremental LC_IR=/path/to/yourlib_ir.json
make debug         DEBUG_IR=/path/to/yourlib_ir.json DEBUG_SRC=/path/to/your-library
```

(For debugging, `src_ranges.json` needs to sit next to the IR file — both land in the same
directory when you set `LEAN4J_OUT` to point there.)

More commonly you'll skip the demo targets and write your own small Java or JS runner that
loads the IR and calls into it — that's the [polyglot](polyglot.md) scenario, only a few
lines. And if you point a run target at an IR that isn't there yet, it won't crash
mysteriously — it tells you to lower the library first.

## What's actually in the IR

It's JSON: a list of function declarations in Lean's post-compilation IR (the same
`Lean.Compiler.IR` the native backend uses), plus a little module metadata. It's
post-erasure — types are gone, names are mangled the way Lean's compiler mangles them —
which is why lean4j adds the `surface`/`api` layer to give you back clean names. You never
have to read the IR by hand.
