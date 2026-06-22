package lean4j.truffle;

import java.util.List;

/** Describes an exported Lean 4 function's type signature for FFM dispatch. */
public record LeanFunctionDescriptor(
    String nativeName,
    String leanName,
    List<LeanType> paramTypes,
    LeanType returnType
) {
    public int arity() { return paramTypes.size(); }
}
