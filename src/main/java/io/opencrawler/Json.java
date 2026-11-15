package io.opencrawler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 极简 JSON 解析/序列化：只用 JDK，支持对象、数组、字符串、数字、布尔与 null。 */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object value = p.value();
        p.skipWs();
        if (p.pos < p.text.length()) throw new IllegalArgumentException("JSON 尾部有多余字符，位置 " + p.pos);
        return value;
    }

    public static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object v, StringBuilder out) {
        if (v == null) { out.append("null"); return; }
        if (v instanceof String s) { quote(s, out); return; }
        if (v instanceof Boolean || v instanceof Number) {
            if (v instanceof Double d && (d.isNaN() || d.isInfinite())) { out.append("null"); return; }
            out.append(v); return;
        }
        if (v instanceof Map<?, ?> m) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(String.valueOf(e.getKey()), out);
                out.append(':');
                write(e.getValue(), out);
            }
            out.append('}'); return;
        }
        if (v instanceof Iterable<?> it) {
            out.append('[');
            boolean first = true;
            for (Object item : it) {
                if (!first) out.append(',');
                first = false;
                write(item, out);
            }
            out.append(']'); return;
        }
        quote(String.valueOf(v), out);
    }

    private static void quote(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object v) {
        return v instanceof List<?> l ? (List<Object>) l : new ArrayList<>();
    }

    public static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public static int intVal(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
        return def;
    }

    public static long longVal(Map<String, Object> m, String key, long def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { }
        return def;
    }

    public static double dblVal(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) { }
        return def;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        if (v instanceof Number n) return n.intValue() != 0;
        return def;
    }

    public static List<String> strings(Map<String, Object> m, String key) {
        List<String> out = new ArrayList<>();
        for (Object o : arr(m.get(key))) out.add(String.valueOf(o));
        return out;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) { this.text = text; }

        void skipWs() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
        }

        Object value() {
            skipWs();
            if (pos >= text.length()) throw new IllegalArgumentException("JSON 意外结束");
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, pos)) throw new IllegalArgumentException("非法字面量，位置 " + pos);
            pos += word.length();
            return value;
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (pos < text.length() && text.charAt(pos) == '}') { pos++; return map; }
            while (true) {
                skipWs();
                if (pos >= text.length() || text.charAt(pos) != '"')
                    throw new IllegalArgumentException("对象键必须是字符串，位置 " + pos);
                String key = string();
                skipWs();
                if (pos >= text.length() || text.charAt(pos) != ':')
                    throw new IllegalArgumentException("缺少冒号，位置 " + pos);
                pos++;
                map.put(key, value());
                skipWs();
                if (pos >= text.length()) throw new IllegalArgumentException("对象未闭合");
                char c = text.charAt(pos++);
                if (c == '}') return map;
                if (c != ',') throw new IllegalArgumentException("对象缺少逗号，位置 " + pos);
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWs();
            if (pos < text.length() && text.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                list.add(value());
                skipWs();
                if (pos >= text.length()) throw new IllegalArgumentException("数组未闭合");
                char c = text.charAt(pos++);
                if (c == ']') return list;
                if (c != ',') throw new IllegalArgumentException("数组缺少逗号，位置 " + pos);
            }
        }

        private String string() {
            pos++; // "
            StringBuilder sb = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (pos >= text.length()) break;
                char e = text.charAt(pos++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) throw new IllegalArgumentException("非法 \\u 转义");
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("非法转义 \\" + e);
                }
            }
            throw new IllegalArgumentException("字符串未闭合");
        }

        private Number number() {
            int start = pos;
            if (pos < text.length() && text.charAt(pos) == '-') pos++;
            boolean floating = false;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (Character.isDigit(c)) pos++;
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { floating = true; pos++; }
                else break;
            }
            String raw = text.substring(start, pos);
            if (raw.isEmpty()) throw new IllegalArgumentException("非法数字，位置 " + start);
            try {
                // 注意：不能用三元运算符返回 Double/Long，Java 会做数值提升把 Long 变成 Double
                if (floating) return Double.valueOf(raw);
                return Long.valueOf(raw);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("非法数字: " + raw);
            }
        }
    }
}
