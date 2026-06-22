package lean4j.truffle;

import com.oracle.truffle.api.TruffleLanguage;
import lean4j.LeanRuntime;

/**
 * Per-context state for the Lean 4 Truffle language.
 * Initializes the Lean runtime once per JVM (guarded by LeanRuntime.INITIALIZED).
 */
public final class LeanContext {

    private final LeanLanguage language;
    private final TruffleLanguage.Env env;

    LeanContext(LeanLanguage language, TruffleLanguage.Env env) {
        this.language = language;
        this.env = env;
    }

    LeanLanguage getLanguage() { return language; }
    TruffleLanguage.Env getEnv() { return env; }

    void initRuntime(String libraryPath) {
        if (!LeanRuntime.isInitialized()) {
            LeanRuntime.initialize(libraryPath);
        }
    }
}
