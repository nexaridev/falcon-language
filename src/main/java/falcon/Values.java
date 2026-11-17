package falcon;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.IdentityHashMap;

final class Values {
    private Values() {}
    static String display(Object value) {
        if (value instanceof List<?> || value instanceof Map<?, ?>) {
            try { return Json.write(value); }
            catch (IllegalArgumentException e) { return "<cyclic or deeply nested collection>"; }
        }
        return String.valueOf(value);
    }
    static boolean equal(Object a, Object b) { return equal(a, b, new IdentityHashMap<>(), 0); }
    private static boolean equal(Object a, Object b, IdentityHashMap<Object, Object> seen, int depth) {
        if (a == b) return true;
        if (a == null || b == null || depth > 128) return false;
        if (a instanceof Long && b instanceof Long) return a.equals(b);
        if (a instanceof Number x && b instanceof Number y) return x.doubleValue() == y.doubleValue();
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            if (seen.containsKey(a)) return seen.get(a) == b;
            seen.put(a, b);
            try {
                for (int i = 0; i < x.size(); i++) if (!equal(x.get(i), y.get(i), seen, depth + 1)) return false;
                return true;
            } finally { seen.remove(a); }
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (!x.keySet().equals(y.keySet())) return false;
            if (seen.containsKey(a)) return seen.get(a) == b;
            seen.put(a, b);
            try {
                for (Object key : x.keySet()) if (!equal(x.get(key), y.get(key), seen, depth + 1)) return false;
                return true;
            } finally { seen.remove(a); }
        }
        return Objects.equals(a, b);
    }
    static String kind(Object value) {
        if (value == null) return "null";
        if (value instanceof Long) return "int";
        if (value instanceof Double) return "float";
        if (value instanceof Boolean) return "bool";
        if (value instanceof String) return "string";
        if (value instanceof List<?>) return "list";
        if (value instanceof Map<?, ?>) return "map";
        throw new IllegalArgumentException("Unsupported value");
    }
    static int offset(long index, int size) {
        if (index < 0 || index >= size) throw new IllegalArgumentException("Index " + index + " is outside 0.." + size + " (exclusive end).");
        return (int) index;
    }
    static Object index(Object target, Object index, Token site) {
        try {
            if (target instanceof List<?> list) return list.get(offset((long) index, list.size()));
            if (target instanceof Map<?, ?> map) {
                if (!map.containsKey(index)) throw new IllegalArgumentException("Map has no key '" + index + "'.");
                return map.get(index);
            }
            String string = (String) target;
            int count = string.codePointCount(0, string.length());
            int start = string.offsetByCodePoints(0, offset((long) index, count));
            return new String(Character.toChars(string.codePointAt(start)));
        } catch (IllegalArgumentException e) { throw new FalconError(site, e.getMessage()); }
    }
    @SuppressWarnings("unchecked")
    static void assign(Object target, Object index, Object value, Token site) {
        try {
            if (target instanceof List<?> list) {
                ((List<Object>) list).set(offset((long) index, list.size()), value);
                return;
            }
            if (target instanceof Map<?, ?> map) {
                ((Map<Object, Object>) map).put(index, value);
                return;
            }
            throw new IllegalArgumentException("Only lists and maps support index assignment.");
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new FalconError(site, e.getMessage());
        }
    }
}
