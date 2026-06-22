package lean4j.jit;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

import lean4j.lir.LirIR.Decl;
import lean4j.lir.LirLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Lean 4 IR as a JIT-compiled GraalVM Truffle language (id = "lean4j-jit").
 *
 * Source strings are paths to lean4j_ir.json. Unlike the tree-walking "lean4j-ir"
 * language, this builds real Truffle AST nodes per function, so hot Lean functions
 * are partial-evaluated and JIT-compiled to native code on the optimizing runtime.
 *
 * Example (Java):
 * <pre>
 *   Value mod = ctx.eval("lean4j-jit", "/path/to/lean4j_ir.json");
 *   long f = mod.invokeMember("fib", 30L).asLong(); // JIT-compiled after warmup
 * </pre>
 */
@TruffleLanguage.Registration(
        id = JitLanguage.ID,
        name = "Lean4J JIT",
        version = "0.1",
        defaultMimeType = JitLanguage.MIME_TYPE,
        characterMimeTypes = JitLanguage.MIME_TYPE,
        contextPolicy = TruffleLanguage.ContextPolicy.SHARED
)
@com.oracle.truffle.api.instrumentation.ProvidedTags({
        com.oracle.truffle.api.instrumentation.StandardTags.RootTag.class,
        com.oracle.truffle.api.instrumentation.StandardTags.RootBodyTag.class,
        com.oracle.truffle.api.instrumentation.StandardTags.StatementTag.class
})
public final class JitLanguage extends TruffleLanguage<JitLanguage.JitContext> {

    public static final String ID = "lean4j-jit";
    public static final String MIME_TYPE = "application/x-lean4j-jit";

    @Override
    protected JitContext createContext(Env env) {
        // Hand the Env to the effect layer so it can spawn polyglot worker threads
        // for Lean Tasks. (Single embedded context per run in our harness.)
        EffectOps.setEnv(env);
        return new JitContext();
    }

    /** Allow guest execution on multiple JVM threads — required for real Task parallelism. */
    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        return true;
    }

    /** Join + shut down the Task worker pool before the context is disposed. */
    @Override
    protected void finalizeContext(JitContext context) {
        EffectOps.shutdownPool();
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        String jsonPath = request.getSource().getCharacters().toString().strip();
        JitLanguage lang = this;
        return new RootNode(this) {
            @Override
            @TruffleBoundary
            public Object execute(VirtualFrame frame) {
                return loadModule(lang, jsonPath);
            }
        }.getCallTarget();
    }

    private static JitModule loadModule(JitLanguage lang, String jsonPath) {
        try {
            Path irPath = Path.of(jsonPath);
            String json = Files.readString(irPath);
            Map<String, Decl> decls = LirLoader.load(json);
            Map<String, String> inits = LirLoader.loadInits(json);
            return JitModule.load(lang, decls, inits, new LeanSourceMap(irPath));
        } catch (IOException e) {
            throw new RuntimeException("Cannot read lean4j_ir.json at: " + jsonPath, e);
        }
    }

    public static final class JitContext {
        JitContext() {}
    }
}
