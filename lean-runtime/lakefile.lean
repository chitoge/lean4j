import Lake
open Lake DSL

package lean4j where
  name := "lean4j"
  version := "0.1.0"

-- Core Lean4J runtime bridge definitions
lean_lib Lean4JLib where
  roots := #[`Lean4J]
  srcDir := "."

-- Build the shared library for JVM interop
target lean4j_lib : System.FilePath := do
  let leanToolchain ← getLeanInstallPath
  let leanLib := leanToolchain / "lib" / "lean"
  let pkg ← findPackage? `lean4j
  let some pkg := pkg | error "Package lean4j not found"
  let cFile : System.FilePath := pkg.dir / "Lean4J.c"
  let bridgeFile : System.FilePath := pkg.dir / "lean4j_bridge.c"
  let outLib : System.FilePath := pkg.dir / "liblean4j.so"

  let compileTask ← Cache.checkOrBuild outLib #[cFile, bridgeFile] fun _ => do
    let r ← proc {
      cmd := "leanc"
      args := #[
        "-shared", "-fPIC", "-fvisibility=default",
        s!"-I{(leanToolchain / "include").toString}",
        cFile.toString, bridgeFile.toString,
        s!"-L{leanLib}", "-lleanshared",
        s!"-Wl,-rpath,{leanLib}",
        "-o", outLib.toString
      ]
    }
    if r.exitCode != 0 then error s!"leanc failed: {r.stderr}"
    return outLib
  return compileTask
