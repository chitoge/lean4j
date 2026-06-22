package lean4j.jit;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/** Structural nodes: statement sequencing (block), writes, and case dispatch. */
final class LeanControlNodes {

    private LeanControlNodes() {}

    /** A statement: runs for effect, writing the frame or mutating an object. */
    abstract static class Stmt extends com.oracle.truffle.api.nodes.Node {
        abstract void executeVoid(VirtualFrame frame);
    }

    /** Write an expression's value into a frame slot ({@code vdecl}). */
    static final class WriteLocal extends Stmt {
        private final int slot;
        private final boolean isLong;
        @Child private LeanExpressionNode value;

        WriteLocal(int slot, boolean isLong, LeanExpressionNode value) {
            this.slot = slot;
            this.isLong = isLong;
            this.value = value;
        }

        @Override
        void executeVoid(VirtualFrame frame) {
            if (isLong) {
                try {
                    frame.setLong(slot, value.executeLong(frame));
                    return;
                } catch (UnexpectedResultException e) {
                    // A long-typed slot should only ever receive longs; a miss is a translator bug.
                    throw CompilerDirectives.shouldNotReachHere(
                        "non-long value for long slot " + slot + ": " + e.getResult());
                }
            }
            frame.setObject(slot, value.executeGeneric(frame));
        }
    }

    /**
     * Store a value into constructor field {@code i} of object {@code x}
     * ({@code set x[i] := y}). Mutates in place when unique; if the object is
     * shared, writes a fresh copy back into the slot (insurance — well-formed IR
     * only emits {@code set} on the unique branch of an isShared check).
     */
    static final class SetField extends Stmt {
        private final int objSlot;
        private final int index;
        @Child private LeanExpressionNode value;

        SetField(int objSlot, int index, LeanExpressionNode value) {
            this.objSlot = objSlot;
            this.index = index;
            this.value = value;
        }

        @Override
        void executeVoid(VirtualFrame frame) {
            lean4j.lir.LirObject o = (lean4j.lir.LirObject) frame.getObject(objSlot);
            if (o.isShared()) {
                o = o.copyUnique();
                frame.setObject(objSlot, o);
            }
            o.fields()[index] = value.executeGeneric(frame);
        }
    }

    /** Store a packed scalar field at byte {@code offset} ({@code sset x[n,offset] := y}). */
    static final class SSetField extends Stmt {
        private final int objSlot;
        private final int offset;
        @Child private LeanExpressionNode value;

        SSetField(int objSlot, int offset, LeanExpressionNode value) {
            this.objSlot = objSlot;
            this.offset = offset;
            this.value = value;
        }

        @Override
        void executeVoid(VirtualFrame frame) {
            lean4j.lir.LirObject o = (lean4j.lir.LirObject) frame.getObject(objSlot);
            if (o.isShared()) { o = o.copyUnique(); frame.setObject(objSlot, o); }
            o.setScalar(offset, value.executeGeneric(frame));
        }
    }

    /** Store a packed usize field {@code index} ({@code uset x[i] := y}). */
    static final class USetField extends Stmt {
        private final int objSlot;
        private final int index;
        @Child private LeanExpressionNode value;

        USetField(int objSlot, int index, LeanExpressionNode value) {
            this.objSlot = objSlot;
            this.index = index;
            this.value = value;
        }

        @Override
        void executeVoid(VirtualFrame frame) {
            lean4j.lir.LirObject o = (lean4j.lir.LirObject) frame.getObject(objSlot);
            if (o.isShared()) { o = o.copyUnique(); frame.setObject(objSlot, o); }
            o.setUsize(index, value.executeGeneric(frame));
        }
    }

    /** Retag a constructor in place ({@code setTag x := cidx}) — FBIP cell reuse. */
    static final class SetTag extends Stmt {
        private final int objSlot;
        private final int newCidx;

        SetTag(int objSlot, int newCidx) { this.objSlot = objSlot; this.newCidx = newCidx; }

        @Override
        void executeVoid(VirtualFrame frame) {
            lean4j.lir.LirObject o = (lean4j.lir.LirObject) frame.getObject(objSlot);
            if (o.isShared()) { o = o.copyUnique(); frame.setObject(objSlot, o); }
            o.setTag(newCidx);
        }
    }

    /** Mark an object shared on {@code inc} (reference duplication). No-op for scalars. */
    static final class MarkShared extends Stmt {
        private final int slot;

        MarkShared(int slot) { this.slot = slot; }

        @Override
        void executeVoid(VirtualFrame frame) {
            Object o = frame.getObject(slot);
            if (o instanceof lean4j.lir.LirObject l) l.markShared();
            else if (o instanceof LeanArray a) a.markShared();
        }
    }

    /**
     * A straight-line block: run the statements in order, then yield the terminal
     * expression's value. {@code @ExplodeLoop} unrolls them so the partial
     * evaluator sees a flat sequence.
     */
    static final class Block extends LeanExpressionNode {
        @Children private final Stmt[] stmts;
        @Child private LeanExpressionNode tail;

        Block(Stmt[] stmts, LeanExpressionNode tail) {
            this.stmts = stmts;
            this.tail = tail;
        }

        @Override
        @ExplodeLoop
        public Object executeGeneric(VirtualFrame frame) {
            for (Stmt s : stmts) s.executeVoid(frame);
            return tail.executeGeneric(frame);
        }

        @Override
        @ExplodeLoop
        public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
            for (Stmt s : stmts) s.executeVoid(frame);
            return tail.executeLong(frame);
        }
    }

    /**
     * Dispatch on a scalar constructor tag ({@code case}). Branches are indexed by
     * their constructor index; an optional default catches the rest. The tag read
     * + branch select PE to a clean switch, and {@code executeLong} keeps a
     * long-returning case (like {@code fib.go}) unboxed.
     */
    static final class Case extends LeanExpressionNode {
        private final int scrutSlot;
        /** True for a scalar tag (Bool/u8 in a Long slot); false for an ADT object (tag = ctor index). */
        private final boolean scalarScrutinee;
        @CompilerDirectives.CompilationFinal(dimensions = 1) private final int[] cidx;
        @Children private final LeanExpressionNode[] branches;
        @Child private LeanExpressionNode defaultBranch; // may be null

        Case(int scrutSlot, boolean scalarScrutinee, int[] cidx,
             LeanExpressionNode[] branches, LeanExpressionNode defaultBranch) {
            this.scrutSlot = scrutSlot;
            this.scalarScrutinee = scalarScrutinee;
            this.cidx = cidx;
            this.branches = branches;
            this.defaultBranch = defaultBranch;
        }

        private long tagOf(VirtualFrame frame) {
            if (scalarScrutinee) {
                return frame.getLong(scrutSlot);
            }
            Object o = frame.getObject(scrutSlot);
            // ADT: the constructor index is the tag. A bare boxed scalar (tagged
            // small Nat) acts as its own value.
            if (o instanceof lean4j.lir.LirObject obj) return obj.cidx();
            if (o instanceof Long l) return l;
            throw CompilerDirectives.shouldNotReachHere("case scrutinee is neither ctor nor scalar: " + o);
        }

        @Override
        @ExplodeLoop
        public Object executeGeneric(VirtualFrame frame) {
            long tag = tagOf(frame);
            for (int i = 0; i < cidx.length; i++) {
                if (cidx[i] == tag) return branches[i].executeGeneric(frame);
            }
            if (defaultBranch != null) return defaultBranch.executeGeneric(frame);
            throw CompilerDirectives.shouldNotReachHere("no case alt for tag " + tag);
        }

        @Override
        @ExplodeLoop
        public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
            long tag = tagOf(frame);
            for (int i = 0; i < cidx.length; i++) {
                if (cidx[i] == tag) return branches[i].executeLong(frame);
            }
            if (defaultBranch != null) return defaultBranch.executeLong(frame);
            throw CompilerDirectives.shouldNotReachHere("no case alt for tag " + tag);
        }
    }

    /** A node that always throws — for {@code unreachable}. */
    static final class Unreachable extends LeanExpressionNode {
        @Override
        public Object executeGeneric(VirtualFrame frame) {
            throw CompilerDirectives.shouldNotReachHere("Lean IR 'unreachable' reached");
        }
    }

    /** Control-flow transfer to a join point ({@code jmp j args}). */
    static final class JumpException extends com.oracle.truffle.api.nodes.ControlFlowException {
        final int jid;
        final Object[] args;
        JumpException(int jid, Object[] args) { this.jid = jid; this.args = args; }
    }

    /** Evaluate args and jump to join point {@code jid} ({@code jmp}). */
    static final class Jmp extends LeanExpressionNode {
        private final int jid;
        @Children private final LeanExpressionNode[] argNodes;

        Jmp(int jid, LeanExpressionNode[] argNodes) {
            this.jid = jid;
            this.argNodes = argNodes;
        }

        @Override
        @ExplodeLoop
        public Object executeGeneric(VirtualFrame frame) {
            Object[] vals = new Object[argNodes.length];
            for (int i = 0; i < argNodes.length; i++) vals[i] = argNodes[i].executeGeneric(frame);
            throw new JumpException(jid, vals);
        }
    }

    /**
     * A join-point declaration ({@code jdecl j (params) := body; cont}). Run the
     * continuation; a {@code jmp} to this join point unwinds here via
     * {@link JumpException}, binds the params, and runs the body — looping if the
     * body jumps back (tail-recursive loops). Jumps to other join points
     * propagate to the enclosing JDecl.
     */
    static final class JDecl extends LeanExpressionNode {
        private final int jid;
        @CompilerDirectives.CompilationFinal(dimensions = 1) private final int[] paramSlots;
        @CompilerDirectives.CompilationFinal(dimensions = 1) private final boolean[] paramLong;
        @Child private LeanExpressionNode body;
        @Child private LeanExpressionNode cont;

        JDecl(int jid, int[] paramSlots, boolean[] paramLong, LeanExpressionNode body, LeanExpressionNode cont) {
            this.jid = jid;
            this.paramSlots = paramSlots;
            this.paramLong = paramLong;
            this.body = body;
            this.cont = cont;
        }

        @ExplodeLoop
        private void bind(VirtualFrame frame, Object[] args) {
            for (int i = 0; i < paramSlots.length; i++) {
                if (paramLong[i]) frame.setLong(paramSlots[i], (Long) args[i]);
                else frame.setObject(paramSlots[i], args[i]);
            }
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            LeanExpressionNode current = cont;
            while (true) {
                try {
                    return current.executeGeneric(frame);
                } catch (JumpException e) {
                    if (e.jid != jid) throw e;
                    bind(frame, e.args);
                    current = body;
                }
            }
        }

        @Override
        public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
            LeanExpressionNode current = cont;
            while (true) {
                try {
                    return current.executeLong(frame);
                } catch (JumpException e) {
                    if (e.jid != jid) throw e;
                    bind(frame, e.args);
                    current = body;
                }
            }
        }
    }
}
