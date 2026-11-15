package io.opencrawler;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** URL、编码与文本工具；不依赖任何第三方库。 */
public final class Util {
    private Util() {}

    public static final Set<String> SKIP_EXT = Set.of(
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".ico", ".bmp", ".avif",
        ".css", ".js", ".mjs", ".map", ".woff", ".woff2", ".ttf", ".eot", ".otf",
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".zip", ".rar",
        ".7z", ".gz", ".tar", ".mp3", ".mp4", ".avi", ".mov", ".wmv", ".exe", ".apk");

    public static final List<String> DEFAULT_DENY = List.of(
        "/(admin|wp-admin|administrator|login|logout|signin|signup|auth|account|cart|checkout|private|internal)(/|$)",
        "[?&](token|api[_-]?key|password|secret)=");

    public static final String DEFAULT_UA =
        "OpenCrawlerJava/0.2 (+educational crawler; set config.user_agent)";

    /** 把相对链接绝对化并规范化；失败时返回空串。 */
    public static String normalize(String href) { return normalize("", href); }

    public static String normalize(String base, String href) {
        if (href == null) return "";
        String raw = htmlUnescape(href.trim());
        if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("javascript:")
            || raw.startsWith("mailto:") || raw.startsWith("data:") || raw.startsWith("tel:")) return "";
        try {
            URI resolved = base.isEmpty() ? new URI(raw) : new URI(base).resolve(new URI(raw));
            String scheme = resolved.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return "";
            String host = resolved.getHost();
            if (host == null || host.isEmpty()) return "";
            int port = resolved.getPort();
            boolean defaultPort = port == -1
                || (scheme.equalsIgnoreCase("http") && port == 80)
                || (scheme.equalsIgnoreCase("https") && port == 443);
            String authority = host.toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
            String path = resolved.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            path = path.replaceAll("/{2,}", "/");
            StringBuilder out = new StringBuilder(scheme.toLowerCase(Locale.ROOT))
                .append("://").append(authority).append(path);
            if (resolved.getRawQuery() != null) out.append('?').append(resolved.getRawQuery());
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String host(String url) {
        try {
            URI u = new URI(url);
            return u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        } catch (Exception e) { return ""; }
    }

    public static String scheme(String url) {
        try {
            URI u = new URI(url);
            return u.getScheme() == null ? "https" : u.getScheme().toLowerCase(Locale.ROOT);
        } catch (Exception e) { return "https"; }
    }

    public static String path(String url) {
        try {
            URI u = new URI(url);
            String p = u.getRawPath();
            return p == null || p.isEmpty() ? "/" : p;
        } catch (Exception e) { return "/"; }
    }

    public static String baseUrl(String url) {
        try {
            URI u = new URI(url);
            return u.getScheme() + "://" + u.getAuthority();
        } catch (Exception e) { return url; }
    }

    public static boolean sameOrSubdomain(String host, String domain) {
        return host.equalsIgnoreCase(domain) || host.endsWith("." + domain.toLowerCase(Locale.ROOT));
    }

    public static boolean hasSkipExtension(String url) {
        String p = path(url).toLowerCase(Locale.ROOT);
        int dot = p.lastIndexOf('.');
        return dot >= 0 && SKIP_EXT.contains(p.substring(dot));
    }

    public static boolean matchesAny(String text, List<String> patterns) {
        for (String p : patterns) {
            try {
                if (Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(text).find()) return true;
            } catch (Exception ignored) { /* 忽略用户写的非法正则 */ }
        }
        return false;
    }

    public static String decode(byte[] body, String contentType) {
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String s = part.trim();
                if (s.regionMatches(true, 0, "charset=", 0, 8)) {
                    try {
                        Charset cs = Charset.forName(s.substring(8).trim().replace("\"", ""));
                        return cs.decode(ByteBuffer.wrap(body)).toString();
                    } catch (Exception ignored) { }
                }
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            try { return new String(body, Charset.forName("GB18030")); }
            catch (Exception ignored) { return new String(body, StandardCharsets.ISO_8859_1); }
        }
    }

    public static String htmlUnescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&nbsp;", " ");
    }

    public static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }

    public static String nowIso() { return Instant.now().toString(); }

    public static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public static String urlDecode(String s) {
        try { return URLDecoder.decode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }

    /** 稳定地把任意对象压平为字符串，用于日志与结果展示。 */
    public static String flat(Object v) {
        if (v == null) return "";
        if (v instanceof Map<?, ?> m) { Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, x) -> copy.put(String.valueOf(k), x)); return Json.stringify(copy); }
        return String.valueOf(v);
    }
}
