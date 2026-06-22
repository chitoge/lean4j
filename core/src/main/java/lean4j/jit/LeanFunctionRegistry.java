package lean4j.jit;

import com.oracle.truffle.api.RootCallTarget;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Name → CallTarget table for all compiled Lean functions in a module.
 *
 * {@code targets}/{@code arities}/{@code initFnOf} are written only during the
 * single-threaded module build and read-only thereafter, so they're safe to read
 * from task worker threads. Only {@code globalCache} is written lazily at runtime
 * (and possibly concurrently) — see {@link #globalValue}.
 */
final class LeanFunctionRegistry {

    private static final Object UNINIT = new Object();

    private final Map<String, RootCallTarget> targets = new LinkedHashMap<>();
    private final Map<String, Integer> arities = new LinkedHashMap<>();
    /** Per-function: which IR params are erased (types/world) → host passes null for them. */
    private final Map<String, boolean[]> erasedOf = new LinkedHashMap<>();
    /** Module-level `initialize` globals: name → initializer fn name. */
    private final Map<String, String> initFnOf = new LinkedHashMap<>();
    /** Lazily-computed global values (run the initializer once on first access). */
    private final Map<String, Object> globalCache = new ConcurrentHashMap<>();
    private final Object globalInitLock = new Object();

    void register(String name, RootCallTarget target, int arity) {
        targets.put(name, target);
        arities.put(name, arity);
    }

    void register(String name, RootCallTarget target, int arity, boolean[] erased) {
        register(name, target, arity);
        erasedOf.put(name, erased);
    }

    boolean[] erasedOf(String name) { return erasedOf.get(name); }

    void registerInits(Map<String, String> inits) {
        initFnOf.putAll(inits);
        for (String g : inits.keySet()) globalCache.put(g, UNINIT);
    }

    boolean isGlobal(String name) {
        return initFnOf.containsKey(name);
    }

    /**
     * Value of a module-level `initialize` global. Runs the initializer once
     * (it returns {@code EST.Out.ok value}); the payload is cached and returned.
     */
    Object globalValue(String name) {
        Object v = globalCache.get(name);
        if (v != UNINIT) return v;
        // Double-checked init under a reentrant monitor. NOT computeIfAbsent: the
        // initializer runs guest code that may recursively init ANOTHER global on
        // this thread — synchronized re-enters cleanly; computeIfAbsent would throw.
        synchronized (globalInitLock) {
            v = globalCache.get(name);
            if (v != UNINIT) return v;
            RootCallTarget initFn = targets.get(initFnOf.get(name));
            if (initFn == null) throw new IllegalStateException("No initializer for global: " + name);
            Object result = initFn.call(new Object[]{ null }); // erased world token
            Object payload = (result instanceof lean4j.lir.LirObject o && o.fields().length > 0)
                    ? o.fields()[0] : result;
            globalCache.put(name, payload);
            return payload;
        }
    }

    RootCallTarget lookup(String name) {
        return targets.get(name);
    }

    int arity(String name) {
        Integer a = arities.get(name);
        if (a == null) throw new IllegalStateException("Unknown arity for: " + name);
        return a;
    }

    Set<String> names() {
        return targets.keySet();
    }
}
