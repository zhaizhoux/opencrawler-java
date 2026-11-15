package io.opencrawler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 调度引擎：每请求一个虚拟线程，同步写法实现高并发，无需回调或响应式框架。 */
public final class Engine {
    private final Config cfg;
    private final Logger log = Logger.getLogger("opencrawler.engine");
    private final Metrics metrics = new Metrics();
    private final AtomicInteger inflight = new AtomicInteger();
    private final AtomicInteger pagesDone = new AtomicInteger();
    private volatile boolean stop = false, running = false, pendingStop = false;
    private volatile String stopReason = "";
    private Frontier frontier;
    private Adaptive adaptive;
    private Map<String, Object> summary;

    public Engine(Config cfg) { this.cfg = cfg; }

    public Map<String, Object> run() {
        if (cfg.dryRun) return dryRun();
        for (String w : cfg.warnings) log.warning("配置提醒: " + w);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Storage storage = new Storage(cfg);
            frontier = new Frontier(cfg.stateFile());
            Limiter limiter = new Limiter(cfg);
            Fetcher fetcher = new Fetcher(cfg, limiter);
            Robots robots = new Robots(fetcher, cfg);
            Extractor extractor = new Extractor(cfg);
            adaptive = cfg.adaptiveConcurrency
                ? new Adaptive(cfg.concurrency, cfg.adaptiveTargetLatencyMs, 2.0) : null;
            running = true;
            if (pendingStop) { stop = true; pendingStop = false; }

            seed(extractor);
            discoverSitemaps(robots);

            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            long lastMetricLog = System.currentTimeMillis();
            long started = System.nanoTime();
            int idleTicks = 0;
            try {
                while (!stop) {
                    if (budgetReached(started)) break;
                    int limit = adaptive == null ? cfg.concurrency : adaptive.limit();
                    if (inflight.get() >= limit) { sleep(5); continue; }
                    Models.Request request = frontier.poll(100);
                    if (request == null) {
                        if (inflight.get() == 0 && frontier.isEmpty()) {
                            if (++idleTicks > 8) { stopReason = "队列已清空"; break; }
                        } else idleTicks = 0;
                        continue;
                    }
                    idleTicks = 0;
                    inflight.incrementAndGet();
                    pool.submit(() -> {
                        try { process(request, fetcher, robots, limiter, extractor, storage); }
                        catch (Exception e) { log.log(Level.FINE, "任务异常 " + request.url(), e); }
                        finally { inflight.decrementAndGet(); }
                    });
                    long now = System.currentTimeMillis();
                    if (now - lastMetricLog > Math.max(1, cfg.metricsInterval) * 1000L) {
                        lastMetricLog = now;
                        log.info(render());
                        writeMetrics();
                    }
                }
            } finally {
                stop = true;
                pool.close();   // Java 21：等待所有虚拟线程收尾后才返回
                writeMetrics();
                log.info("完成: " + (stopReason.isEmpty() ? "达到停止条件" : stopReason) + " | " + render());
            }
            result.putAll(metrics.snapshot(frontier.counts(), 0));
            result.put("stop_reason", stopReason.isEmpty() ? "达到停止条件" : stopReason);
            result.put("state_file", cfg.stateFile());
            result.put("output_dir", cfg.outputDir);
            storage.close();
            frontier.close();
            fetcher.close();
        } catch (Exception e) {
            log.log(Level.SEVERE, "引擎启动失败", e);
            result.put("error", e.toString());
        } finally {
            running = false;
        }
        summary = result;
        return result;
    }

    private Map<String, Object> dryRun() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dry_run", true);
        m.put("seeds", cfg.seeds);
        m.put("config", cfg.toMap());
        m.put("plan", "并发 " + cfg.concurrency + " / 每域 " + cfg.perDomainConcurrency
            + " | 深度 " + cfg.maxDepth + " | 上限 " + cfg.maxPages + " 页 | 限速 "
            + cfg.defaultDelayMs + "ms | robots=" + cfg.respectRobots);
        return m;
    }

    private void seed(Extractor extractor) {
        List<Models.Request> seeds = new ArrayList<>();
        for (String raw : cfg.seeds) {
            String url = Util.normalize(raw);
            if (url.isEmpty() || !extractor.shouldFollow(url, 0)) { log.warning("跳过种子: " + raw); continue; }
            seeds.add(new Models.Request(url, 0, 100, "page"));
        }
        log.info("种子 " + seeds.size() + " 个（新增 " + frontier.addAll(seeds) + "）");
    }

    private void discoverSitemaps(Robots robots) {
        if (!cfg.followSitemaps || !cfg.respectRobots) return;
        List<Models.Request> found = new ArrayList<>();
        for (String seed : cfg.seeds) for (String url : robots.sitemaps(seed)) {
            String normalized = Util.normalize(seed, url);
            if (!normalized.isEmpty()) found.add(new Models.Request(normalized, 0, 50, "sitemap"));
        }
        if (!found.isEmpty()) log.info("从 robots.txt 发现 " + frontier.addAll(found) + " 个 Sitemap");
    }

    private void process(Models.Request request, Fetcher fetcher, Robots robots,
                         Limiter limiter, Extractor extractor, Storage storage) {
        String url = request.url();
        if (stop) { frontier.add(request); return; }   // 停机时未处理的请求回到持久队列
        String host = Util.host(url);
        try {
            if (!robots.allowed(url)) {
                metrics.skipped.incrementAndGet();
                frontier.markDone(url, "robots");
                return;
            }
            long delay = robots.crawlDelayMs(url);
            try (Limiter.Slot ignored = limiter.acquire(host, delay)) {
                Models.Outcome outcome = fetcher.fetch(request);
                if (adaptive != null) adaptive.record(
                    outcome.response() == null ? cfg.requestTimeoutSeconds * 1000.0
                        : outcome.response().elapsedMs(), outcome.error().isEmpty());
                if (!outcome.error().isEmpty()) {
                    metrics.failed.incrementAndGet();
                    if (outcome.error().startsWith("HTTP 4")) frontier.markDone(url, outcome.error());
                    else frontier.markFailed(request, cfg.retries + 1, outcome.error());
                    return;
                }
                if (!outcome.skipped().isEmpty()) {
                    metrics.skipped.incrementAndGet();
                    frontier.markDone(url, outcome.skipped());
                    return;
                }
                Models.Response response = outcome.response();
                metrics.record(response);
                Models.Extracted extracted = extractor.extract(response, request.depth());
                if (storage.write(extracted.item())) metrics.items.incrementAndGet();
                else metrics.duplicates.incrementAndGet();
                if (!extracted.links().isEmpty()) {
                    frontier.addAll(extracted.links());
                    metrics.links.addAndGet(extracted.links().size());
                }
                frontier.markDone(url, extracted.item().hash());
                pagesDone.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            frontier.add(request);
        } catch (Exception e) {
            metrics.failed.incrementAndGet();
            frontier.markFailed(request, cfg.retries + 1, e.toString());
            log.fine("处理失败 " + url + ": " + e);
        }
    }

    private boolean budgetReached(long startedNanos) {
        if (pagesDone.get() >= cfg.maxPages) { stopReason = "达到页数上限 " + cfg.maxPages; return true; }
        if (metrics.bytes.get() >= cfg.maxBytes) { stopReason = "达到流量上限"; return true; }
        if (cfg.timeBudgetSeconds > 0
            && (System.nanoTime() - startedNanos) / 1e9 >= cfg.timeBudgetSeconds) {
            stopReason = "达到时间预算 " + cfg.timeBudgetSeconds + "s"; return true;
        }
        return false;
    }

    private String render() {
        Map<String, Object> s = snapshot();
        return String.format("[%.1fs] 请求 %s | 成功 %s | 失败 %s | 条目 %s | %.2f req/s | 队列 %s",
            s.get("elapsed_seconds"), s.get("requests"), s.get("succeeded"), s.get("failed"),
            s.get("items"), s.get("requests_per_second"),
            ((Map<?, ?>) s.get("frontier")).get("queued"));
    }

    private void writeMetrics() {
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(cfg.outputDir));
            java.nio.file.Path p = java.nio.file.Path.of(cfg.outputDir, "metrics.jsonl");
            java.nio.file.Files.writeString(p, Json.stringify(snapshot()) + System.lineSeparator(),
                java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }

    public void requestStop(String reason) {
        stopReason = reason;
        if (running) stop = true;
        else pendingStop = true;
    }

    public boolean isRunning() { return running; }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = metrics.snapshot(
            frontier == null ? Map.of() : frontier.counts(), inflight.get());
        m.put("version", Config.VERSION);
        m.put("running", running);
        m.put("stop_reason", stopReason);
        m.put("adaptive", adaptive == null ? Map.of("enabled", false) : adaptive.snapshot());
        m.put("summary", summary);
        m.put("error", "");
        m.put("output_dir", cfg.outputDir);
        return m;
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
