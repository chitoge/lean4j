package lean4j.truffle;

/** Lean 4 primitive types that can cross the FFI boundary. */
public enum LeanType {
    UINT32,   // uint32_t — maps to Java int (unsigned semantics)
    UINT64,   // uint64_t — maps to Java long (unsigned semantics)
    INT32,    // int32_t  — maps to Java int
    INT64,    // int64_t  — maps to Java long
    FLOAT32,  // float    — maps to Java float
    FLOAT64,  // double   — maps to Java double
    BOOL,     // uint8_t (0/1) — maps to Java boolean
    STRING,   // lean_object* (LeanString) — marshalled to/from Java String
    UNIT,     // lean_object* (Unit) — maps to Java void/null
    OBJECT;   // lean_object* (opaque) — wrapped in LeanObject handle

    public static LeanType fromString(String s) {
        return switch (s) {
            case "UInt32" -> UINT32;
            case "UInt64" -> UINT64;
            case "Int32"  -> INT32;
            case "Int64"  -> INT64;
            case "Float"  -> FLOAT64;
            case "Bool"   -> BOOL;
            case "String" -> STRING;
            case "Unit"   -> UNIT;
            default -> OBJECT;
        };
    }
}
