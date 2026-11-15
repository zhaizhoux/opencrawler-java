package io.opencrawler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** 本机内存演示站点：robots.txt、HTML、JSON、RSS、Sitemap 与受限路径，用于自学与自测。 */
public final class Demo implements AutoCloseable {
    private final HttpServer server;
    private final String base;

    public Demo(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 32);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    public String base() { return base; }
    public int port() { return server.getAddress().getPort(); }
    public String robotsUrl() { return base + "/robots.txt"; }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String body;
        String type = "text/html; charset=utf-8";
        int status = 200;
        if (path.equals("/robots.txt")) {
            type = "text/plain; charset=utf-8";
            body = "User-agent: *\nDisallow: /secret\nAllow: /\nCrawl-delay: 0\n"
                + "Sitemap: " + base + "/sitemap.xml\n";
        } else if (path.equals("/") || path.startsWith("/page/")) {
            int n = path.equals("/") ? 0 : parseInt(path.substring(path.lastIndexOf('/') + 1));
            body = page(n);
        } else if (path.equals("/secret")) {
            body = "<html><head><title>SECRET - 不应被抓取</title></head><body>禁止抓取</body></html>";
        } else if (path.equals("/api/data.json")) {
            type = "application/json; charset=utf-8";
            body = "{\"items\":[{\"title\":\"JSON 条目 A\",\"url\":\"" + base + "/page/9\"},"
                + "{\"title\":\"JSON 条目 B\",\"href\":\"" + base + "/page/10\"}],"
                + "\"next\":\"" + base + "/page/11\"}";
        } else if (path.equals("/sitemap.xml")) {
            type = "application/xml; charset=utf-8";
            StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><urlset>");
            for (int i = 1; i <= 8; i++) sb.append("<loc>").append(base).append("/page/").append(i).append("</loc>");
            sb.append("<loc>").append(base).append("/secret</loc></urlset>");
            body = sb.toString();
        } else if (path.equals("/feed.xml")) {
            type = "application/rss+xml; charset=utf-8";
            body = "<?xml version=\"1.0\"?><rss><channel><item><link>" + base
                + "/page/12</link><title>RSS 条目</title></item></channel></rss>";
        } else if (path.equals("/slow")) {
            try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            body = "<html><head><title>慢页面</title></head><body>用于测试自适应并发</body></html>";
        } else if (path.equals("/rate-limited")) {
            status = 429;
            body = "too many requests";
        } else if (path.endsWith(".png")) {
            status = 404;
            body = "";
        } else {
            status = 404;
            body = "not found";
        }
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, data.length);
        ex.getResponseBody().write(data);
        ex.close();
    }

    private String page(int n) {
        StringBuilder sb = new StringBuilder("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
            .append("<title>演示页 ").append(n).append("</title>")
            .append("<meta name=\"description\" content=\"本机演示站的第 ").append(n).append(" 页\">")
            .append("</head><body><h1>演示页 ").append(n).append("</h1>")
            .append("<p class=\"content\">这是第 ").append(n).append(" 个用于自测的公开页面。</p>")
            .append("<div class=\"tag\">爬虫</div><div class=\"tag\">教学</div>");
        for (int i = 1; i <= 8; i++) sb.append("<a href=\"/page/").append(i)
            .append("\">页面 ").append(i).append("</a> ");
        sb.append("<a href=\"/secret\">禁止页</a> ")
            .append("<a href=\"/asset.png\">图片</a> ")
            .append("<a href=\"https://example.org/external\">外链</a> ")
            .append("<a href=\"/api/data.json\">JSON 接口</a> ")
            .append("<a href=\"/feed.xml\">RSS</a> ")
            .append("<a href=\"/nofollow\" rel=\"nofollow\">不跟随</a>")
            .append("</body></html>");
        return sb.toString();
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    @Override public void close() { server.stop(0); }
}
