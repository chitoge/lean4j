package lean4j.jit;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

import lean4j.lir.LirObject;

/** Leaf expression nodes: local reads, literals, projections, constructors. */
final class LeanLeafNodes {

    private LeanLeafNodes() {}

    /** Read a local variable from a frame slot. */
    static final class ReadLocal extends LeanExpressionNode {
        private final int slot;
        private final boolean isLong;

        ReadLocal(int slot, boolean isLong) {
            this.slot = slot;
            this.isLong = isLong;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            return isLong ? (Long) frame.getLong(slot) : frame.getObject(slot);
        }

        @Override
        public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
            if (isLong) return frame.getLong(slot);
            Object v = frame.getObject(slot);
            if (v instanceof Long l) return l;
            throw new UnexpectedResultException(v);
        }
    }

    /** A scalar (u8/u16/u32/u64) literal. Stays unboxed on the long fast path. */
    static final class LongLiteral extends LeanExpressionNode {
        private final long value;

        LongLiteral(long value) { this.value = value; }

        @Override
        public Object executeGeneric(VirtualFrame frame) { return value; }

        @Override
        public long executeLong(VirtualFrame frame) { return value; }
    }

    /** An object literal: a String constant, a boxed big Nat, or null (erased). */
    static final class ObjectLiteral extends LeanExpressionNode {
        @CompilationFinal private final Object value;

        ObjectLiteral(Object value) { this.value = value; }

        @Override
        public Object executeGeneric(VirtualFrame frame) { return value; }
    }

    /** {@code isShared x} — 1 if the object was inc'd (conservatively shared), else 0. */
    static final class IsShared extends LeanExpressionNode {
        private final int slot;

        IsShared(int slot) { this.slot = slot; }

        @Override
        public Object executeGeneric(VirtualFrame frame) { return executeLong(frame); }

        @Override
        public long executeLong(VirtualFrame frame) {
            Object o = frame.getObject(slot);
            if (o instanceof lean4j.lir.LirObject l) return l.isShared() ? 1L : 0L;
            if (o instanceof LeanArray a) return a.isShared() ? 1L : 0L;
            return 0L; // scalars / strings / closures are effectively unique here
        }
    }

    /** Extract object field {@code index} from a constructor ({@code proj}). */
    static final class Proj extends LeanExpressionNode {
        private final int slot;
        private final int index;

        Proj(int slot, int index) {
            this.slot = slot;
            this.index = index;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object o = frame.getObject(slot);
            return ((LirObject) o).fields()[index];
        }
    }

    /** Extract packed scalar field at byte {@code offset} ({@code sproj}). */
    static final class SProj extends LeanExpressionNode {
        private final int slot;
        private final int offset;

        SProj(int slot, int offset) { this.slot = slot; this.offset = offset; }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            return ((LirObject) frame.getObject(slot)).getScalar(offset);
        }

        @Override
        public long executeLong(VirtualFrame frame) {
            Object v = ((LirObject) frame.getObject(slot)).getScalar(offset);
            return v instanceof Long l ? l : 0L;
        }
    }

    /** Extract packed usize field {@code index} ({@code uproj}). */
    static final class UProj extends LeanExpressionNode {
        private final int slot;
        private final int index;

        UProj(int slot, int index) { this.slot = slot; this.index = index; }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            return ((LirObject) frame.getObject(slot)).getUsize(index);
        }

        @Override
        public long executeLong(VirtualFrame frame) {
            Object v = ((LirObject) frame.getObject(slot)).getUsize(index);
            return v instanceof Long l ? l : 0L;
        }
    }

    /** Construct a Lean ADT value ({@code ctor}) on the JVM heap. */
    static final class Ctor extends LeanExpressionNode {
        private final String ctorName;
        private final int cidx;
        @Children private final LeanExpressionNode[] args;

        Ctor(String ctorName, int cidx, LeanExpressionNode[] args) {
            this.ctorName = ctorName;
            this.cidx = cidx;
            this.args = args;
        }

        @Override
        @com.oracle.truffle.api.nodes.ExplodeLoop
        public Object executeGeneric(VirtualFrame frame) {
            Object[] fields = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                fields[i] = args[i].executeGeneric(frame);
            }
            return new LirObject(ctorName, cidx, fields);
        }
    }
}
