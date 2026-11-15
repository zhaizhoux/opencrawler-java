package io.opencrawler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 命令行入口：crawl / fetch / demo / selftest / desktop / validate / init / version。 */
public final class Main {
    private Main() {}

    public static void main(String[] args) { System.exit(run(args)); }

    static int run(String[] args) {
        List<String> argv = List.of(args);
        String command = !argv.isEmpty() && !argv.get(0).startsWith("--") ? argv.get(0) : "crawl";
        int from = !argv.isEmpty() && !argv.get(0).startsWith("--") ? 1 : 0;
        Map<String, Object> overrides = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        List<String> seeds = new ArrayList<>();
        String configPath = "";
        for (int i = from; i < argv.size(); i++) {
            String a = argv.get(i);
            if (a.startsWith("--")) {
                String key = a.substring(2).replace('-', '_');
                String value = "";
                int eq = key.indexOf('=');
                if (eq > 0) { value = key.substring(eq + 1); key = key.substring(0, eq); }
                else if (i + 1 < argv.size() && !argv.get(i + 1).startsWith("--")) value = argv.get(++i);
                if (key.equals("config")) { configPath = value; continue; }
                if (key.equals("seed") || key.equals("seeds")) {
                    for (String s : value.split("[,;]")) if (!s.isBlank()) seeds.add(s.trim());
                    continue;
                }
                overrides.put(key, value.isEmpty() ? Boolean.TRUE : value);
            } else if (a.equals("-c") && i + 1 < argv.size()) configPath = argv.get(++i);
            else if (a.equals("-o") && i + 1 < argv.size()) overrides.put("output", argv.get(++i));
            else positional.add(a);
        }
        if (!seeds.isEmpty()) overrides.put("seeds", seeds);
        if (!configPath.isEmpty()) {
            // 配置打底，命令行参数优先
            Map<String, Object> merged = new LinkedHashMap<>(Config.fromFile(configPath).toMap());
            merged.putAll(overrides);
            overrides = merged;
        }

        try {
            switch (command) {
                case "version" -> { System.out.println("OpenCrawlerJava " + Config.VERSION); return 0; }
                case "selftest" -> { configureLogging("WARNING"); return SelfTest.run() ? 0 : 1; }
                case "demo" -> {
                    int port = intOption(overrides, "port", 8765);
                    try (Demo demo = new Demo(port)) {
                        System.out.println("演示站点: " + demo.base() + " （Ctrl+C 结束）");
                        new CountDownLatch(1).await();
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return 0;
                }
                case "desktop" -> {
                    int port = intOption(overrides, "port", 8757);
                    try (Desktop desktop = new Desktop(port)) {
                        desktop.start();
                        if (!overrides.containsKey("no_browser"))
                            Desktop.openDesktopWindow("http://127.0.0.1:" + desktop.port() + "/");
                        System.out.println("桌面窗口地址: http://127.0.0.1:" + desktop.port() + "/（Ctrl+C 结束）");
                        new CountDownLatch(1).await();
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return 0;
                }
                case "init" -> {
                    Map<String, Object> sample = new Config(Map.of("seeds", List.of("https://example.com/"))).toMap();
                    sample.put("seeds", List.of("https://example.com/"));
                    sample.put("rules", List.of(Map.of("name", "article", "url_pattern", "/article/",
                        "fields", Map.of("title", Map.of("selector", "h1"),
                            "date", Map.of("selector", "time.published", "attr", "datetime")))));
                    Path out = Path.of(String.valueOf(overrides.getOrDefault("output", "crawler.config.json")));
                    Files.writeString(out, Json.stringify(sample) + System.lineSeparator(), StandardCharsets.UTF_8);
                    System.out.println("已生成示例配置: " + out);
                    return 0;
                }
                case "validate" -> {
                    Config cfg = new Config(overrides);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("valid", true);
                    out.put("warnings", cfg.warnings);
                    out.put("config", cfg.toMap());
                    System.out.println(Json.stringify(out));
                    return 0;
                }
                case "fetch" -> {
                    if (positional.isEmpty()) { System.err.println("用法: fetch <url>"); return 2; }
                    return fetch(positional.get(0), overrides);
                }
                case "crawl" -> {
                    if (seeds.isEmpty() && configPath.isEmpty() && !overrides.containsKey("seeds")) {
                        System.err.println("请提供 --seed <URL> 或 --config <file>");
                        return 2;
                    }
                    Config cfg = new Config(overrides);
                    configureLogging(cfg.logLevel);
                    Map<String, Object> summary = new Engine(cfg).run();
                    System.out.println(Json.stringify(summary));
                    return 0;
                }
                default -> {
                    System.err.println("未知命令: " + command + "（crawl/fetch/demo/selftest/desktop/validate/init/version）");
                    return 2;
                }
            }
        } catch (Exception e) {
            System.err.println("错误: " + e);
            return 1;
        }
    }

    private static int fetch(String url, Map<String, Object> overrides) {
        Config cfg = new Config(overrides);
        Limiter limiter = new Limiter(cfg);
        try (Fetcher fetcher = new Fetcher(cfg, limiter)) {
            if (cfg.respectRobots && !new Robots(fetcher, cfg).allowed(url)) {
                System.out.println(Json.stringify(Map.of("error", "按 robots.txt 拒绝抓取")));
                return 3;
            }
            Models.Outcome outcome = fetcher.fetch(Models.Request.page(url, 0));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("url", url);
            if (!outcome.success()) { out.put("error", outcome.error().isEmpty() ? outcome.skipped() : outcome.error()); }
            else {
                Models.Extracted ex = new Extractor(cfg).extract(outcome.response(), 0);
                out.put("status", outcome.response().status());
                out.put("content_type", outcome.response().contentType());
                out.put("elapsed_ms", outcome.response().elapsedMs());
                out.put("item", ex.item().data());
                List<String> links = new ArrayList<>();
                for (Models.Request r : ex.links()) links.add(r.url());
                out.put("links", links);
            }
            System.out.println(Json.stringify(out));
            return 0;
        }
    }

    private static int intOption(Map<String, Object> overrides, String key, int def) {
        return Json.intVal(overrides, key, def);
    }

    private static void configureLogging(String level) {
        Logger root = Logger.getLogger("opencrawler");
        try { root.setLevel(Level.parse(level.toUpperCase())); }
        catch (IllegalArgumentException e) { root.setLevel(Level.INFO); }
    }
}
