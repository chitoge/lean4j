package lean4j.truffle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the lean4j_manifest.json file that lives alongside a Lean 4 shared library.
 * Minimal JSON parsing without external dependencies.
 */
public record LeanManifest(String module, String version, List<LeanFunctionDescriptor> functions) {

    public static LeanManifest load(Path manifestPath) throws IOException {
        String json = Files.readString(manifestPath);
        return parse(json);
    }

    /** Locate the manifest for a .so at the given path (same dir, same name + .json). */
    public static Path manifestPath(Path soPath) {
        String name = soPath.getFileName().toString();
        // lib<name>.so -> <name>.json (strip lib prefix and .so suffix)
        if (name.startsWith("lib") && name.endsWith(".so")) {
            name = name.substring(3, name.length() - 3);
        } else if (name.endsWith(".so")) {
            name = name.substring(0, name.length() - 3);
        }
        return soPath.getParent().resolve(name + "_manifest.json");
    }

    // --- Minimal bespoke JSON parser ---

    private static LeanManifest parse(String json) {
        String module = extractString(json, "\"module\"");
        String version = extractString(json, "\"version\"");
        List<LeanFunctionDescriptor> fns = parseFunctions(json);
        return new LeanManifest(module, version, fns);
    }

    private static String extractString(String json, String key) {
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return "";
        int colon = json.indexOf(':', keyIdx + key.length());
        int open = json.indexOf('"', colon + 1);
        int close = json.indexOf('"', open + 1);
        return json.substring(open + 1, close);
    }

    private static List<LeanFunctionDescriptor> parseFunctions(String json) {
        List<LeanFunctionDescriptor> result = new ArrayList<>();
        int fnArray = json.indexOf("\"functions\"");
        if (fnArray < 0) return result;
        int arrayStart = json.indexOf('[', fnArray);
        int arrayEnd = json.lastIndexOf(']');

        String array = json.substring(arrayStart + 1, arrayEnd);
        // Split on top-level objects
        int depth = 0;
        int start = -1;
        for (int i = 0; i < array.length(); i++) {
            char c = array.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    result.add(parseFunction(array.substring(start, i + 1)));
                    start = -1;
                }
            }
        }
        return result;
    }

    private static LeanFunctionDescriptor parseFunction(String obj) {
        String name = extractString(obj, "\"name\"");
        String leanName = extractString(obj, "\"lean_name\"");
        LeanType ret = LeanType.fromString(extractString(obj, "\"return\""));
        List<LeanType> params = parseParams(obj);
        return new LeanFunctionDescriptor(name, leanName.isEmpty() ? name : leanName, params, ret);
    }

    private static List<LeanType> parseParams(String obj) {
        List<LeanType> types = new ArrayList<>();
        int paramsIdx = obj.indexOf("\"params\"");
        if (paramsIdx < 0) return types;
        int arrStart = obj.indexOf('[', paramsIdx);
        int arrEnd = obj.indexOf(']', arrStart);
        String arr = obj.substring(arrStart + 1, arrEnd);
        for (String part : arr.split(",")) {
            String s = part.strip().replace("\"", "");
            if (!s.isEmpty()) types.add(LeanType.fromString(s));
        }
        return types;
    }
}
