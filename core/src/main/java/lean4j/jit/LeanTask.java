package lean4j.jit;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * A Lean {@code Task α} — a value being computed on a worker thread. Opaque to the
 * interpreter (flows through untouched, like a file Handle). {@code Task.get} blocks
 * on the underlying {@link Future}.
 */
public final class LeanTask {

    private final Future<Object> future;

    LeanTask(Future<Object> future) { this.future = future; }

    /** An already-completed task (Task.pure). */
    static LeanTask completed(Object value) {
        return new LeanTask(CompletableFuture.completedFuture(value));
    }

    /** Block until the task completes; surface a guest error as the runtime exception. */
    Object get() {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Task.get interrupted", e);
        }
    }

    @Override public String toString() { return "Task[" + (future.isDone() ? "done" : "running") + "]"; }
}
