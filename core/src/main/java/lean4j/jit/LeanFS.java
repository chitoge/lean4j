package lean4j.jit;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import lean4j.lir.LirObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/**
 * The filesystem component of the Lean runtime: a {@code java.nio}-backed
 * implementation of Lean's {@code IO.FS}/{@code System.FilePath} externs.
 *
 * Boundary: this class owns ALL filesystem behaviour AND the construction of the
 * Lean FS data structures it must return ({@code Metadata}, {@code DirEntry},
 * opaque {@code Handle}). It speaks only the extern ABI — {@code Object[]} in,
 * an {@code EST.Out.ok|error} value out — and depends on the rest of the runtime
 * only through {@link LeanRT} (arg helpers), {@link LirObject}/{@link LeanArray}
 * (heap values), and {@link LeanRT#UNIT}. Nothing here reaches into the
 * interpreter; {@link LeanBuiltins} wires these in via {@link #register}.
 *
 * Layouts (reverse-engineered from the Lean IR):
 *   FilePath  ≡ String (single-field struct, unwrapped at runtime)
 *   Metadata  = obj[accessed, modified] + scalars{byteSize@0:u64, numLinks@8:u64, type@16:u8}
 *   FileType  enum: dir=0, file=1, symlink=2, other=3
 *   DirEntry  = obj[root, fileName];  .path = join(root, fileName)
 *   Handle    opaque — we wrap a {@link RandomAccessFile}
 */
final class LeanFS {

    private LeanFS() {}

    /** Register every filesystem extern into the builtin table. */
    static void register(Map<String, Function<Object[], Object>> prim) {
        prim.put("IO.FS.createTempDir",          LeanFS::createTempDir);
        prim.put("IO.FS.createDir",              LeanFS::createDir);
        prim.put("IO.FS.removeFile",             LeanFS::removeFile);
        prim.put("IO.FS.removeDir",              LeanFS::removeDir);
        prim.put("IO.FS.rename",                 LeanFS::rename);
        prim.put("IO.FS.Handle.mk",              LeanFS::handleMk);
        prim.put("IO.FS.Handle.putStr",          LeanFS::handlePutStr);
        prim.put("IO.FS.Handle.read",            LeanFS::handleRead);
        prim.put("IO.FS.Handle.getLine",         LeanFS::handleGetLine);
        prim.put("IO.FS.Handle.write",           LeanFS::handleWrite);
        prim.put("IO.FS.Handle.flush",           LeanFS::handleFlush);
        prim.put("IO.FS.Handle.rewind",          LeanFS::handleRewind);
        prim.put("IO.FS.Handle.truncate",        LeanFS::handleTruncate);
        prim.put("IO.FS.Handle.isTty",           a -> 0L);                // BaseIO Bool (a file is not a tty)
        prim.put("IO.FS.realPath",               LeanFS::realPath);
        prim.put("IO.currentDir",                a -> ok(System.getProperty("user.dir")));
        prim.put("IO.appPath",                   a -> ok(ProcessHandle.current().info().command().orElse("lean4j")));
        prim.put("IO.getRandomBytes",            LeanFS::getRandomBytes);
        prim.put("System.FilePath.readDir",      LeanFS::readDir);
        prim.put("System.FilePath.metadata",     LeanFS::metadata);
        prim.put("System.FilePath.symlinkMetadata", LeanFS::metadata);
        // getPID is BaseIO UInt32 (cannot fail) → returns the raw value, not EST.Out-wrapped.
        prim.put("IO.Process.getPID",            a -> ProcessHandle.current().pid() & 0xFFFF_FFFFL);
        prim.put("IO.Process.spawn",             LeanFS::procSpawn);
        prim.put("IO.Process.Child.wait",        LeanFS::procWait);
        prim.put("IO.Process.Child.takeStdin",   LeanFS::procTakeStdin);
        prim.put("IO.Process.exit",              LeanFS::procExit);
    }

    // ── subprocesses (java.lang.ProcessBuilder) ──

    private static final String CHILD_CTOR = "_private.Init.System.IO.0.IO.Process.Child.mk";
    /** Holder for the OS process, carried as a hidden 4th field of the Child struct. */
    static final class Proc { final Process p; Proc(Process p) { this.p = p; } }

    // Apply a Stdio config (piped=0, inherit=1, null=2) to one stream of the builder.
    private static ProcessBuilder.Redirect redirectFor(int kind, boolean input) {
        return switch (kind) {
            case 1 -> ProcessBuilder.Redirect.INHERIT;
            case 2 -> input ? ProcessBuilder.Redirect.from(new java.io.File("/dev/null"))
                            : ProcessBuilder.Redirect.DISCARD;
            default -> null;  // piped → ProcessBuilder default (a pipe)
        };
    }

    @TruffleBoundary
    private static Object procSpawn(Object[] a) {
        // SpawnArgs.mk: 5 object fields [stdioConfig, cmd, args, cwd, env] + 2 scalar bytes
        // [inheritEnv@0, setsid@1]. StdioConfig.mk: 0 object fields + 3 scalar bytes — the
        // Stdio enum (piped=0, inherit=1, null=2) is a packed u8, NOT an object field.
        LirObject sa = (LirObject) LeanRT.meaningful(a)[0];
        Object[] f = sa.fields();
        LirObject cfg = (LirObject) f[0];
        int inK  = (int) LeanRT.asLong(cfg.getScalar(0));
        int outK = (int) LeanRT.asLong(cfg.getScalar(1));
        int errK = (int) LeanRT.asLong(cfg.getScalar(2));
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(LeanRT.str(f[1]));                          // cmd
        LeanArray argv = (LeanArray) f[2];                      // args
        for (int i = 0; i < argv.size(); i++) command.add(LeanRT.str(argv.get(i)));
        ProcessBuilder pb = new ProcessBuilder(command);
        if (f[3] instanceof LirObject cwd && cwd.cidx() == 1)   // cwd : Option FilePath
            pb.directory(new java.io.File(LeanRT.str(cwd.fields()[0])));
        if (LeanRT.asLong(sa.getScalar(0)) == 0) pb.environment().clear();  // inheritEnv=false → cleared
        LeanArray env = (LeanArray) f[4];                        // env : Array (String × Option String)
        for (int i = 0; i < env.size(); i++) {
            Object[] kv = ((LirObject) env.get(i)).fields();
            String k = LeanRT.str(kv[0]);
            if (kv[1] instanceof LirObject ov && ov.cidx() == 1) pb.environment().put(k, LeanRT.str(ov.fields()[0]));
            else pb.environment().remove(k);                     // None → unset
        }
        ProcessBuilder.Redirect ri = redirectFor(inK, true), ro = redirectFor(outK, false), re = redirectFor(errK, false);
        if (ri != null) pb.redirectInput(ri);
        if (ro != null) pb.redirectOutput(ro);
        if (re != null) pb.redirectError(re);
        try {
            Process proc = pb.start();
            Object stdin  = inK  == 0 ? new Handle(null, proc.getOutputStream()) : LeanRT.UNIT; // we write child stdin
            Object stdout = outK == 0 ? new Handle(proc.getInputStream(), null)  : LeanRT.UNIT; // we read child stdout
            Object stderr = errK == 0 ? new Handle(proc.getErrorStream(), null)  : LeanRT.UNIT;
            return ok(new LirObject(CHILD_CTOR, 0, new Object[]{ stdin, stdout, stderr, new Proc(proc) }));
        } catch (IOException e) { return err(e); }
    }

    // wait/takeStdin take an implicit {cfg : StdioConfig} *before* the Child, so resolve
    // the Child by its ctor name rather than by position.
    private static LirObject childOf(Object[] a) {
        for (Object o : LeanRT.meaningful(a))
            if (o instanceof LirObject lo && CHILD_CTOR.equals(lo.ctorName())) return lo;
        throw new IllegalStateException("no Child argument");
    }

    @TruffleBoundary
    private static Object procWait(Object[] a) {
        Proc proc = (Proc) childOf(a).fields()[3];
        try { return ok((long) proc.p.waitFor() & 0xFFFF_FFFFL); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return err("Child.wait interrupted"); }
    }

    // takeStdin : Child → IO (stdin.toHandleType × Child) — hand out the stdin handle and
    // return a Child with stdin replaced by Unit (preserving the hidden Proc field).
    private static Object procTakeStdin(Object[] a) {
        Object[] cf = childOf(a).fields();
        LirObject newChild = new LirObject(CHILD_CTOR, 0, new Object[]{ LeanRT.UNIT, cf[1], cf[2], cf[3] });
        return ok(new LirObject("Prod.mk", 0, new Object[]{ cf[0], newChild }));
    }

    @TruffleBoundary
    private static Object procExit(Object[] a) {
        int code = (int) LeanRT.asLong(LeanRT.meaningful(a)[0]);
        System.out.flush();
        throw new LeanExit(code);
    }

    /**
     * Opaque Lean Handle — a host file OR a process pipe, flowing through the interpreter
     * untouched. File handles wrap a {@link RandomAccessFile} (seekable); pipe handles
     * (subprocess stdin/stdout/stderr) wrap an {@link InputStream}/{@link OutputStream}.
     */
    static final class Handle {
        final RandomAccessFile raf;      // file-backed (null for pipes)
        final InputStream in;            // readable pipe (null otherwise)
        final OutputStream out;          // writable pipe (null otherwise)
        Handle(RandomAccessFile raf) { this.raf = raf; this.in = null; this.out = null; }
        Handle(InputStream in, OutputStream out) { this.raf = null; this.in = in; this.out = out; }

        void write(byte[] b) throws IOException {
            if (raf != null) raf.write(b);
            else if (out != null) out.write(b);
            else throw new IOException("handle is not writable");
        }
        int readByte() throws IOException {
            if (raf != null) return raf.read();
            if (in != null) return in.read();
            throw new IOException("handle is not readable");
        }
        /** Read up to n bytes; returns the bytes actually read (possibly fewer / empty at EOF). */
        byte[] read(int n) throws IOException {
            byte[] buf = new byte[n];
            int r = raf != null ? raf.read(buf) : in != null ? in.read(buf) : -1;
            return r <= 0 ? new byte[0] : (r == n ? buf : java.util.Arrays.copyOf(buf, r));
        }
        void flush() throws IOException { if (out != null) out.flush(); }   // raf is unbuffered
        boolean isWritable() { return raf != null || out != null; }
    }

    // ── directory / path ops ──

    @TruffleBoundary
    private static Object createTempDir(Object[] a) {
        try { return ok(Files.createTempDirectory("lean4j-").toString()); }
        catch (IOException e) { return err(e); }
    }

    @TruffleBoundary
    private static Object createDir(Object[] a) {
        try { Files.createDirectories(Path.of(LeanRT.str(LeanRT.meaningful(a)[0]))); return ok(LeanRT.UNIT); }
        catch (IOException e) { return err(e); }
    }

    @TruffleBoundary
    private static Object removeFile(Object[] a) {
        try { Files.deleteIfExists(Path.of(LeanRT.str(LeanRT.meaningful(a)[0]))); return ok(LeanRT.UNIT); }
        catch (IOException e) { return err(e); }
    }

    @TruffleBoundary
    private static Object removeDir(Object[] a) {
        try { Files.deleteIfExists(Path.of(LeanRT.str(LeanRT.meaningful(a)[0]))); return ok(LeanRT.UNIT); }
        catch (IOException e) { return err(e); }
    }

    @TruffleBoundary
    private static Object rename(Object[] a) {
        Object[] m = LeanRT.meaningful(a);
        try {
            Files.move(Path.of(LeanRT.str(m[0])), Path.of(LeanRT.str(m[1])),
                       java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return ok(LeanRT.UNIT);
        } catch (IOException e) { return err(e); }
    }

    @TruffleBoundary
    private static Object readDir(Object[] a) {
        String dir = LeanRT.str(LeanRT.meaningful(a)[0]);
        LeanArray arr = LeanArray.empty(8);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Path.of(dir))) {
            for (Path p : ds) {
                arr = arr.push(new LirObject("IO.FS.DirEntry.mk", 0,
                        new Object[]{ dir, p.getFileName().toString() }));
            }
            return ok(arr);
        } catch (IOException e) { return err(e); }
    }

    @TruffleBoundary
    private static Object metadata(Object[] a) {
        Path p = Path.of(LeanRT.str(LeanRT.meaningful(a)[0]));
        if (!Files.exists(p)) return err("no such file or directory: " + p);
        long size;
        try { size = Files.isDirectory(p) ? 0 : Files.size(p); }
        catch (IOException e) { return err(e); }
        int fileType = Files.isDirectory(p) ? 0 : 1; // FileType.dir=0, file=1
        LirObject md = new LirObject("IO.FS.Metadata.mk", 0, new Object[]{ null, null }); // accessed, modified
        md.setScalar(0, size);              // byteSize  (sproj[2,0] : u64)
        md.setScalar(8, 1L);                // numLinks  (sproj[2,8] : u64)
        md.setScalar(16, (long) fileType);  // type      (sproj[2,16]: u8)
        return ok(md);
    }

    // ── handle ops ──

    @TruffleBoundary
    private static Object handleMk(Object[] a) {
        Object[] m = LeanRT.meaningful(a);
        String path = LeanRT.str(m[0]);
        long mode = LeanRT.asLong(m[1]); // Mode: read=0, write=1, writeNew=2, append=3, readWrite=4
        try {
            RandomAccessFile raf = new RandomAccessFile(path, mode == 0 ? "r" : "rw");
            if (mode == 1 || mode == 2) raf.setLength(0);     // (over)write → truncate
            else if (mode == 3) raf.seek(raf.length());       // append
            return ok(new Handle(raf));
        } catch (IOException e) { return err(e); }
    }

    @TruffleBoundary
    private static Object handlePutStr(Object[] a) {
        Object[] m = LeanRT.meaningful(a);
        try { ((Handle) m[0]).write(LeanRT.str(m[1]).getBytes(StandardCharsets.UTF_8)); return ok(LeanRT.UNIT); }
        catch (IOException e) { return err(e); }
    }

    // getLine: read bytes up to and including '\n' (or EOF), decode UTF-8 — Lean keeps
    // the trailing newline and returns "" at EOF.
    @TruffleBoundary
    private static Object handleGetLine(Object[] a) {
        Handle h = (Handle) LeanRT.meaningful(a)[0];
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            int c;
            while ((c = h.readByte()) != -1) { bos.write(c); if (c == '\n') break; }
            return ok(new String(bos.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException e) { return err(e); }
    }

    @TruffleBoundary
    private static Object handleWrite(Object[] a) {
        Object[] m = LeanRT.meaningful(a);
        try { ((Handle) m[0]).write(LeanRT.arrayToBytes((LeanArray) m[1])); return ok(LeanRT.UNIT); }
        catch (IOException e) { return err(e); }
    }

    @TruffleBoundary
    private static Object handleFlush(Object[] a) {
        try { ((Handle) LeanRT.meaningful(a)[0]).flush(); return ok(LeanRT.UNIT); }
        catch (IOException e) { return err(e); }
    }

    @TruffleBoundary
    private static Object handleRewind(Object[] a) {
        Handle h = (Handle) LeanRT.meaningful(a)[0];
        try { if (h.raf != null) h.raf.seek(0); return ok(LeanRT.UNIT); } // seek is a no-op on pipes
        catch (IOException e) { return err(e); }
    }

    @TruffleBoundary
    private static Object handleTruncate(Object[] a) {
        Handle h = (Handle) LeanRT.meaningful(a)[0];
        try { if (h.raf != null) h.raf.setLength(h.raf.getFilePointer()); return ok(LeanRT.UNIT); }
        catch (IOException e) { return err(e); }
    }

    @TruffleBoundary
    private static Object realPath(Object[] a) {
        try { return ok(Path.of(LeanRT.str(LeanRT.meaningful(a)[0])).toRealPath().toString()); }
        catch (IOException e) { return err(e); }
    }

    private static final java.security.SecureRandom RNG = new java.security.SecureRandom();

    @TruffleBoundary
    private static Object getRandomBytes(Object[] a) {
        byte[] buf = new byte[(int) LeanRT.asLong(LeanRT.meaningful(a)[0])];
        RNG.nextBytes(buf);
        return ok(LeanRT.bytesToArray(buf));
    }

    @TruffleBoundary
    private static Object handleRead(Object[] a) {
        Object[] m = LeanRT.meaningful(a);
        Handle h = (Handle) m[0];
        int n = (int) LeanRT.asLong(m[1]);
        try { return ok(LeanRT.bytesToArray(h.read(n))); }
        catch (IOException e) { return err(e); }
    }

    // ── EST.Out wrapping (IO result ADT) ──

    private static Object ok(Object value) {
        return new LirObject("EST.Out.ok", 0, new Object[]{ value });
    }
    private static Object err(IOException e) { return err(String.valueOf(e.getMessage())); }
    private static Object err(String msg) {
        Object ioErr = new LirObject("IO.Error.userError", 18, new Object[]{ msg });
        return new LirObject("EST.Out.error", 1, new Object[]{ ioErr });
    }
}
