package lean4j.lir;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

import lean4j.lir.LirIR.Decl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Lean 4 IR interpreter as a GraalVM Truffle language (id = "lean4j-ir").
 *
 * Source strings are paths to lean4j_ir.json files.
 * Evaluating loads all IR decls and returns a {@link LirModule} TruffleObject.
 * Functions execute entirely on the JVM — no FFM, no C heap.
 *
 * Example (Java):
 * <pre>
 *   Value mod = ctx.eval("lean4j-ir", "/path/to/lean4j_ir.json");
 *   int sum = mod.invokeMember("addUInt32", 21, 21).asInt(); // 42
 *   String msg = mod.invokeMember("greet", "World").asString(); // Hello from Lean 4, World!
 * </pre>
 */
@TruffleLanguage.Registration(
        id = LirLanguage.ID,
        name = "Lean4J IR",
        version = "0.1",
        defaultMimeType = LirLanguage.MIME_TYPE,
        characterMimeTypes = LirLanguage.MIME_TYPE,
        contextPolicy = TruffleLanguage.ContextPolicy.SHARED
)
public final class LirLanguage extends TruffleLanguage<LirLanguage.LirContext> {

    public static final String ID = "lean4j-ir";
    public static final String MIME_TYPE = "application/x-lean4j-ir";

    @Override
    protected LirContext createContext(Env env) { return new LirContext(); }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        String jsonPath = request.getSource().getCharacters().toString().strip();
        LirLanguage lang = this;

        return new RootNode(this) {
            @Override
            @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
            public Object execute(VirtualFrame frame) {
                return loadModule(lang, jsonPath);
            }
        }.getCallTarget();
    }

    private static LirModule loadModule(LirLanguage lang, String jsonPath) {
        try {
            String json = Files.readString(Path.of(jsonPath));
            Map<String, Decl> decls = LirLoader.load(json);
            return LirModule.load(lang, decls);
        } catch (IOException e) {
            throw new RuntimeException("Cannot read lean4j_ir.json at: " + jsonPath, e);
        }
    }

    public static final class LirContext {
        LirContext() {}
    }
}
