package lean4j.jit;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * A debugger scope over a Lean frame — exposes the frame's slots as variables. Names are
 * IR-level ({@code x_5}, since Lean erases source variable names during compilation), but
 * the values are real Lean objects: scalars show directly and a {@code LirObject} expands
 * to its fields in the debugger. Returned by {@link LeanRootBody}'s {@code NodeLibrary}.
 */
@ExportLibrary(InteropLibrary.class)
public final class LeanScope implements TruffleObject {

    private final Frame frame;

    LeanScope(Frame frame) { this.frame = frame; }

    @ExportMessage boolean isScope() { return true; }
    @ExportMessage boolean hasMembers() { return true; }
    @ExportMessage boolean hasLanguage() { return true; }
    @ExportMessage Class<? extends TruffleLanguage<?>> getLanguage() { return JitLanguage.class; }
    @ExportMessage Object toDisplayString(@SuppressWarnings("unused") boolean side) { return "frame"; }

    @ExportMessage @TruffleBoundary
    Object getMembers(@SuppressWarnings("unused") boolean includeInternal) {
        FrameDescriptor d = frame.getFrameDescriptor();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < d.getNumberOfSlots(); i++) {
            Object nm = d.getSlotName(i);
            if (nm != null) names.add(nm.toString());
        }
        return new JitModule.StringArray(names.toArray(String[]::new));
    }

    @ExportMessage @TruffleBoundary
    boolean isMemberReadable(String member) { return slotOf(member) >= 0; }

    @ExportMessage @TruffleBoundary
    Object readMember(String member) throws UnknownIdentifierException {
        int i = slotOf(member);
        if (i < 0) throw UnknownIdentifierException.create(member);
        Object v = frame.getValue(i);
        return v == null ? "⊥" : v;     // erased / not-yet-bound
    }

    @TruffleBoundary
    private int slotOf(String name) {
        FrameDescriptor d = frame.getFrameDescriptor();
        for (int i = 0; i < d.getNumberOfSlots(); i++) {
            if (name.equals(String.valueOf(d.getSlotName(i)))) return i;
        }
        return -1;
    }
}
