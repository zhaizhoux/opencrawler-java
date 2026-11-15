package io.opencrawler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** JDK 21 HttpClient：同步写法 + sendAsync().join()，让虚拟线程正确挂起而不占用载体线程。 */
public final class Fetcher implements Robots.TextProvider, AutoCloseable {
    private static final Set<String> ALLOWED_TYPES = Set.of(
        "text/html", "application/xhtml+xml", "text/plain", "text/xml", "application/xml",
        "application/json", "application/ld+json", "application/rss+xml",
        "application/atom+xml", "text/css");

    private final HttpClient client;
    private final Config cfg;
    private final Limiter limiter;
    private final Map<String, String> etags = new ConcurrentHashMap<>();
    private final Map<String, String> lastModified = new ConcurrentHashMap<>();
    private final Logger log = Logger.getLogger("opencrawler.fetcher");

    public Fetcher(Config cfg, Limiter limiter) {
        this.cfg = cfg;
        this.limiter = limiter;
        HttpClient.Builder b = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL);
        this.client = b.build();
    }

    /** 单次 GET（robots.txt / 调试用），不重试、不过滤类型。 */
    @Override
    public Models.Response get(String url) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(Math.max(1, cfg.requestTimeoutSeconds)))
            .header("User-Agent", cfg.userAgent)
            .header("Accept", "*/*")
            .GET();
        long t0 = System.nanoTime();
        HttpResponse<byte[]> resp = client.sendAsync(rb.build(),
            HttpResponse.BodyHandlers.ofByteArray()).join();
        return new Models.Response(url, resp.statusCode(),
            resp.headers().firstValue("content-type").orElse("application/octet-stream"),
            resp.headers().map(), resp.body(), (System.nanoTime() - t0) / 1_000_000);
    }

    /** 带条件请求、体长限制、429 退避与重试的抓取。 */
    public Models.Outcome fetch(Models.Request request) {
        String url = request.url();
        for (int attempt = 0; attempt <= cfg.retries; attempt++) {
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(1, cfg.requestTimeoutSeconds)))
                    .header("User-Agent", cfg.userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/json,"
                        + "application/xml,text/plain;q=0.9,*/*;q=0.1")
                    .GET();
                if (cfg.conditionalRequests) {
                    String etag = etags.get(url), lm = lastModified.get(url);
                    if (etag != null) rb.header("If-None-Match", etag);
                    if (lm != null) rb.header("If-Modified-Since", lm);
                }
                long t0 = System.nanoTime();
                HttpResponse<byte[]> resp = client.sendAsync(rb.build(),
                    HttpResponse.BodyHandlers.ofByteArray()).join();
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                Map<String, List<String>> headers = new LinkedHashMap<>(resp.headers().map());
                String etag = resp.headers().firstValue("etag").orElse("");
                String lm = resp.headers().firstValue("last-modified").orElse("");
                if (!etag.isEmpty()) etags.put(url, etag);
                if (!lm.isEmpty()) lastModified.put(url, lm);

                int status = resp.statusCode();
                if (status == 304) return Models.Outcome.skip("304 Not Modified");
                if (status == 429 || status == 503) {
                    long penalty = retryAfterSeconds(resp);
                    limiter.penalize(Util.host(url), penalty);
                    if (attempt < cfg.retries) { sleep(penalty); continue; }
                    return Models.Outcome.fail("HTTP " + status);
                }
                if (status >= 400) return Models.Outcome.fail("HTTP " + status);

                String contentType = resp.headers().firstValue("content-type")
                    .orElse("application/octet-stream").toLowerCase();
                String mime = contentType.split(";")[0].trim();
                byte[] body = resp.body();
                if (body.length > cfg.maxContentLength)
                    return Models.Outcome.skip("响应体超过上限 " + cfg.maxContentLength);
                if (!ALLOWED_TYPES.contains(mime) && !mime.endsWith("+xml") && !mime.endsWith("+json"))
                    return Models.Outcome.skip("不支持的 Content-Type: " + mime);
                Models.Response r = new Models.Response(url, status, contentType, headers, body, elapsedMs);
                return Models.Outcome.ok(r);
            } catch (Exception e) {
                if (attempt == cfg.retries)
                    return Models.Outcome.fail(typeName(e) + ": " + e.getMessage());
                sleep((long) Math.pow(2, attempt));
            }
        }
        return Models.Outcome.fail("重试耗尽");
    }

    private long retryAfterSeconds(HttpResponse<byte[]> resp) {
        String v = resp.headers().firstValue("retry-after").orElse("");
        try { return Math.max(1, Math.min(30, Long.parseLong(v.trim()))); }
        catch (NumberFormatException e) { return 3; }
    }

    private static void sleep(long seconds) {
        try { Thread.sleep(Math.min(30, Math.max(1, seconds)) * 1000L); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String typeName(Exception e) {
        String n = e.getClass().getSimpleName();
        return n.isEmpty() ? "Exception" : n;
    }

    @Override public void close() { /* HttpClient 无需显式关闭（JDK 21） */ }
}
