package lean4j.jit;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;

import lean4j.lir.LirObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

import static lean4j.jit.LeanRT.*;

/**
 * Effectful / runtime builtins: ST mutable refs, stdout/stderr streams, real
 * multi-threaded Task execution + read/write mutexes, the clock, and panic.
 * (Filesystem lives in {@link LeanFS}.) Lean's world token is erased at runtime, so
 * these run for effect in IR let-order; erased/world args arrive as null and are
 * dropped by {@code meaningful}.
 */
final class EffectOps {

    private EffectOps() {}

    /** Default stdout/stderr as full host-backed Lean IO.FS.Stream objects. */
    private static final Object STDOUT = makeOutStream(false);
    private static final Object STDERR = makeOutStream(true);
    // The *current* streams are thread-local (matching Lean), so withStdin/withStdout
    // redirect-and-restore work and setStdX returns the old stream.
    private static final ThreadLocal<Object> CUR_STDOUT = ThreadLocal.withInitial(() -> STDOUT);
    private static final ThreadLocal<Object> CUR_STDERR = ThreadLocal.withInitial(() -> STDERR);
    private static final ThreadLocal<Object> CUR_STDIN  = ThreadLocal.withInitial(() -> makeInStream(hostIn()));

    // ── Task worker pool (lazily created from the Truffle Env; one context per run) ──
    private static volatile TruffleLanguage.Env taskEnv;
    private static volatile LeanTaskPool taskPool;

    static void setEnv(TruffleLanguage.Env env) { taskEnv = env; }

    static synchronized void shutdownPool() {
        if (taskPool != null) { taskPool.shutdown(); taskPool = null; }
    }

    private static LeanTaskPool pool() {
        LeanTaskPool p = taskPool;
        if (p == null) {
            synchronized (EffectOps.class) {
                p = taskPool;
                if (p == null) { p = new LeanTaskPool(taskEnv); taskPool = p; }
            }
        }
        return p;
    }

    static void register(Map<String, Function<Object[], Object>> p) {
        // ── ST mutable refs ──
        p.put("ST.Prim.mkRef",    a -> new LeanRef(meaningful(a)[0]));
        // Ref.get returns the contents while the ref keeps holding them → the result
        // is now shared (RC≥2). Mark it so FBIP reuse copies, not mutates.
        p.put("ST.Prim.Ref.get",  a -> markShared(((LeanRef) meaningful(a)[0]).value));
        // Ref.take transfers ownership (the value leaves the ref) → not shared.
        p.put("ST.Prim.Ref.take", a -> ((LeanRef) meaningful(a)[0]).value);
        p.put("ST.Prim.Ref.set",  a -> { Object[] m = meaningful(a); ((LeanRef) m[0]).value = m[1]; return UNIT; });
        p.put("ST.Prim.Ref.reset", a -> UNIT);

        // ── stdout/stderr (host-backed; applying putStr writes to System.out/err) ──
        p.put("IO.getStdin",  a -> CUR_STDIN.get());
        p.put("IO.getStdout", a -> CUR_STDOUT.get());
        p.put("IO.getStderr", a -> CUR_STDERR.get());
        p.put("IO.setStdin",  a -> { Object old = CUR_STDIN.get();  CUR_STDIN.set(meaningful(a)[0]);  return old; });
        p.put("IO.setStdout", a -> { Object old = CUR_STDOUT.get(); CUR_STDOUT.set(meaningful(a)[0]); return old; });
        p.put("IO.setStderr", a -> { Object old = CUR_STDERR.get(); CUR_STDERR.set(meaningful(a)[0]); return old; });

        // ── env, clock + platform (BaseIO → raw, not EST.Out-wrapped) ──
        p.put("IO.getEnv", a -> { String v = System.getenv(str(meaningful(a)[0]));
            return v == null ? new LirObject("Option.none", 0, new Object[0])
                             : new LirObject("Option.some", 1, new Object[]{ v }); });
        p.put("IO.monoNanosNow",  a -> System.nanoTime());
        p.put("IO.monoMsNow",     a -> System.nanoTime() / 1_000_000L);
        p.put("IO.getNumHeartbeats", a -> 0L);
        // dbgSleep ms (fn : Unit → α) : α — sleep, then force the thunk (no world token).
        p.put("dbgSleep", a -> { Object[] m = meaningful(a);
            try { Thread.sleep(asLong(m[0])); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return LeanApplyNodes.applyUncached(m[1], new Object[]{ UNIT }); });
        p.put("System.Platform.getNumBits",   a -> 64L);
        p.put("System.Platform.getIsWindows", a -> 0L);

        // ── Std.Internal.UV.System: synchronous libuv syscalls (no event loop) ──
        String UV = "Std.Internal.UV.System.";
        p.put(UV + "cwd",             a -> ok(System.getProperty("user.dir")));
        p.put(UV + "exePath",         a -> ok(ProcessHandle.current().info().command().orElse("lean4j")));
        p.put(UV + "getProcessTitle", a -> ok("lean4j"));
        p.put(UV + "hrtime",          a -> ok(System.nanoTime()));
        p.put(UV + "osHomedir",       a -> ok(System.getProperty("user.home")));
        p.put(UV + "osTmpdir",        a -> ok(System.getProperty("java.io.tmpdir")));
        p.put(UV + "osGetPid",        a -> ok(ProcessHandle.current().pid()));
        p.put(UV + "osGetPpid",       a -> ok(ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(0L)));
        p.put(UV + "osGetHostname",   a -> ok(hostname()));
        p.put(UV + "availableMemory", a -> ok(Runtime.getRuntime().maxMemory()));
        p.put(UV + "freeMemory",      a -> ok(Runtime.getRuntime().freeMemory()));
        p.put(UV + "totalMemory",     a -> ok(Runtime.getRuntime().totalMemory()));
        p.put(UV + "constrainedMemory", a -> ok(0L));
        p.put(UV + "uptime",          a -> ok(java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000L));
        p.put(UV + "osUname",         a -> ok(new LirObject(UV + "UnameInfo.mk", 0, new Object[]{
            System.getProperty("os.name"), System.getProperty("os.version"),
            System.getProperty("os.version"), System.getProperty("os.arch") })));
        p.put(UV + "osEnviron",       a -> {
            LeanArray arr = LeanArray.empty(System.getenv().size());
            for (Map.Entry<String, String> e : System.getenv().entrySet())
                arr = arr.push(new LirObject("Prod.mk", 0, new Object[]{ e.getKey(), e.getValue() }));
            return ok(arr);
        });

        // ── Task: real parallelism — asTask spawns on the pool, Task.get blocks ──
        p.put("BaseIO.asTask", a -> {
            Object thunk = meaningful(a)[0];
            // Soundness: mark everything the task captures as shared, so any FBIP
            // reuse copies instead of mutating in place — no concurrent in-place
            // mutation of an object visible to both the task and the spawner.
            markCaptured(thunk);
            Future<Object> f = pool().submit(() -> LeanApplyNodes.applyUncached(thunk, new Object[]{ null }));
            return new LeanTask(f);
        });
        p.put("Task.get", a -> {
            Object t = m1(a);
            return (t instanceof LeanTask lt) ? lt.get() : t; // tolerate a non-Task (Task.pure etc.)
        });
        p.put("Task.pure", a -> LeanTask.completed(m1(a)));
        // Task.map f x : run f on x's result as a dependent task. f and x are found by
        // type (the IR may also pass erased prio/sync args alongside).
        p.put("Task.map", a -> {
            Object[] m = meaningful(a);
            LeanClosure f = null; LeanTask x = null;
            for (Object o : m) {
                if (o instanceof LeanClosure c) f = c;
                else if (o instanceof LeanTask lt) x = lt;
            }
            final LeanClosure ff = f; final LeanTask xx = x;
            return new LeanTask(pool().submit(() ->
                LeanApplyNodes.applyUncached(ff, new Object[]{ xx != null ? xx.get() : null })));
        });

        // ── IO.Promise: one-shot async cell; result? is a Task that completes on resolve ──
        p.put("IO.Promise.new", a -> new LeanPromise());
        p.put("IO.Promise.resolve", a -> { // resolve (value) (promise) — find promise by type
            Object[] m = meaningful(a);
            LeanPromise prom = null; Object value = null;
            for (Object o : m) { if (o instanceof LeanPromise lp) prom = lp; else if (value == null) value = o; }
            if (prom != null) prom.future.complete(value);
            return UNIT;
        });
        p.put("IO.Promise.result?", a -> { // → Task (Option α): yields some(value) when resolved
            LeanPromise prom = (LeanPromise) firstOf(meaningful(a), LeanPromise.class);
            return new LeanTask(prom.future.thenApply(v ->
                new LirObject("Option.some", 1, new Object[]{ v })));
        });

        // ── shared read/write mutex (java.util.concurrent) ──
        p.put("Std.BaseSharedMutex.new",         a -> new ReentrantReadWriteLock());
        p.put("Std.BaseSharedMutex.read",        a -> { ((ReentrantReadWriteLock) m1(a)).readLock().lock();    return UNIT; });
        p.put("Std.BaseSharedMutex.write",       a -> { ((ReentrantReadWriteLock) m1(a)).writeLock().lock();   return UNIT; });
        p.put("Std.BaseSharedMutex.unlockRead",  a -> { ((ReentrantReadWriteLock) m1(a)).readLock().unlock();  return UNIT; });
        p.put("Std.BaseSharedMutex.unlockWrite", a -> { ((ReentrantReadWriteLock) m1(a)).writeLock().unlock(); return UNIT; });

        // ── panic ──
        p.put("panicCore", a -> { throw new RuntimeException("Lean panic: "
            + (meaningful(a).length > 0 ? str(meaningful(a)[meaningful(a).length - 1]) : "")); });
    }

    // IO.FS.Stream.mk fields (with the IO/world expansion): [0] flush (IO Unit, arity 1),
    // [1] read (USize→IO ByteArray, arity 2), [2] write (ByteArray→IO Unit, arity 2),
    // [3] getLine (IO String, arity 1), [4] putStr (String→IO Unit, arity 2),
    // [5] isTty (BaseIO Bool, arity 1, RAW — not EST.Out-wrapped).

    /** A writable stream over System.out/err (read/getLine are inert). */
    private static Object makeOutStream(boolean err) {
        Object[] f = new Object[6];
        f[0] = LeanClosure.ofJava(a -> { (err ? System.err : System.out).flush(); return ok(UNIT); }, 1, "stream.flush");
        f[1] = LeanClosure.ofJava(a -> ok(bytesToArray(new byte[0])), 2, "stream.read");
        f[2] = LeanClosure.ofJava(a -> { writeOut(err, new String(arrayToBytes((LeanArray) a[0]), StandardCharsets.UTF_8)); return ok(UNIT); }, 2, "stream.write");
        f[3] = LeanClosure.ofJava(a -> ok(""), 1, "stream.getLine");
        f[4] = LeanClosure.ofJava(a -> { writeOut(err, str(a[0])); return ok(UNIT); }, 2, "stream.putStr");
        f[5] = LeanClosure.ofJava(a -> isTty(), 1, "stream.isTty");
        return new LirObject("IO.FS.Stream.mk", 0, f);
    }

    /** A readable stream over a host InputStream (the Context's stdin); writes error. */
    private static Object makeInStream(InputStream in) {
        Object[] f = new Object[6];
        f[0] = LeanClosure.ofJava(a -> ok(UNIT), 1, "stdin.flush");
        f[1] = LeanClosure.ofJava(a -> { int n = (int) asLong(a[0]);
            try { byte[] buf = new byte[n]; int r = in.read(buf);
                  return ok(bytesToArray(r <= 0 ? new byte[0] : java.util.Arrays.copyOf(buf, r))); }
            catch (IOException e) { return ioErr(e); } }, 2, "stdin.read");
        f[2] = LeanClosure.ofJava(a -> ioErr(new IOException("stdin is not writable")), 2, "stdin.write");
        f[3] = LeanClosure.ofJava(a -> { try { return ok(readLine(in)); } catch (IOException e) { return ioErr(e); } }, 1, "stdin.getLine");
        f[4] = LeanClosure.ofJava(a -> ioErr(new IOException("stdin is not writable")), 2, "stdin.putStr");
        f[5] = LeanClosure.ofJava(a -> isTty(), 1, "stdin.isTty");
        return new LirObject("IO.FS.Stream.mk", 0, f);
    }

    /** Read bytes up to and including '\n' (or EOF) and UTF-8 decode; "" at EOF. */
    @TruffleBoundary
    private static String readLine(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) { bos.write(c); if (c == '\n') break; }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static InputStream hostIn() { return taskEnv != null ? taskEnv.in() : System.in; }
    private static String hostname() {
        try { return java.net.InetAddress.getLocalHost().getHostName(); }
        catch (java.net.UnknownHostException e) { return "localhost"; }
    }
    /** Real isatty: true only when the JVM is attached to a terminal (System.console). */
    private static long isTty() { return System.console() != null ? 1L : 0L; }
    private static Object ok(Object v) { return new LirObject("EST.Out.ok", 0, new Object[]{ v }); }
    private static Object ioErr(IOException e) {
        Object io = new LirObject("IO.Error.userError", 18, new Object[]{ String.valueOf(e.getMessage()) });
        return new LirObject("EST.Out.error", 1, new Object[]{ io });
    }

    @TruffleBoundary
    private static void writeOut(boolean err, String s) {
        (err ? System.err : System.out).print(s);
    }

    /** First meaningful arg of a given runtime type (for type-directed arg resolution). */
    private static Object firstOf(Object[] m, Class<?> cls) {
        for (Object o : m) if (cls.isInstance(o)) return o;
        throw new IllegalStateException("no " + cls.getSimpleName() + " arg among " + java.util.Arrays.toString(m));
    }

    /** Mark a task thunk's captured values shared (forces copy-on-write for FBIP). */
    private static void markCaptured(Object thunk) {
        if (thunk instanceof LeanClosure c) {
            for (Object captured : c.captured) markShared(captured);
        }
    }
}
