package lean4j.truffle;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Lean 4 as a GraalVM Truffle polyglot language.
 *
 * Source strings are paths to Lean 4 shared libraries (.so).
 * Evaluating loads the library and returns a {@link LeanModule} TruffleObject
 * whose members are the exported Lean functions.
 *
 * Example (JavaScript on GraalVM):
 * <pre>
 *   const lean = Polyglot.eval("lean4j", "/path/to/liblean4j.so");
 *   console.log(lean.lean4j_add_uint32(21, 21)); // 42
 *   console.log(lean.lean4j_greet("World"));     // Hello from Lean 4, World!
 * </pre>
 */
@TruffleLanguage.Registration(
        id = LeanLanguage.ID,
        name = "Lean 4",
        version = "4.31.0",
        defaultMimeType = LeanLanguage.MIME_TYPE,
        characterMimeTypes = LeanLanguage.MIME_TYPE,
        contextPolicy = TruffleLanguage.ContextPolicy.SHARED
)
public final class LeanLanguage extends TruffleLanguage<LeanContext> {

    public static final String ID = "lean4j";
    public static final String MIME_TYPE = "application/x-lean4j";

    @Override
    protected LeanContext createContext(Env env) {
        return new LeanContext(this, env);
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        Source source = request.getSource();
        String libraryPath = source.getCharacters().toString().strip();

        return new RootNode(this) {
            @Override
            public Object execute(VirtualFrame frame) {
                return loadLibrary(libraryPath);
            }
        }.getCallTarget();
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Object loadLibrary(String libraryPath) {
        LeanContext ctx = getCurrentContext(LeanLanguage.class);

        // Initialize the Lean runtime (idempotent)
        ctx.initRuntime(libraryPath);

        // Load the manifest from <lib>_manifest.json
        Path soPath = Path.of(libraryPath);
        Path manifestPath = LeanManifest.manifestPath(soPath);

        LeanManifest manifest;
        try {
            manifest = LeanManifest.load(manifestPath);
        } catch (IOException e) {
            throw new RuntimeException(
                    "Cannot load Lean4J manifest at " + manifestPath +
                    ". Ensure a <name>_manifest.json exists alongside the shared library.", e);
        }

        return LeanModule.load(libraryPath, manifest);
    }

}
