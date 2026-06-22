# Tool locations are overridable from the environment — the nix devShell sets GRAALVM, M2,
# GVER, and puts lean/leanc on PATH (via elan). The defaults keep ambient setups working.
GRAALVM  ?= /opt/graalvm-community-openjdk-25.0.2+10.1
LEAN     ?= lean
LEANC    ?= leanc
LEAN_TC  ?= $(HOME)/.elan/toolchains/leanprover--lean4---v4.31.0
LEAN_LIB := $(LEAN_TC)/lib/lean
LEAN_INC := $(LEAN_TC)/include
JAVAC    := $(GRAALVM)/bin/javac
JAVA     := $(GRAALVM)/bin/java

LIB_SO   := lean-runtime/liblean4j.so
CLS_DIR  := target/classes
SMOKE_LOG := target/lean4j-smoke.log

.PHONY: all lean java lir-export jit-example leancremental polyglot json-example smoke debug bench clean

all: lean java

## --- Lean native library ---

lean-runtime/Lean4J.c: lean-runtime/Lean4J.lean
	$(LEAN) "--c=lean-runtime/Lean4J.c" -R lean-runtime lean-runtime/Lean4J.lean

$(LIB_SO): lean-runtime/Lean4J.c lean-runtime/lean4j_bridge.c
	$(LEANC) -shared -fPIC -fvisibility=default \
		-I$(LEAN_INC) \
		lean-runtime/Lean4J.c lean-runtime/lean4j_bridge.c \
		-L$(LEAN_LIB) -lleanshared -Wl,-rpath,$(LEAN_LIB) \
		-o $(LIB_SO)

lean: $(LIB_SO)

## --- GraalVM 25.0.2 (matches the JDK's Graal compiler → optimizing Truffle runtime) ---

M2   ?= $(HOME)/.m2/repository
GVER ?= 25.0.2

# Truffle/polyglot core + the OPTIMIZING runtime (truffle-runtime/compiler).
# These MUST be on --module-path so the runtime binds to jdk.graal.compiler and JITs.
# Single line on purpose: Make turns backslash-newline into a space and corrupts the path.
MOD_PATH := $(M2)/org/graalvm/truffle/truffle-api/$(GVER)/truffle-api-$(GVER).jar:$(M2)/org/graalvm/truffle/truffle-runtime/$(GVER)/truffle-runtime-$(GVER).jar:$(M2)/org/graalvm/truffle/truffle-compiler/$(GVER)/truffle-compiler-$(GVER).jar:$(M2)/org/graalvm/polyglot/polyglot/$(GVER)/polyglot-$(GVER).jar:$(M2)/org/graalvm/sdk/collections/$(GVER)/collections-$(GVER).jar:$(M2)/org/graalvm/sdk/word/$(GVER)/word-$(GVER).jar:$(M2)/org/graalvm/sdk/nativeimage/$(GVER)/nativeimage-$(GVER).jar:$(M2)/org/graalvm/sdk/jniutils/$(GVER)/jniutils-$(GVER).jar

# GraalJS (a guest Truffle language) + app classes go on the classpath;
# Truffle discovers classpath languages even when its runtime is a module.
JS_CP := $(M2)/org/graalvm/js/js-language/$(GVER)/js-language-$(GVER).jar:$(M2)/org/graalvm/regex/regex/$(GVER)/regex-$(GVER).jar:$(M2)/org/graalvm/shadowed/icu4j/$(GVER)/icu4j-$(GVER).jar

# Compile-time classpath (truffle-api + polyglot + sdk) and DSL annotation processor.
COMPILE_CP := $(M2)/org/graalvm/truffle/truffle-api/$(GVER)/truffle-api-$(GVER).jar:$(M2)/org/graalvm/polyglot/polyglot/$(GVER)/polyglot-$(GVER).jar:$(M2)/org/graalvm/sdk/collections/$(GVER)/collections-$(GVER).jar:$(M2)/org/graalvm/sdk/word/$(GVER)/word-$(GVER).jar:$(M2)/org/graalvm/sdk/nativeimage/$(GVER)/nativeimage-$(GVER).jar
DSL_PROC   := $(M2)/org/graalvm/truffle/truffle-dsl-processor/$(GVER)/truffle-dsl-processor-$(GVER).jar

JAVA_SRC := $(wildcard core/src/main/java/lean4j/*.java) \
            $(wildcard core/src/main/java/lean4j/truffle/*.java) \
            $(wildcard core/src/main/java/lean4j/lir/*.java) \
            $(wildcard core/src/main/java/lean4j/jit/*.java) \
            $(wildcard core/src/test/java/lean4j/*.java)

# Compiling does NOT need Lean or the native .so — the JIT path is pure JVM. (The FFM
# classes use java.lang.foreign, a compile-time-pure API; only the disabled v1 path
# System.load()s the .so at runtime.) So the runtime build needs only GraalVM + the jars.
$(CLS_DIR)/.compiled: $(JAVA_SRC) | $(CLS_DIR)
	cp -r core/src/main/resources/META-INF $(CLS_DIR)/
	$(JAVAC) --enable-preview --release 25 \
		-cp "$(COMPILE_CP)" \
		-processorpath "$(DSL_PROC)" \
		-d $(CLS_DIR) \
		$(JAVA_SRC) && touch $@

$(CLS_DIR):
	mkdir -p $(CLS_DIR)

java: $(CLS_DIR)/.compiled

## --- Run flags (module-path activates the optimizing/JIT Truffle runtime) ---

RUN_CP    := $(CLS_DIR):$(JS_CP)
RUN_FLAGS := --enable-preview \
             --module-path "$(MOD_PATH)" \
             --add-modules org.graalvm.truffle.runtime \
             --enable-native-access=ALL-UNNAMED \
             -Xss256m \
             -ea \
             -Dlean4j.lib=$(abspath $(LIB_SO))

# NOTE: v1 (FFM, lean4j) and v2 (tree-walker, lean4j-ir) are disabled as standalone
# demos — v3 (lean4j-jit) is the live path. Their code is kept: v2's language still
# backs the `bench` A/B comparison, and the FFM layer remains for reference.

## --- Lean IR pipeline (export IR → JSON) ---

lean-runtime/Lean4J.olean: lean-runtime/Lean4J.lean
	$(LEAN) -R lean-runtime -o lean-runtime/Lean4J.olean lean-runtime/Lean4J.lean

lean-runtime/lean4j_ir.json: lean-runtime/Lean4J.olean lean-runtime/LeanIRExport.lean
	LEAN_PATH=lean-runtime $(LEAN) -R lean-runtime lean-runtime/LeanIRExport.lean

lir-export: lean-runtime/lean4j_ir.json

# v3: PE-friendly Truffle nodes (JIT-compiled into native code)
jit-example: java lir-export
	$(JAVA) $(RUN_FLAGS) \
		-Dlean4j.ir=$(abspath lean-runtime/lean4j_ir.json) \
		-cp "$(RUN_CP)" \
		lean4j.JitExample

# Run real Leancremental library code (pure model) on the JIT runtime.
# Requires lean-runtime/leancremental_ir.json (exported via the Leancremental tree).
# IR of a lowered library; override to point anywhere, e.g. LC_IR=/path/to/lib_ir.json
LC_IR ?= $(abspath lean-runtime/leancremental_ir.json)

leancremental: java
	@test -f "$(LC_IR)" || { \
		echo "Missing $(LC_IR) — lower the library first (default devShell):"; \
		echo "  cd <Leancremental checkout> && LEAN4J_OUT=$(abspath lean-runtime) lake env lean Export.lean"; exit 1; }
	$(JAVA) $(RUN_FLAGS) \
		-Dlean4j.ir=$(LC_IR) \
		-cp "$(RUN_CP)" \
		lean4j.LeancrementalExample

# Polyglot demo: call a lowered library by its documented names via module.api,
# pass a Java lambda into a higher-order Lean function. (Uses the Leancremental example.)
polyglot: java
	@test -f "$(LC_IR)" || { \
		echo "Missing $(LC_IR) — lower the library first (default devShell):"; \
		echo "  cd <Leancremental checkout> && LEAN4J_OUT=$(abspath lean-runtime) lake env lean Export.lean"; exit 1; }
	$(JAVA) $(RUN_FLAGS) \
		-Dlean4j.ir=$(LC_IR) \
		-cp "$(RUN_CP)" \
		lean4j.LeanBindingDemo

# Standard-library example: call Lean's own JSON parser from the JVM. Lower it first with
#   lake build && LEAN4J_OUT=$(abspath lean-runtime) lake env lean examples/JsonExport.lean
JSON_IR ?= $(abspath lean-runtime/json_ir.json)
json-example: java
	@test -f "$(JSON_IR)" || { \
		echo "Missing $(JSON_IR) — lower Lean's JSON parser first (default devShell):"; \
		echo "  lake build && LEAN4J_OUT=$(abspath lean-runtime) lake env lean examples/JsonExport.lean"; exit 1; }
	$(JAVA) $(RUN_FLAGS) \
		-Dlean4j.ir=$(JSON_IR) \
		-cp "$(RUN_CP)" \
		lean4j.JsonExample

# Smoke test: prove the OPTIMIZING Truffle runtime actually engages (vs silently falling
# back to interpreter-only, which a GraalVM/Truffle version mismatch causes). Lowers the
# in-repo Lean4J.lean (self-contained — no external library), warms fib, and asserts a
# Truffle compilation event fires. Needs Lean (lir-export) → run in the default devShell.
smoke: java lir-export
	@echo ">> JIT smoke: warming fib, watching for Truffle compilation..."
	@$(JAVA) $(RUN_FLAGS) -Dpolyglot.engine.TraceCompilation=true \
		-Dlean4j.ir=$(abspath lean-runtime/lean4j_ir.json) \
		-cp "$(RUN_CP)" lean4j.LeanSmoke 2>&1 | tee $(SMOKE_LOG); \
	if grep -qiE "opt (done|start|queued)|\[engine\].*[Cc]ompilation" $(SMOKE_LOG); then \
		echo "SMOKE OK — Truffle JIT engaged (compilation observed)."; \
	else \
		echo "SMOKE FAIL — interpreter-only: no compilation. The GraalVM JDK and the"; \
		echo "  truffle-runtime $(GVER) jars are likely version-mismatched."; \
		exit 1; \
	fi

# Live source-level debugger demo: break on a Lean function, walk the stack, read the
# frame's variables — on UNMODIFIED library code. DEBUG_SRC points at the lowered
# library's checkout (its .lean sources); DEBUG_IR at its exported IR (+ src_ranges.json).
DEBUG_SRC ?= Leancremental
DEBUG_IR  ?= $(abspath lean-runtime/leancremental_ir.json)
debug: java
	@test -f "$(DEBUG_IR)" || { \
		echo "Missing $(DEBUG_IR) — lower the library first (with src_ranges.json for source):"; \
		echo "  cd <library checkout> && lake env lean Export.lean"; exit 1; }
	$(JAVA) $(RUN_FLAGS) \
		-Dlean4j.ir=$(DEBUG_IR) \
		-Dlean.src=$(DEBUG_SRC) \
		-cp "$(RUN_CP)" lean4j.LeanLiveDebugDemo

# A/B benchmark: v3 (PE nodes, JIT) vs v2 (tree-walker). Shows TraceCompilation.
bench: java lir-export
	$(JAVA) $(RUN_FLAGS) \
		-Dpolyglot.engine.TraceCompilation=true \
		-Dlean4j.ir=$(abspath lean-runtime/lean4j_ir.json) \
		-cp "$(RUN_CP)" \
		lean4j.JitBench

clean:
	rm -f lean-runtime/Lean4J.c lean-runtime/liblean4j.so
	rm -f lean-runtime/*.json lean-runtime/*.olean
	rm -rf target/
