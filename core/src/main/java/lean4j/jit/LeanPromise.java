package lean4j.jit;

import java.util.concurrent.CompletableFuture;

/**
 * A Lean {@code IO.Promise α} — a one-shot async cell resolved once, readable as a
 * {@code Task} that completes when resolved. Backed by a {@link CompletableFuture}.
 * Opaque to the interpreter (flows through untouched, like a Task or file Handle).
 */
public final class LeanPromise {
    final CompletableFuture<Object> future = new CompletableFuture<>();
}
