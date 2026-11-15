package io.opencrawler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 配置：一份 JSON 对象即可描述全部行为；命令行参数作为覆盖层合并。 */
public final class Config {
    public static final String VERSION = "0.2.0";

    private static Map<String, Object> defaults() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("seeds", List.of());
        d.put("allowed_domains", List.of());
        d.put("include_subdomains", true);
        d.put("follow_external", false);
        d.put("allow_url_patterns", List.of());
        d.put("deny_url_patterns", Util.DEFAULT_DENY);
        d.put("respect_nofollow", true);
        d.put("follow_sitemaps", true);
        d.put("respect_robots", true);
        d.put("robots_on_error", "deny");
        d.put("obey_crawl_delay", true);
        d.put("user_agent", Util.DEFAULT_UA);
        d.put("default_delay_ms", 500);
        d.put("per_domain_rps", 2.0);
        d.put("concurrency", 32);
        d.put("adaptive_concurrency", true);
        d.put("adaptive_target_latency_ms", 1200);
        d.put("per_domain_concurrency", 4);
        d.put("request_timeout_seconds", 20);
        d.put("retries", 2);
        d.put("max_content_length", 5_000_000L);
        d.put("max_depth", 5);
        d.put("max_pages", 1_000);
        d.put("max_bytes", 512L * 1024 * 1024);
        d.put("time_budget_seconds", 0);
        d.put("conditional_requests", true);
        d.put("extract_mode", "auto");
        d.put("rules", List.of());
        d.put("storage", "jsonl");
        d.put("dedupe_content", true);
        d.put("output_dir", "./output");
        d.put("state_file", "");
        d.put("log_level", "INFO");
        d.put("proxy", "");
        d.put("dry_run", false);
        d.put("metrics_interval", 15);
        return d;
    }

    private final Map<String, Object> v;
    public final List<String> seeds;
    public final List<String> allowedDomains;
    public final List<String> denyUrlPatterns;
    public final List<String> allowUrlPatterns;
    public final List<Map<String, Object>> rules;
    public final boolean includeSubdomains, followExternal, respectNofollow, followSitemaps;
    public final boolean respectRobots, obeyCrawlDelay, adaptiveConcurrency, dedupeContent;
    public final boolean conditionalRequests, dryRun;
    public final String robotsOnError, userAgent, extractMode, storage, outputDir, stateFile;
    public final String logLevel, proxy;
    public final int defaultDelayMs, concurrency, perDomainConcurrency, maxDepth, maxPages;
    public final int timeBudgetSeconds, retries, adaptiveTargetLatencyMs, metricsInterval;
    public final int requestTimeoutSeconds;
    public final double perDomainRps;
    public final long maxContentLength, maxBytes;
    public final List<String> warnings = new ArrayList<>();

    public Config(Map<String, Object> input) {
        Map<String, Object> merged = defaults();
        if (input != null) merged.putAll(input);
        // 命令行常把 "32" / "true" 作为字符串传入；按默认值类型统一，
        // 让 toMap() 输出干净的类型，桌面前端回传时不会越来越乱。
        for (var entry : defaults().entrySet()) {
            merged.put(entry.getKey(), coerce(entry.getValue(), merged.get(entry.getKey())));
        }
        v = merged;
        seeds = Json.strings(v, "seeds");
        allowedDomains = Json.strings(v, "allowed_domains");
        denyUrlPatterns = Json.strings(v, "deny_url_patterns");
        allowUrlPatterns = Json.strings(v, "allow_url_patterns");
        rules = new ArrayList<>();
        for (Object o : Json.arr(v.get("rules"))) rules.add(Json.obj(o));

        includeSubdomains = Json.bool(v, "include_subdomains", true);
        followExternal = Json.bool(v, "follow_external", false);
        respectNofollow = Json.bool(v, "respect_nofollow", true);
        followSitemaps = Json.bool(v, "follow_sitemaps", true);
        respectRobots = Json.bool(v, "respect_robots", true);
        obeyCrawlDelay = Json.bool(v, "obey_crawl_delay", true);
        adaptiveConcurrency = Json.bool(v, "adaptive_concurrency", true);
        dedupeContent = Json.bool(v, "dedupe_content", true);
        conditionalRequests = Json.bool(v, "conditional_requests", true);
        dryRun = Json.bool(v, "dry_run", false);

        robotsOnError = Json.str(v, "robots_on_error", "deny");
        userAgent = Json.str(v, "user_agent", Util.DEFAULT_UA);
        extractMode = Json.str(v, "extract_mode", "auto");
        storage = Json.str(v, "storage", "jsonl");
        outputDir = Json.str(v, "output_dir", "./output");
        stateFile = Json.str(v, "state_file", "");
        logLevel = Json.str(v, "log_level", "INFO");
        proxy = Json.str(v, "proxy", "");

        defaultDelayMs = Json.intVal(v, "default_delay_ms", 500);
        concurrency = Json.intVal(v, "concurrency", 32);
        perDomainConcurrency = Json.intVal(v, "per_domain_concurrency", 4);
        maxDepth = Json.intVal(v, "max_depth", 5);
        maxPages = Json.intVal(v, "max_pages", 1000);
        timeBudgetSeconds = Json.intVal(v, "time_budget_seconds", 0);
        retries = Json.intVal(v, "retries", 2);
        adaptiveTargetLatencyMs = Json.intVal(v, "adaptive_target_latency_ms", 1200);
        metricsInterval = Json.intVal(v, "metrics_interval", 15);
        requestTimeoutSeconds = Json.intVal(v, "request_timeout_seconds", 20);
        perDomainRps = Json.dblVal(v, "per_domain_rps", 2.0);
        maxContentLength = Json.longVal(v, "max_content_length", 5_000_000L);
        maxBytes = Json.longVal(v, "max_bytes", 512L * 1024 * 1024);
        validate();
    }

    public static Config fromFile(String path) {
        try {
            String text = Files.readString(Path.of(path));
            Object parsed = text.trim().startsWith("{") ? Json.parse(text) : parseProperties(text);
            return new Config(Json.obj(parsed));
        } catch (Exception e) {
            throw new IllegalArgumentException("读取配置失败: " + path + " → " + e.getMessage(), e);
        }
    }

    /** 兼容极简 key=value 配置（教学用途），JSON 仍为首选。 */
    private static Map<String, Object> parseProperties(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int eq = s.indexOf('=');
            if (eq < 1) continue;
            String key = s.substring(0, eq).trim(), raw = s.substring(eq + 1).trim();
            Object value = raw;
            if (raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false")) value = Boolean.parseBoolean(raw);
            else if (raw.matches("-?\\d+")) value = Long.parseLong(raw);
            else if (raw.startsWith("[")) try { value = Json.parse(raw); } catch (Exception ignored) { }
            if (key.equals("seed") || key.equals("seeds")) {
                @SuppressWarnings("unchecked") List<Object> list = (List<Object>) out.computeIfAbsent(
                    "seeds", k -> new ArrayList<Object>());
                if (value instanceof List<?> many) list.addAll(many);
                else list.add(value);
            } else out.put(key, value);
        }
        return out;
    }

    public Config with(Map<String, Object> overrides) {
        Map<String, Object> merged = new LinkedHashMap<>(v);
        if (overrides != null) merged.putAll(overrides);
        return new Config(merged);
    }

    public Map<String, Object> toMap() { return new LinkedHashMap<>(v); }

    public String stateFile() {
        return stateFile.isEmpty() ? Path.of(outputDir, "state.tsv").toString() : stateFile;
    }

    private static Object coerce(Object sample, Object raw) {
        if (raw == null) return null;
        if (sample instanceof Boolean) {
            if (raw instanceof Boolean b) return b;
            if (raw instanceof Number n) return n.intValue() != 0;
            return Boolean.parseBoolean(String.valueOf(raw));
        }
        if (sample instanceof Integer) {
            if (raw instanceof Number n) return n.intValue();
            try { return Integer.parseInt(String.valueOf(raw).trim()); }
            catch (NumberFormatException e) { return sample; }
        }
        if (sample instanceof Long) {
            if (raw instanceof Number n) return n.longValue();
            try { return Long.parseLong(String.valueOf(raw).trim()); }
            catch (NumberFormatException e) { return sample; }
        }
        if (sample instanceof Double) {
            if (raw instanceof Number n) return n.doubleValue();
            try { return Double.parseDouble(String.valueOf(raw).trim()); }
            catch (NumberFormatException e) { return sample; }
        }
        return raw;
    }

    private void validate() {
        if (concurrency < 1) throw new IllegalArgumentException("concurrency 必须 >= 1");
        if (perDomainConcurrency < 1) throw new IllegalArgumentException("per_domain_concurrency 必须 >= 1");
        if (maxPages < 1) throw new IllegalArgumentException("max_pages 必须 >= 1");
        if (maxDepth < 0) throw new IllegalArgumentException("max_depth 不能为负");
        if (defaultDelayMs < 0) throw new IllegalArgumentException("default_delay_ms 不能为负");
        if (!List.of("auto", "rules", "both", "raw").contains(extractMode))
            throw new IllegalArgumentException("extract_mode 必须是 auto/rules/both/raw");
        if (!List.of("jsonl", "none").contains(storage))
            throw new IllegalArgumentException("storage 必须是 jsonl 或 none");
        if (!List.of("deny", "allow").contains(robotsOnError))
            throw new IllegalArgumentException("robots_on_error 必须是 deny 或 allow");
        if (!respectRobots) warnings.add("已关闭 robots.txt 检查，请仅用于自有站点的教学实验");
        if (followExternal) warnings.add("允许外链可能扩大抓取范围，请确认已获授权");
        if (defaultDelayMs == 0) warnings.add("请求间隔为 0，公网抓取请设置礼貌限速");
    }
}
