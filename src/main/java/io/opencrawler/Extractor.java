package io.opencrawler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 站点自适应抽取：HTML / JSON / Sitemap / RSS + 配置化规则。 */
public final class Extractor {
    private static final Pattern TAG = Pattern.compile(
        "<(/?)([a-zA-Z][\\w:-]*)([^>]*?)(/?)>", Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile(
        "([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern SCRIPT = Pattern.compile(
        "<(script|style|noscript)[^>]*>.*?</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Set<String> VOID = Set.of(
        "br", "img", "meta", "link", "input", "hr", "source", "area", "base",
        "col", "embed", "param", "track", "wbr");
    private static final Set<String> LINK_KEYS = Set.of(
        "url", "href", "link", "next", "loc", "self", "permalink", "canonical");

    public static final class Elem {
        public final String tag;
        public final Map<String, String> attrs = new LinkedHashMap<>();
        public final StringBuilder text = new StringBuilder();
        public final int parent;

        Elem(String tag, int parent) { this.tag = tag; this.parent = parent; }

        public String attr(String name) { return attrs.getOrDefault(name.toLowerCase(Locale.ROOT), ""); }
        public String classes() { return attr("class"); }
        public String text() { return text.toString().trim(); }
    }

    private final Config cfg;
    private final List<String> allowedDomains = new ArrayList<>();

    public Extractor(Config cfg) {
        this.cfg = cfg;
        allowedDomains.addAll(cfg.allowedDomains);
        if (allowedDomains.isEmpty()) for (String seed : cfg.seeds) {
            String h = Util.host(Util.normalize(seed));
            if (!h.isEmpty() && !allowedDomains.contains(h)) allowedDomains.add(h);
        }
    }

    // ---------------------------------------------------------------- HTML DOM
    public static List<Elem> parseHtml(String html) {
        List<Elem> out = new ArrayList<>();
        Deque<Integer> stack = new ArrayDeque<>();
        Matcher m = TAG.matcher(html == null ? "" : html);
        int cursor = 0;
        while (m.find()) {
            appendText(out, stack, html.substring(cursor, m.start()));
            cursor = m.end();
            String tag = m.group(2).toLowerCase(Locale.ROOT);
            boolean closing = !m.group(1).isEmpty();
            String rawAttrs = m.group(3) == null ? "" : m.group(3);
            boolean selfClosing = !m.group(4).isEmpty() || VOID.contains(tag);
            if (closing) {
                int idx = matchingOpen(out, stack, tag);
                if (idx >= 0) while (!stack.isEmpty() && stack.peek() >= idx) stack.pop();
                continue;
            }
            int parent = stack.isEmpty() ? -1 : stack.peek();
            Elem e = new Elem(tag, parent);
            Matcher am = ATTR.matcher(rawAttrs);
            while (am.find()) {
                String value = am.group(2) != null ? am.group(2)
                    : am.group(3) != null ? am.group(3)
                    : am.group(4) != null ? am.group(4) : "";
                e.attrs.put(am.group(1).toLowerCase(Locale.ROOT), Util.htmlUnescape(value));
            }
            out.add(e);
            if (!selfClosing) stack.push(out.size() - 1);
        }
        appendText(out, stack, html.substring(cursor));
        return out;
    }

    private static void appendText(List<Elem> elems, Deque<Integer> stack, String raw) {
        if (raw.isEmpty() || stack.isEmpty()) return;
        String text = Util.htmlUnescape(raw.replaceAll("<[^>]*>", " "))
            .replaceAll("\\s+", " ");
        if (text.isBlank()) return;
        elems.get(stack.peek()).text.append(text);
    }

    private static int matchingOpen(List<Elem> elems, Deque<Integer> stack, String tag) {
        for (int idx : stack) if (elems.get(idx).tag.equals(tag)) return idx;
        return -1;
    }

    public static boolean matchesSimple(Elem e, String simple) {
        if (simple.isEmpty()) return true;
        String head = simple.trim();
        StringBuilder tag = new StringBuilder();
        String classes = "", id = "", attrName = "", attrValue = null;
        int bracket = head.indexOf('[');
        if (bracket >= 0) {
            String inside = head.substring(bracket + 1).replaceAll("\\].*$", "");
            int eq = inside.indexOf('=');
            if (eq >= 0) {
                attrName = inside.substring(0, eq).trim();
                attrValue = inside.substring(eq + 1).trim().replaceAll("^[\"']|[\"']$", "");
            } else attrName = inside.trim();
            head = head.substring(0, bracket);
        }
        Matcher m = Pattern.compile("[.#]?[\\w:-]+").matcher(head);
        while (m.find()) {
            String token = m.group();
            if (token.startsWith(".")) classes = token.substring(1);
            else if (token.startsWith("#")) id = token.substring(1);
            else tag.append(token);
        }
        if (!tag.isEmpty() && !e.tag.equals(tag.toString().toLowerCase(Locale.ROOT))) return false;
        if (!id.isEmpty() && !id.equals(e.attr("id"))) return false;
        if (!classes.isEmpty() && !(" " + e.classes() + " ").contains(" " + classes + " ")) return false;
        if (!attrName.isEmpty()) {
            String actual = e.attr(attrName);
            if (actual.isEmpty()) return false;
            if (attrValue != null && !actual.equalsIgnoreCase(attrValue)) return false;
        }
        return true;
    }

    public static List<Elem> select(List<Elem> elems, String selector) {
        String[] parts = selector.trim().split("\\s+");
        List<Elem> out = new ArrayList<>();
        for (int i = 0; i < elems.size(); i++) {
            if (!matchesSimple(elems.get(i), parts[parts.length - 1])) continue;
            int ancestor = elems.get(i).parent;
            boolean ok = true;
            for (int p = parts.length - 2; p >= 0 && ok; p--) {
                boolean found = false;
                while (ancestor >= 0) {
                    if (matchesSimple(elems.get(ancestor), parts[p])) { found = true; ancestor = elems.get(ancestor).parent; break; }
                    ancestor = elems.get(ancestor).parent;
                }
                ok = found;
            }
            if (ok) out.add(elems.get(i));
        }
        return out;
    }

    // ---------------------------------------------------------------- 抽取入口
    public Models.Extracted extract(Models.Response resp, int depth) {
        String text = resp.text();
        String mime = resp.contentType().split(";")[0].trim().toLowerCase(Locale.ROOT);
        List<Elem> elems = List.of();
        boolean html = mime.contains("html") || mime.contains("xml") || mime.contains("rss")
            || mime.contains("atom") || text.trim().startsWith("<!doctype html")
            || text.trim().startsWith("<html");
        if (html) elems = parseHtml(text);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("url", resp.url());
        item.put("status", resp.status());
        item.put("content_type", resp.contentType());
        item.put("elapsed_ms", resp.elapsedMs());
        item.put("fetched_at", Util.nowIso());

        if (html) {
            item.put("title", firstText(elems, "h1", "title", "og:title"));
            item.put("description", metaContent(elems, "description", "og:description"));
            item.put("canonical", canonical(elems));
            item.put("text", visibleText(text));
            item.put("headings", selectTexts(elems, "h2", 10));
        }
        if (mime.contains("json") || text.trim().startsWith("{") || text.trim().startsWith("[")) {
            try { item.put("json", Json.parse(text)); } catch (RuntimeException ignored) { }
        }

        List<Models.Request> links = new ArrayList<>();
        if (!cfg.extractMode.equals("raw")) links.addAll(discoverLinks(resp, elems, text, depth, html));
        if (!cfg.extractMode.equals("raw")) applyRules(item, resp.url(), elems, text);

        item.put("links_count", links.size());
        String hash = Util.sha256(text);
        Models.Item record = new Models.Item(item, resp.url(), "page", hash);
        return new Models.Extracted(record, links, "", false);
    }

    private String visibleText(String html) {
        String s = SCRIPT.matcher(html).replaceAll(" ");
        s = s.replaceAll("<[^>]*>", " ");
        s = Util.htmlUnescape(s).replaceAll("\\s+", " ").trim();
        return s.length() > 10_000 ? s.substring(0, 10_000) : s;
    }

    private static String firstText(List<Elem> elems, String... selectors) {
        for (String s : selectors) {
            if (s.startsWith("og:")) {
                String value = metaContent(elems, s);
                if (!value.isEmpty()) return value;
                continue;
            }
            List<Elem> found = select(elems, s);
            if (!found.isEmpty() && !found.get(0).text().isEmpty()) return found.get(0).text();
        }
        return "";
    }

    private static List<String> selectTexts(List<Elem> elems, String selector, int limit) {
        List<String> out = new ArrayList<>();
        for (Elem e : select(elems, selector)) {
            if (!e.text().isEmpty()) out.add(e.text());
            if (out.size() >= limit) break;
        }
        return out;
    }

    private static String metaContent(List<Elem> elems, String... names) {
        for (String name : names) for (Elem e : select(elems, "meta")) {
            String key = e.attr("name").isEmpty() ? e.attr("property") : e.attr("name");
            if (key.equalsIgnoreCase(name)) return e.attr("content");
        }
        return "";
    }

    private static String canonical(List<Elem> elems) {
        for (Elem e : select(elems, "link")) {
            if (e.attr("rel").equalsIgnoreCase("canonical")) return e.attr("href");
        }
        return "";
    }

    // ---------------------------------------------------------------- 链接发现
    private List<Models.Request> discoverLinks(Models.Response resp, List<Elem> elems,
                                               String text, int depth, boolean html) {
        List<String> raw = new ArrayList<>();
        boolean nofollowPage = false;
        if (html) {
            for (Elem e : select(elems, "meta")) {
                String name = e.attr("name");
                if (name.equalsIgnoreCase("robots") && e.attr("content").toLowerCase(Locale.ROOT).contains("nofollow"))
                    nofollowPage = true;
            }
            if (!nofollowPage || !cfg.respectNofollow) {
                for (Elem e : select(elems, "a")) {
                    if (cfg.respectNofollow && e.attr("rel").toLowerCase(Locale.ROOT).contains("nofollow")) continue;
                    raw.add(e.attr("href"));
                }
            }
            for (Elem e : select(elems, "loc")) raw.add(e.text());
            for (Elem e : select(elems, "link")) {
                String href = e.attr("href");
                if (href.isEmpty()) href = e.text();
                if (e.attr("rel").toLowerCase(Locale.ROOT).contains("alternate") || !href.isEmpty()) raw.add(href);
            }
            for (Elem e : select(elems, "guid")) raw.add(e.text());
        }
        if (text.trim().startsWith("{") || text.trim().startsWith("[")) {
            try { collectJsonUrls(Json.parse(text), raw, 0); } catch (RuntimeException ignored) { }
        }

        List<Models.Request> out = new ArrayList<>();
        for (String href : raw) {
            String url = Util.normalize(resp.url(), href);
            if (url.isEmpty() || !shouldFollow(url, depth + 1)) continue;
            String kind = url.contains("sitemap") || href.startsWith("<loc>") ? "sitemap" : "page";
            out.add(new Models.Request(url, depth + 1, kind.equals("sitemap") ? 50 : 0, kind));
        }
        return out;
    }

    private static void collectJsonUrls(Object node, List<String> out, int depth) {
        if (depth > 8 || out.size() > 500) return;
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey()).toLowerCase(Locale.ROOT);
                Object value = e.getValue();
                if (LINK_KEYS.contains(key) && value instanceof String s && s.startsWith("http")) out.add(s);
                else collectJsonUrls(value, out, depth + 1);
            }
        } else if (node instanceof List<?> list) {
            for (Object o : list) collectJsonUrls(o, out, depth + 1);
        }
    }

    public boolean shouldFollow(String url, int depth) {
        if (url.isEmpty() || depth > cfg.maxDepth) return false;
        String host = Util.host(url);
        if (host.isEmpty()) return false;
        if (!cfg.followExternal && !allowedDomains.isEmpty()) {
            boolean allowed = false;
            for (String d : allowedDomains) if (cfg.includeSubdomains
                ? Util.sameOrSubdomain(host, d) : host.equalsIgnoreCase(d)) { allowed = true; break; }
            if (!allowed) return false;
        }
        if (!cfg.allowUrlPatterns.isEmpty() && !Util.matchesAny(url, cfg.allowUrlPatterns)) return false;
        if (Util.matchesAny(url, cfg.denyUrlPatterns)) return false;
        return !Util.hasSkipExtension(url);
    }

    // ---------------------------------------------------------------- 规则
    private void applyRules(Map<String, Object> item, String url, List<Elem> elems, String text) {
        for (Map<String, Object> rule : cfg.rules) {
            String pattern = Json.str(rule, "url_pattern", "");
            if (!pattern.isEmpty() && !url.contains(pattern)) {
                try { if (!Pattern.compile(pattern).matcher(url).find()) continue; }
                catch (RuntimeException ignored) { continue; }
            }
            Map<String, Object> fields = Json.obj(rule.get("fields"));
            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                Map<String, Object> spec = Json.obj(entry.getValue());
                Object value = fieldValue(spec, elems, text);
                if (value != null) item.put(entry.getKey(), value);
            }
        }
    }

    private static Object fieldValue(Map<String, Object> spec, List<Elem> elems, String text) {
        String selector = Json.str(spec, "selector", "");
        String path = Json.str(spec, "path", "");
        String regex = Json.str(spec, "regex", "");
        String attr = Json.str(spec, "attr", "");
        boolean many = Json.bool(spec, "many", false);
        String transform = Json.str(spec, "transform", "strip");

        List<String> values = new ArrayList<>();
        if (!path.isEmpty()) {
            try {
                Object v = jsonPath(Json.parse(text), path);
                if (v instanceof List<?> list) for (Object o : list) values.add(String.valueOf(o));
                else if (v != null) values.add(String.valueOf(v));
            } catch (RuntimeException ignored) { }
        } else if (!selector.isEmpty()) {
            for (Elem e : select(elems, selector))
                values.add(attr.isEmpty() ? e.text() : e.attr(attr));
        } else if (!regex.isEmpty()) {
            Matcher m = Pattern.compile(regex).matcher(text);
            if (m.find()) values.add(m.groupCount() > 0 ? m.group(1) : m.group());
        }
        if (values.isEmpty()) return null;
        List<Object> cooked = new ArrayList<>();
        for (String v : values) {
            String s = v.trim();
            if (!regex.isEmpty() && selector.isEmpty() && path.isEmpty()) {
                Matcher m = Pattern.compile(regex).matcher(s);
                if (m.find()) s = m.groupCount() > 0 ? m.group(1) : m.group();
            }
            switch (transform) {
                case "int" -> { try { cooked.add(Integer.parseInt(s.replaceAll("[^0-9-]", ""))); continue; } catch (NumberFormatException ignored) { } }
                case "float" -> { try { cooked.add(Double.parseDouble(s.replaceAll("[^0-9.-]", ""))); continue; } catch (NumberFormatException ignored) { } }
                case "lower" -> s = s.toLowerCase(Locale.ROOT);
                case "upper" -> s = s.toUpperCase(Locale.ROOT);
                default -> { }
            }
            cooked.add(s);
        }
        return many ? cooked : cooked.get(0);
    }

    /** 轻量 JSONPath：$.a.b[0].c，支持数组下标与通配符。 */
    public static Object jsonPath(Object root, String path) {
        Object cur = root;
        for (String raw : path.replaceFirst("^\\$", "").split("\\.")) {
            if (raw.isEmpty()) continue;
            Matcher m = Pattern.compile("([^\\[\\]]+)?(?:\\[(\\d+|\\*)\\])?").matcher(raw);
            if (!m.matches()) return null;
            String key = m.group(1), index = m.group(2);
            if (key != null && !key.isEmpty()) {
                if (!(cur instanceof Map<?, ?> map)) return null;
                cur = map.get(key);
            }
            if (index != null) {
                if (!(cur instanceof List<?> list)) return null;
                if (index.equals("*")) return list;
                int i = Integer.parseInt(index);
                if (i < 0 || i >= list.size()) return null;
                cur = list.get(i);
            }
            if (cur == null) return null;
        }
        return cur;
    }
}
