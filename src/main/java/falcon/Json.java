package falcon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;

/** Small strict JSON codec with 64-bit integers, finite doubles, and bounded nesting. */
final class Json {
    private final String source;
    private int pos;
    private Json(String source) { this.source = source; }
    static Object read(String source) {
        Json parser = new Json(source);
        Object value = parser.value(0); parser.space();
        if (parser.pos != source.length()) throw parser.error("Unexpected trailing text");
        return value;
    }
    private Object value(int depth) {
        if (depth > 128) throw error("JSON nesting exceeds 128");
        space();
        if (pos == source.length()) throw error("Expected JSON value");
        char c = source.charAt(pos);
        if (c == '"') return string();
        if (c == '[') {
            pos++; space(); var list = new ArrayList<Object>();
            if (take(']')) return list;
            do { list.add(value(depth + 1)); space(); if (take(']')) return list; expect(','); } while (true);
        }
        if (c == '{') {
            pos++; space(); var map = new LinkedHashMap<String, Object>();
            if (take('}')) return map;
            do {
                space(); if (pos >= source.length() || source.charAt(pos) != '"') throw error("Expected object key");
                String key = string(); space(); expect(':');
                if (map.containsKey(key)) throw error("Duplicate object key '" + key + "'");
                map.put(key, value(depth + 1)); space(); if (take('}')) return map; expect(',');
            } while (true);
        }
        if (source.startsWith("true", pos)) { pos += 4; return true; }
        if (source.startsWith("false", pos)) { pos += 5; return false; }
        if (source.startsWith("null", pos)) { pos += 4; return null; }
        int start = pos;
        take('-');
        if (!take('0')) digits();
        boolean decimal = false;
        if (take('.')) { decimal = true; digits(); }
        if (take('e') || take('E')) { decimal = true; if (!take('+')) take('-'); digits(); }
        try {
            String text = source.substring(start, pos);
            if (!decimal) return Long.parseLong(text);
            double number = Double.parseDouble(text);
            if (!Double.isFinite(number)) throw error("Number is outside the finite range");
            return number;
        } catch (NumberFormatException e) { throw error("Invalid or out-of-range JSON number"); }
    }
    private void digits() {
        int start = pos;
        while (pos < source.length() && source.charAt(pos) >= '0' && source.charAt(pos) <= '9') pos++;
        if (start == pos) throw error("Expected a digit");
    }
    private String string() {
        expect('"'); StringBuilder result = new StringBuilder();
        while (pos < source.length()) {
            char c = source.charAt(pos++);
            if (c == '"') return result.toString();
            if (c < 32) throw error("Unescaped control character in string");
            if (c == '\\') {
                if (pos == source.length()) throw error("Unclosed string escape");
                c = source.charAt(pos++);
                switch (c) {
                    case '"', '\\', '/' -> result.append(c);
                    case 'b' -> result.append('\b'); case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n'); case 'r' -> result.append('\r'); case 't' -> result.append('\t');
                    case 'u' -> {
                        if (pos + 4 > source.length()) throw error("Incomplete Unicode escape");
                        String hex = source.substring(pos, pos + 4);
                        if (!hex.matches("[0-9a-fA-F]{4}")) throw error("Invalid Unicode escape");
                        result.append((char) Integer.parseInt(hex, 16)); pos += 4;
                    }
                    default -> throw error("Unknown string escape");
                }
            } else result.append(c);
        }
        throw error("Unclosed JSON string");
    }
    private void space() { while (pos < source.length() && " \t\r\n".indexOf(source.charAt(pos)) >= 0) pos++; }
    private boolean take(char c) { if (pos < source.length() && source.charAt(pos) == c) { pos++; return true; } return false; }
    private void expect(char c) { if (!take(c)) throw error("Expected '" + c + "'"); }
    private IllegalArgumentException error(String message) { return new IllegalArgumentException(message + " at JSON character " + (pos + 1) + "."); }

    static String write(Object value) {
        StringBuilder result = new StringBuilder();
        write(value, result, new IdentityHashMap<>(), 0); return result.toString();
    }
    private static void write(Object value, StringBuilder out, IdentityHashMap<Object, Boolean> active, int depth) {
        if (depth > 128) throw new IllegalArgumentException("JSON nesting exceeds 128.");
        if (value == null) { out.append("null"); return; }
        if (value instanceof String string) { quote(string, out); return; }
        if (value instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) throw new IllegalArgumentException("JSON numbers must be finite.");
            out.append(n); return;
        }
        if (value instanceof Boolean) { out.append(value); return; }
        if (active.put(value, true) != null) throw new IllegalArgumentException("Cannot serialize cyclic collections to JSON.");
        if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) { if (i > 0) out.append(','); write(list.get(i), out, active, depth + 1); }
            out.append(']');
        } else if (value instanceof Map<?, ?> map) {
            out.append('{'); boolean first = true;
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("JSON object keys must be strings.");
                if (!first) out.append(','); first = false; quote(key, out); out.append(':'); write(entry.getValue(), out, active, depth + 1);
            }
            out.append('}');
        } else throw new IllegalArgumentException("Value cannot be encoded as JSON.");
        active.remove(value);
    }
    private static void quote(String value, StringBuilder out) {
        out.append('"');
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if (c < 32 || Character.isSurrogate(c)) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        out.append('"');
    }
}
