# Call Lean from Java and JavaScript

Once a library is [lowered to IR](generate-ir-and-run.md), it's a polyglot object on
GraalVM. You load it, you get back a module, and you call its functions. This page shows
the plain way, the ergonomic way, and how to pass host functions into Lean.

All of this runs on a GraalVM `Context`; the language id is `lean4j-jit`.

## The minimal version

Load the IR and call a function. Here's the whole thing against the in-repo example
(`addUInt32`, `fib`, `greet` — all pure):

```java
import org.graalvm.polyglot.*;

try (Context ctx = Context.newBuilder("lean4j-jit").allowAllAccess(true).build()) {
    // eval'ing the path to an IR JSON gives you the module
    Value module = ctx.eval("lean4j-jit", "lean-runtime/lean4j_ir.json");

    int sum     = module.invokeMember("addUInt32", 21, 21).asInt();   // 42
    long fib30  = module.invokeMember("fib", 30L).asLong();           // 832040
    String hi   = module.invokeMember("greet", "World").asString();
}
```

Numbers, strings, and the like map across naturally:

| Lean | Host (Java/JS) |
|---|---|
| `Nat`, `UInt8/16/32/64`, `Int` | integer (`long`) |
| `Float` | `double` |
| `String` | string |
| `Array α` | an array you can index |
| a structure / inductive | an object whose fields you can read |

`make jit-example` runs essentially the snippet above, so you can see it work before
writing any code yourself.

## The ergonomic version: `module.api`

The plain `invokeMember` path has two sharp edges once you go past pure functions:

- **Names are mangled.** Lean's compiler rewrites `YourLib.foo` into things like
  `YourLib.foo._redArg`, and inlines some functions away entirely.
- **`IO` has a calling convention.** An `IO` function takes a hidden "world" token and
  returns its result wrapped in a success/error envelope — fiddly to deal with by hand.

You don't have to. If you lowered your library with `surface` (see
[generate-ir-and-run.md](generate-ir-and-run.md)), call `module.api` instead and use the
**names straight from your library's docs**:

```java
Value api = module.getMember("api");

api.getMemberKeys();                       // every surfaced function, by its real name
Value v = api.invokeMember("YourLib.compute", x, y);   // mangling, world token,
                                                       // and IO-unwrapping all handled
```

`module.api` resolves the documented name to the generated wrapper, fills in the type and
world arguments, and unwraps the `IO` envelope (turning a Lean error into a host
exception). What you write is just the function and its logical arguments.

> **See it run.** After lowering the example library (the worked example in
> [generate-ir-and-run.md](generate-ir-and-run.md)), `make polyglot` runs exactly this
> against Leancremental — building a little computation graph by calling
> `Leancremental.map2`, `Leancremental.observe`, etc. by name, with a Java lambda as the
> combiner — and prints the result.

## Passing host functions into Lean

A Lean function that takes a function argument can take **your** function — a Java lambda
or a JavaScript function — directly. lean4j routes it back as a Lean closure. So if a Lean
API wants a combiner `(α → β → γ)`, you hand it one:

```java
import org.graalvm.polyglot.proxy.ProxyExecutable;

ProxyExecutable times = (Value... a) -> a[0].asDouble() * a[1].asDouble();
Value node = api.invokeMember("YourLib.map2", left, right, times);
```

This is how you drive a Lean library's higher-order API from the host without writing any
Lean glue.

## From JavaScript

Because it's all polyglot, the same works from GraalJS. A JS function passed where Lean
wants a function behaves just like the Java lambda above:

```js
const module = Polyglot.eval('lean4j-jit', 'lean-runtime/lean4j_ir.json');
const api = module.api;

// invoke by the (dotted) name with bracket-call syntax; a JS function goes straight in
const node = api['YourLib.map2'](left, right, (a, b) => a * b);
```

## A note on what `surface` gives you

`surface YourLib` exposes the whole namespace under the original names, so you call what
the docs say. It does this by generating a thin polymorphic wrapper per function and
routing the documented name to it — you never see or name the wrapper. The one thing it
can't fully automate is functions that consume a typeclass instance at runtime; see
[limitations.md](limitations.md).
