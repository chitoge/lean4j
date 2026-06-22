package lean4j.lir;

import java.util.*;

/** Minimal JSON value hierarchy with a recursive-descent parser. */
public sealed interface Json permits Json.JNull, Json.JBool, Json.JNum, Json.JStr, Json.JArr, Json.JObj {

    record JNull()                               implements Json {}
    record JBool(boolean value)                  implements Json {}
    record JNum(long value)                      implements Json {}
    record JStr(String value)                    implements Json {}
    record JArr(List<Json> elements)             implements Json {}
    record JObj(Map<String, Json> fields)        implements Json {}

    static Json parse(String input) { return new Parser(input).parseValue(); }

    default String str()    { return ((JStr) this).value(); }
    default long   num()    { return ((JNum) this).value(); }
    default boolean bool()  { return ((JBool) this).value(); }
    default Map<String, Json> obj()  { return ((JObj) this).fields(); }
    default List<Json> arr()         { return ((JArr) this).elements(); }
    default Json get(String key)     { return obj().get(key); }
    default boolean has(String key)  { return obj().containsKey(key); }

    final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        Json parseValue() {
            skipWs();
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObj();
                case '[' -> parseArr();
                case '"' -> parseStr();
                case 't' -> { pos += 4; yield new JBool(true); }
                case 'f' -> { pos += 5; yield new JBool(false); }
                case 'n' -> { pos += 4; yield new JNull(); }
                default  -> parseNum();
            };
        }

        private Json parseObj() {
            pos++;
            Map<String, Json> fields = new LinkedHashMap<>();
            skipWs();
            while (s.charAt(pos) != '}') {
                if (s.charAt(pos) == ',') { pos++; skipWs(); }
                String key = parseStr().value();
                skipWs();
                pos++; // ':'
                fields.put(key, parseValue());
                skipWs();
            }
            pos++;
            return new JObj(Collections.unmodifiableMap(fields));
        }

        private Json parseArr() {
            pos++;
            List<Json> elems = new ArrayList<>();
            skipWs();
            while (s.charAt(pos) != ']') {
                if (s.charAt(pos) == ',') { pos++; }
                elems.add(parseValue());
                skipWs();
            }
            pos++;
            return new JArr(Collections.unmodifiableList(elems));
        }

        private JStr parseStr() {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (s.charAt(pos) != '"') {
                char c = s.charAt(pos++);
                if (c == '\\') {
                    char esc = s.charAt(pos++);
                    sb.append(switch (esc) {
                        case '"'  -> '"';
                        case '\\' -> '\\';
                        case '/'  -> '/';
                        case 'n'  -> '\n';
                        case 'r'  -> '\r';
                        case 't'  -> '\t';
                        case 'b'  -> '\b';
                        case 'f'  -> '\f';
                        case 'u'  -> {
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            yield (char) Integer.parseInt(hex, 16);
                        }
                        default -> esc;
                    });
                } else {
                    sb.append(c);
                }
            }
            pos++;
            return new JStr(sb.toString());
        }

        private Json parseNum() {
            int start = pos;
            if (pos < s.length() && s.charAt(pos) == '-') pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            return new JNum(Long.parseLong(s.substring(start, pos)));
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }
    }
}
