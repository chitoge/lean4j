package lean4j;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/**
 * Calls Lean's standard-library JSON parser from the JVM, against the IR produced by
 * {@code examples/JsonExport.lean} (a standalone, no-external-library lowering). Run via
 * {@code make json-example}.
 */
public final class JsonExample {
    public static void main(String[] args) {
        String ir = System.getProperty("lean4j.ir", System.getProperty("ir"));
        try (Context c = Context.newBuilder("lean4j-jit").allowAllAccess(true).build()) {
            Value m = c.eval("lean4j-jit", ir);
            String input = "{\"hello\": [1, 2, 3], \"ok\": true, \"n\": 42}";
            System.out.println("input:                  " + input);
            // jsonRoundtrip parses with Lean.Json.parse and re-serializes — a clean String result
            System.out.println("jsonRoundtrip(input):   " + m.invokeMember("jsonRoundtrip", input));
            // or call Lean's parser directly and get the parsed AST back as a value
            System.out.println("Lean.Json.parse(input): " + m.invokeMember("Lean.Json.parse", input));
        }
    }
}
