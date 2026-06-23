{
  description = "lean4j — run compiled Lean 4 IR on GraalVM Truffle (lean4j-jit)";

  # Verified: `nix develop .#runtime` builds GraalVM CE 25.0.2 (nixos-unstable) + the
  # vendored Truffle 25.0.2 jars; `make java` + `make smoke` (JIT engages, Tier-2 OSR
  # observed) + the full `make leancremental` suite all pass with no ambient tooling.
  # The version-match that makes the optimizing runtime engage is exact; `make smoke`
  # stays as the loud guard against a future nixpkgs GraalVM bump drifting it.

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f (import nixpkgs { inherit system; }));

      gver = "25.0.2"; # GraalVM CE / Truffle version — MUST match the GraalVM JDK below.

      # Truffle / polyglot / GraalJS / tooling jars, version-locked to GraalVM CE 25.0.2.
      # Fetched from Maven Central; hashes computed offline from a known-good local ~/.m2.
      jarList = [
        { p = "org/graalvm/truffle";  a = "truffle-api";           h = "sha256-1yJp2G//3bCe1itWWbNVoJ6HbVt34Lc3qZLy01nPWsw="; }
        { p = "org/graalvm/truffle";  a = "truffle-runtime";       h = "sha256-Yd3rcaa1T9+bp9INqplLpUM3Iuva+UkZ+4U6+qsCdiI="; }
        { p = "org/graalvm/truffle";  a = "truffle-compiler";      h = "sha256-50OE74gteGf+Ar5XkHkJzZE392OzyMUkLN0+p5cLaTM="; }
        { p = "org/graalvm/truffle";  a = "truffle-dsl-processor"; h = "sha256-VgLes9ViC2dqdGUcjgxgtigUNIyTj8ZPZPSLQx2IDqM="; }
        { p = "org/graalvm/polyglot"; a = "polyglot";              h = "sha256-Xl+9yIqNLaBAUlhqOJS67X+ZNbcB/W0Px8cigvonL7c="; }
        { p = "org/graalvm/sdk";      a = "collections";           h = "sha256-JI4Iw84C5HdG9Gl9ASf3FCrdmUdK4x596pXn2pgjqs8="; }
        { p = "org/graalvm/sdk";      a = "word";                  h = "sha256-HmFsSN7tO1+FUieojM/pxacfDq1kIvnEHNhT9YSr+og="; }
        { p = "org/graalvm/sdk";      a = "nativeimage";           h = "sha256-qzDep21C3XjxdV+qBirw8LhT3ys6qskqD5EufuvsOXE="; }
        { p = "org/graalvm/sdk";      a = "jniutils";              h = "sha256-sIfYsCMuLhmmwzAFQd3EXR6D638r54IBmMT0KsJLFs4="; }
        { p = "org/graalvm/js";       a = "js-language";           h = "sha256-rQdVJuwsjlFNq4Ys9hmzzMSvQLI/MXvFwXcIS6tHk80="; }
        # GraalPy (large: ~113 MB) — only for the `make polyglot-py` test of the Python guest path.
        { p = "org/graalvm/python";   a = "python-language";       h = "sha256-O+3cPAWmVzQV8XTqaPq7Dx/rGZlIs7H4NWJUwK6iVKA="; }
        { p = "org/graalvm/python";   a = "python-resources";      h = "sha256-7AJYAnlKOMjP8n0QuR6QKvQL47D2FIt2EmiakruxBA4="; }
        { p = "org/graalvm/python";   a = "python-embedding";      h = "sha256-sU099V2+fHOTP5Z2EUUVPydSx4diCa4NtkyuwimmY3Y="; }
        { p = "org/graalvm/regex";    a = "regex";                 h = "sha256-GB9+LgSH6BZ8zPQcrXbNUj9uBTjvHITaAPWrxvN+mqE="; }
        { p = "org/graalvm/shadowed"; a = "icu4j";                 h = "sha256-5TOszG1o/7m/qHqhhwKYSlHPbE0SJgXOPBY0AgX9z9Y="; }
        { p = "org/graalvm/tools";    a = "profiler-tool";         h = "sha256-RrtLkYU6voRpciidwE4qbMbwqnpJBtXNo2OOE+rDyk8="; }
        { p = "org/graalvm/tools";    a = "coverage-tool";         h = "sha256-H0s5IAKNEhNYGxbEA2UpEf9uHVOypw4aOI+pemwfyhQ="; }
        { p = "org/graalvm/tools";    a = "chromeinspector-tool";  h = "sha256-LB0sPbtNyXvceXYgYtIKOUVadwrCPilsFKRd0GwsnYA="; }
        { p = "org/graalvm/shadowed"; a = "json";                  h = "sha256-fPSRbB0XZ6ppPL0M2rvx517DlnVp/Pjc7s/aKoC9R88="; }
      ];
    in {
      devShells = forAllSystems (pkgs:
        let
          lib = pkgs.lib;

          # Reassemble a Maven-layout repo from the vendored jars, so the Makefile's
          # $(M2)/<group-path>/<artifact>/<ver>/<jar> references resolve unchanged.
          m2repo = pkgs.runCommand "lean4j-m2-${gver}" { } (''
            mkdir -p $out
          '' + lib.concatMapStrings (j:
            let jar = pkgs.fetchurl {
                  url = "https://repo1.maven.org/maven2/${j.p}/${j.a}/${gver}/${j.a}-${gver}.jar";
                  hash = j.h;
                };
            in ''
              mkdir -p "$out/${j.p}/${j.a}/${gver}"
              cp "${jar}" "$out/${j.p}/${j.a}/${gver}/${j.a}-${gver}.jar"
            '') jarList);

          # GraalVM CE — verified 25.0.2 on nixos-unstable, matching the truffle-runtime jars
          # above. The optimizing Truffle runtime only engages on an exact match; `make smoke`
          # is the loud guard if a future nixpkgs bump drifts the version.
          graalvm = pkgs.graalvmPackages.graalvm-ce;

          banner = ''
            export GRAALVM="${graalvm}"
            export M2="${m2repo}"
            export GVER="${gver}"
            echo "lean4j devShell"
            echo "  GraalVM : $("$GRAALVM/bin/java" -version 2>&1 | head -1)"
            echo "  Truffle : $M2 (vendored jars, ${gver})"
          '';
        in {
          # Full end-to-end toolchain. The two halves:
          #   • RUN compiled IR — pure JVM: GraalVM + the vendored Truffle jars.
          #   • LOWER a Lean library to IR + the surface/getConstInfo work — needs Lean,
          #     provided via `elan`, which reads each project's `lean-toolchain` and installs
          #     the EXACT version (4.31.0 here, but correct for any library — unlike a fixed
          #     nixpkgs.lean4, which can't match a library's version-locked oleans).
          # gcc is for `lake build` (compiling a library's oleans before export).
          default = pkgs.mkShell {
            packages = [ graalvm pkgs.gnumake pkgs.rsync pkgs.elan pkgs.gcc ];
            shellHook = banner + ''
              echo "  Lean    : elan (auto-installs each project's pinned toolchain — 4.31.0 here)"
              echo ""
              echo "  build  make java   ·   run  make leancremental   ·   verify JIT  make smoke"
              echo "  export IR:  cd <lean-library> && lake env lean Export.lean"
              echo ""
              echo "  NixOS note: elan fetches upstream prebuilt Lean binaries — if they fail to"
              echo "  run, enable programs.nix-ld (or use an FHS shell) so the dynamic loader resolves."
            '';
          };

          # Lighter subset for users who only RUN pre-generated IR (no lowering): drops Lean.
          runtime = pkgs.mkShell {
            packages = [ graalvm pkgs.gnumake pkgs.rsync ];
            shellHook = banner + ''
              echo "  (runtime-only shell — no Lean; use the default shell to lower libraries)"
            '';
          };
        });
    };

  # ── Verify (run on a machine with network) ───────────────────────────────────────────
  #   nix develop          # runtime toolchain (GraalVM + Truffle jars), no Lean
  #   make java            # compile the interpreter
  #   make smoke           # asserts the Truffle JIT actually engages (the key check)
  #   make leancremental   # full suite
  #
  # If `make smoke` fails with "interpreter-only", the GraalVM JDK and the ${gver} Truffle
  # jars are version-mismatched — pin nixpkgs to a revision whose `graalvm-ce` is 25.0.2.
}
