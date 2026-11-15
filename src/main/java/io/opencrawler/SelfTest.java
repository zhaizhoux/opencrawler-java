package io.opencrawler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 内置自测：不访问公网，覆盖 JSON、robots、抽取、限速、续爬、端到端抓取与桌面接口。 */
public final class SelfTest {
    private static final List<String> FAILURES = new ArrayList<>();
    private SelfTest() {}

    private static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name + (detail.isEmpty() ? "" : " — " + detail));
        if (!ok) FAILURES.add(name);
    }

    public static boolean run() {
        FAILURES.clear();
        testJson();
        testRobots();
        testExtractor();
        testAdaptive();
        testLimiter();
        testFrontier();
        testEndToEnd();
        testStorageQuery();
        testDesktopApi();
        System.out.println(FAILURES.isEmpty() ? "SELFTEST PASS" : "SELFTEST FAIL: " + FAILURES);
        return FAILURES.isEmpty();
    }

    private static void testJson() {
        try {
            String src = "{\"name\":\"中文爬虫\",\"n\":12,\"f\":1.5,\"ok\":true,\"nil\":null,"
                + "\"arr\":[1,\"a\\nb\",{\"k\":\"v\"}]}";
            Map<String, Object> m = Json.obj(Json.parse(src));
            String round = Json.stringify(m);
            Map<String, Object> again = Json.obj(Json.parse(round));
            check("JSON 解析与序列化往返", "中文爬虫".equals(again.get("name"))
                && Json.arr(again.get("arr")).size() == 3
                && Long.valueOf(12).equals(again.get("n")), round.length() + " 字符");
        } catch (Exception e) { check("JSON 解析与序列化往返", false, e.toString()); }
    }

    private static void testRobots() {
        Robots r = new Robots(url -> new Models.Response(url, 200, "text/plain",
            Map.of(), new byte[0], 0), new Config(Map.of()));
        Robots.Rules rules = r.parse("""
            User-agent: *
            Disallow: /private
            Allow: /private/open
            Crawl-delay: 1
            Sitemap: https://example.com/sitemap.xml

            User-agent: OpenCrawlerJava
            Disallow: /lab-only
            Crawl-delay: 2
            """);
        check("robots Allow 优先于同长度 Disallow", rules.canFetch("/private/open"), "");
        check("robots Disallow 生效", !rules.canFetch("/private/x") && !rules.canFetch("/lab-only/x"), "");
        check("robots Crawl-delay 取较严格者", rules.crawlDelayMs == 2000.0,
            rules.crawlDelayMs + " ms");
        check("robots Sitemap 解析", rules.sitemaps.size() == 1, rules.sitemaps.toString());
    }

    private static void testExtractor() {
        List<Extractor.Elem> elems = Extractor.parseHtml(
            "<div class='post'><h1 id='t'>标题</h1><p class='body'>正文</p>"
            + "<a href='/a' class='link'>A</a></div>");
        check("CSS 选择器 tag / .class / #id",
            !Extractor.select(elems, "h1").isEmpty()
                && !Extractor.select(elems, ".body").isEmpty()
                && !Extractor.select(elems, "#t").isEmpty()
                && Extractor.select(elems, "div.post p").size() == 1, "");
        check("CSS 属性选择器", Extractor.select(elems, "a[href]").size() == 1, "");
        Object v = Extractor.jsonPath(Json.parse("{\"a\":{\"b\":[{\"c\":42}]}}"), "$.a.b[0].c");
        check("JSONPath 抽取", Long.valueOf(42).equals(v), String.valueOf(v));
    }

    private static void testAdaptive() {
        Adaptive fast = new Adaptive(16, 1000, 0);
        for (int i = 0; i < 30; i++) fast.record(10, true);
        check("AIMD 健康时加性增并发", fast.limit() > 16, "limit=" + fast.limit());
        Adaptive broken = new Adaptive(32, 1000, 0);
        for (int i = 0; i < 30; i++) broken.record(10, false);
        check("AIMD 高错误率乘性减并发", broken.limit() < 32, "limit=" + broken.limit());
    }

    private static void testLimiter() {
        try {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("default_delay_ms", 20);
            o.put("per_domain_rps", 100);
            o.put("per_domain_concurrency", 8);
            Limiter limiter = new Limiter(new Config(o));
            AtomicInteger current = new AtomicInteger(), max = new AtomicInteger();
            List<Thread> threads = new ArrayList<>();
            long t0 = System.nanoTime();
            for (int i = 0; i < 8; i++) {
                Thread t = new Thread(() -> {
                    try (Limiter.Slot ignored = limiter.acquire("demo.local", 0)) {
                        max.accumulateAndGet(current.incrementAndGet(), Math::max);
                        Thread.sleep(80);
                        current.decrementAndGet();
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                threads.add(t);
                t.start();
            }
            for (Thread t : threads) t.join();
            double ms = (System.nanoTime() - t0) / 1e6;
            check("每域限速器允许并发而非串行", max.get() >= 2 && ms < 1500.0,
                "max=" + max.get() + ", " + Math.round(ms) + "ms");
        } catch (Exception e) { check("每域限速器允许并发而非串行", false, e.toString()); }
    }

    private static void testFrontier() {
        try {
            Path tmp = Files.createTempDirectory("ocl-java-frontier");
            String state = tmp.resolve("state.tsv").toString();
            Frontier f = new Frontier(state);
            f.add(Models.Request.page("https://a.example/1", 0));
            f.add(Models.Request.page("https://a.example/2", 0));
            Models.Request first = f.poll(100);
            if (first != null) f.markDone(first.url(), "h1");
            f.close();
            Frontier again = new Frontier(state);
            int queued = ((Number) again.counts().get("queued")).intValue();
            check("持久队列断点续爬", queued == 1, "恢复待抓 " + queued + " 条");
            again.close();
        } catch (Exception e) { check("持久队列断点续爬", false, e.toString()); }
    }

    private static void testEndToEnd() {
        try (Demo demo = new Demo(0)) {
            Path tmp = Files.createTempDirectory("ocl-java-e2e");
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("seeds", List.of(demo.base() + "/"));
            o.put("output_dir", tmp.toString());
            o.put("state_file", tmp.resolve("state.tsv").toString());
            o.put("concurrency", 8);
            o.put("per_domain_concurrency", 4);
            o.put("default_delay_ms", 0);
            o.put("per_domain_rps", 100);
            o.put("max_pages", 80);
            o.put("max_depth", 3);
            o.put("respect_robots", true);
            o.put("follow_sitemaps", true);
            o.put("retries", 0);
            o.put("request_timeout_seconds", 8);
            o.put("adaptive_concurrency", false);
            o.put("metrics_interval", 5);
            Engine engine = new Engine(new Config(o));
            Map<String, Object> summary = engine.run();
            List<Map<String, Object>> rows = Storage.tail(tmp.toString(), 200);
            List<String> urls = new ArrayList<>();
            for (Map<String, Object> row : rows) urls.add(String.valueOf(row.get("url")));
            check("端到端抓取页面数 >= 5", urls.size() >= 5, "实抓 " + urls.size() + " 页");
            check("robots 禁止路径未被抓取", urls.stream().noneMatch(u -> u.endsWith("/secret")), "");
            check("外链未被抓取", urls.stream().noneMatch(u -> u.contains("example.org")), "");
            check("静态资源未被抓取", urls.stream().noneMatch(u -> u.endsWith(".png")), "");
            check("JSONL 结果可回读且含标题", !rows.isEmpty()
                && Json.obj(rows.get(0).get("data")).containsKey("title"), "");
            check("引擎返回摘要", summary.containsKey("requests")
                && !summary.containsKey("error"), String.valueOf(summary.get("stop_reason")));
        } catch (Exception e) { check("端到端抓取", false, e.toString()); }
    }

    private static void testDesktopApi() {
        try (Desktop desktop = new Desktop(0)) {
            desktop.start();
            HttpClient client = HttpClient.newHttpClient();
            String url = "http://127.0.0.1:" + desktop.port();
            HttpResponse<String> status = client.send(HttpRequest.newBuilder(URI.create(url + "/api/status"))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> html = client.send(HttpRequest.newBuilder(URI.create(url + "/"))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            check("桌面服务 /api/status 与首页", status.statusCode() == 200
                && status.body().contains("version") && html.statusCode() == 200
                && html.body().contains("OpenCrawlerLab"), "");
            check("首页返回正确 Content-Type",
                html.headers().firstValue("Content-Type").orElse("").contains("text/html"), "");
            HttpResponse<String> info = client.send(HttpRequest.newBuilder(URI.create(url + "/api/info"))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            check("桌面服务 /api/info 标识实现", info.statusCode() == 200
                && info.body().contains("\"implementation\":\"java\"")
                && info.body().contains("0.3.0"), "");
            check("状态快照补齐指标字段", status.body().contains("implementation")
                && status.body().contains("uptime_seconds")
                && status.body().contains("duplicates"), "");
            HttpResponse<String> results = client.send(HttpRequest.newBuilder(URI.create(url + "/api/results?limit=5&q=zzz"))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            check("结果接口返回 total/limit/offset", results.statusCode() == 200
                && results.body().contains("\"total\"")
                && results.body().contains("\"offset\""), "");
        } catch (Exception e) { check("桌面服务 /api/status 与首页", false, e.toString()); }
    }

    private static void testStorageQuery() {
        try {
            Path dir = Files.createTempDirectory("ocl-java-query");
            List<String> lines = List.of(
                "{\"url\":\"https://a/one\",\"kind\":\"page\",\"data\":{\"title\":\"Alpha\"}}",
                "{\"url\":\"https://a/two\",\"kind\":\"sitemap\",\"data\":{\"title\":\"Sitemap\"}}",
                "{\"url\":\"https://a/three\",\"kind\":\"page\",\"data\":{\"title\":\"Beta\"}}");
            Files.write(dir.resolve("items.jsonl"), lines, StandardCharsets.UTF_8);
            Map<String, Object> all = Storage.query(dir.toString(), 100, "", "", 0);
            Map<String, Object> pages = Storage.query(dir.toString(), 100, "", "page", 0);
            Map<String, Object> hits = Storage.query(dir.toString(), 100, "alpha", "", 0);
            Map<String, Object> older = Storage.query(dir.toString(), 2, "", "", 1);
            check("结果筛选：全量/类型/关键字/offset",
                Json.intVal(all, "total", -1) == 3
                    && Json.intVal(pages, "total", -1) == 2
                    && Json.intVal(hits, "total", -1) == 1
                    && Json.arr(older.get("items")).size() == 2, "");
        } catch (Exception e) { check("结果筛选：全量/类型/关键字/offset", false, e.toString()); }
    }
}
