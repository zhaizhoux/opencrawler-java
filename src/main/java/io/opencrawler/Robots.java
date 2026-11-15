package io.opencrawler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** robots.txt：UA 分组、最长路径匹配、Crawl-delay 与 Sitemap，失败默认拒绝。 */
public final class Robots {
    public interface TextProvider {
        Models.Response get(String url);
    }

    public static final class Rules {
        final List<String[]> entries = new ArrayList<>(); // {allow?, path}
        double crawlDelayMs;
        final List<String> sitemaps = new ArrayList<>();

        boolean canFetch(String path) {
            int best = -1;
            boolean allow = true;
            for (String[] e : entries) {
                String prefix = e[1];
                if (prefix.isEmpty() || path.startsWith(prefix)) {
                    boolean thisAllow = Boolean.parseBoolean(e[0]);
                    // 最长路径优先；同长度时 Allow 优先于 Disallow
                    if (prefix.length() > best
                        || (prefix.length() == best && thisAllow && !allow)) {
                        best = prefix.length();
                        allow = thisAllow;
                    }
                }
            }
            return allow;
        }
    }

    private final TextProvider provider;
    private final Config cfg;
    private final Logger log = Logger.getLogger("opencrawler.robots");
    private final Map<String, Rules> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> fetchedAt = new ConcurrentHashMap<>();
    private final String uaToken;

    public Robots(TextProvider provider, Config cfg) {
        this.provider = provider;
        this.cfg = cfg;
        String ua = cfg.userAgent.toLowerCase(Locale.ROOT);
        uaToken = ua.split("[/\\s]")[0];
    }

    private Rules rules(String url) {
        if (!cfg.respectRobots) return new Rules();
        String host = Util.host(url);
        long now = System.currentTimeMillis();
        Long at = fetchedAt.get(host);
        if (at != null && now - at < 3_600_000L) {
            Rules cached = cache.get(host);
            if (cached != null) return cached;
        }
        Rules rules = new Rules();
        String robotsUrl = Util.baseUrl(url) + "/robots.txt";
        try {
            Models.Response resp = provider.get(robotsUrl);
            if (resp.status() == 200) rules = parse(resp.text());
            else if (resp.status() != 404 && cfg.robotsOnError.equals("deny")) rules = denyAll();
        } catch (Exception e) {
            log.fine("获取 robots.txt 失败 " + robotsUrl + ": " + e.getMessage());
            if (cfg.robotsOnError.equals("deny")) rules = denyAll();
        }
        cache.put(host, rules);
        fetchedAt.put(host, now);
        return rules;
    }

    private static Rules denyAll() {
        Rules r = new Rules();
        r.entries.add(new String[]{"false", "/"});
        return r;
    }

    /** 解析 robots.txt。本 UA 组与通配组保守合并，Crawl-delay 取较严格者。 */
    public Rules parse(String text) {
        Rules out = new Rules();
        Set<String> group = new LinkedHashSet<>();
        boolean inRules = false;
        for (String raw : text.split("\\R")) {
            String line = raw.split("#", 2)[0].trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon < 1) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            switch (key) {
                case "user-agent" -> {
                    if (inRules) { group.clear(); inRules = false; }
                    group.add(value.toLowerCase(Locale.ROOT));
                }
                case "sitemap" -> { if (!value.isEmpty()) out.sitemaps.add(value); }
                case "crawl-delay" -> {
                    inRules = true;
                    if (relevant(group)) try { out.crawlDelayMs = Math.max(out.crawlDelayMs,
                        Double.parseDouble(value) * 1000); } catch (NumberFormatException ignored) { }
                }
                case "allow", "disallow" -> {
                    inRules = true;
                    if (relevant(group)) out.entries.add(new String[]{
                        String.valueOf(key.equals("allow")), value});
                }
                default -> { /* 忽略未知指令 */ }
            }
        }
        return out;
    }

    private boolean relevant(Set<String> group) {
        if (group.isEmpty()) return false;
        for (String g : group) if (g.equals("*") || g.contains(uaToken) || uaToken.contains(g)) return true;
        return false;
    }

    public boolean allowed(String url) { return rules(url).canFetch(Util.path(url)); }

    public long crawlDelayMs(String url) {
        return cfg.obeyCrawlDelay ? (long) rules(url).crawlDelayMs : 0L;
    }

    public List<String> sitemaps(String url) { return new ArrayList<>(rules(url).sitemaps); }

    /** 供自测使用的规则快照。 */
    public Map<String, Object> debug(String url) {
        Rules r = rules(url);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("crawl_delay_ms", r.crawlDelayMs);
        m.put("sitemaps", r.sitemaps);
        m.put("rules", r.entries.size());
        return m;
    }
}
