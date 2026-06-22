package lean4j.jit;

import com.oracle.truffle.api.TruffleLanguage;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The worker pool backing Lean {@code Task}/{@code IO.asTask}. Threads are created via
 * {@link TruffleLanguage.Env#createThread(Runnable)} so they are **polyglot threads**
 * attached to the context — only such threads may execute guest CallTargets. Sized to
 * the available processors. Must be shut down (and its threads joined) before the
 * context is disposed; {@link JitLanguage#finalizeContext} does that.
 */
final class LeanTaskPool {

    private final ExecutorService exec;

    LeanTaskPool(TruffleLanguage.Env env) {
        int n = Math.max(2, Runtime.getRuntime().availableProcessors());
        ThreadFactory tf = new ThreadFactory() {
            private int counter = 0;
            @Override public synchronized Thread newThread(Runnable r) {
                Thread t = env.createThread(r);   // polyglot thread, enters the context
                t.setName("lean4j-task-" + (counter++));
                return t;
            }
        };
        // Fixed pool: workers are long-lived (env.createThread runs the pool's worker
        // loop, so each thread stays attached to the context until shutdown).
        this.exec = new ThreadPoolExecutor(n, n, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), tf);
    }

    Future<Object> submit(Callable<Object> task) {
        return exec.submit(task);
    }

    /** Stop accepting work and join the worker threads (before context dispose). */
    void shutdown() {
        exec.shutdown();
        try {
            if (!exec.awaitTermination(60, TimeUnit.SECONDS)) exec.shutdownNow();
        } catch (InterruptedException e) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
