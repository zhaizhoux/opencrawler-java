package io.opencrawler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** 桌面服务：JDK 内置 HttpServer，实现与 Python/C++ 版相同的 REST + SSE 契约。 */
public final class Desktop implements AutoCloseable {
    private static final int MAX_LOGS = 400;
    private final HttpServer server;
    private final State state = new State();
    private volatile boolean closed;

    public static final class State {
        public Map<String, Object> config = new Config(Map.of()).toMap();
        public Engine engine;
        public Thread task;
        public Map<String, Object> summary;
        public String error = "";
        public final Deque<Map<String, Object>> logs = new ArrayDeque<>();
        private int seq;
        private final Handler handler = new Handler() {
            @Override public void publish(LogRecord r) { log(r.getLevel().getName(), r.getMessage(), r.getLoggerName()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        private final Logger logSink = Logger.getLogger("opencrawler");

        State() { logSink.addHandler(handler); logSink.setLevel(Level.ALL); }

        public synchronized void log(String level, String message, String source) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("seq", ++seq);
            e.put("level", level == null ? "INFO" : level);
            e.put("message", message == null ? "" : message);
            e.put("source", source == null ? "engine" : source);
            logs.addLast(e);
            while (logs.size() > MAX_LOGS) logs.removeFirst();
        }

        public synchronized List<Map<String, Object>> logsSince(int after) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> e : logs) if (((Number) e.get("seq")).intValue() > after) out.add(e);
            return out;
        }

        public synchronized int seq() { return seq; }

        public synchronized boolean running() { return task != null && task.isAlive(); }

        public Map<String, Object> status() {
            Map<String, Object> s = engine == null ? idle() : new LinkedHashMap<>(engine.snapshot());
            s.put("running", running());
            s.put("summary", summary);
            s.put("error", error);
            s.put("version", Config.VERSION);
            s.put("output_dir", config.getOrDefault("output_dir", "./output"));
            return s;
        }

        private Map<String, Object> idle() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("requests", 0); m.put("succeeded", 0); m.put("failed", 0);
            m.put("items", 0); m.put("inflight", 0); m.put("frontier", Map.of());
            m.put("requests_per_second", 0.0); m.put("elapsed_seconds", 0.0);
            m.put("adaptive", Map.of("enabled", false));
            return m;
        }

        public synchronized void close() { logSink.removeHandler(handler); }
    }

    public Desktop(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::dispatch);
    }

    public int port() { return server.getAddress().getPort(); }
    public State state() { return state; }

    public void start() {
        server.start();
        System.out.println("OpenCrawlerJava 桌面服务已启动: http://127.0.0.1:" + port());
    }

    public void serveForever() throws InterruptedException {
        start();
        openDesktopWindow("http://127.0.0.1:" + port() + "/");
        while (!closed) Thread.sleep(500);
    }

    private void dispatch(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        try {
            switch (path) {
                case "/", "/index.html" -> asset(ex, "index.html", "text/html; charset=utf-8");
                case "/app.css" -> asset(ex, "app.css", "text/css; charset=utf-8");
                case "/app.js" -> asset(ex, "app.js", "application/javascript; charset=utf-8");
                case "/api/events" -> events(ex);
                case "/api/status" -> json(ex, 200, state.status());
                case "/api/config" -> {
                    if (method.equals("POST")) {
                        if (state.running()) { json(ex, 409, error("抓取正在运行，请先停止再修改配置")); break; }
                        Map<String, Object> body = body(ex);
                        Map<String, Object> merged = new LinkedHashMap<>(state.config);
                        merged.putAll(body);
                        try {
                            Config cfg = new Config(merged);
                            state.config = cfg.toMap();
                            state.log("INFO", "配置已更新", "desktop");
                            json(ex, 200, Map.of("ok", true, "config", state.config));
                        } catch (IllegalArgumentException e) { json(ex, 400, error("配置无效: " + e.getMessage())); }
                    } else json(ex, 200, state.config);
                }
                case "/api/validate" -> {
                    try {
                        Config cfg = new Config(body(ex));
                        json(ex, 200, Map.of("valid", true, "warnings", cfg.warnings, "config", cfg.toMap()));
                    } catch (IllegalArgumentException e) {
                        json(ex, 400, Map.of("valid", false, "error", e.getMessage()));
                    }
                }
                case "/api/start" -> startCrawl(ex);
                case "/api/stop" -> {
                    if (state.engine != null && state.running()) {
                        state.engine.requestStop("桌面窗口请求停止");
                        state.log("WARNING", "已请求优雅停止，正在保存进度…", "desktop");
                    }
                    json(ex, 200, Map.of("ok", true));
                }
                case "/api/results" -> {
                    int limit = queryInt(ex, "limit", 100);
                    json(ex, 200, Map.of("items", Storage.tail(
                        String.valueOf(state.config.getOrDefault("output_dir", "./output")), limit)));
                }
                case "/api/logs" -> {
                    int since = queryInt(ex, "since", 0);
                    json(ex, 200, Map.of("seq", state.seq(), "items", state.logsSince(since)));
                }
                case "/api/export" -> export(ex);
                default -> send(ex, 404, "not found", "text/plain; charset=utf-8");
            }
        } catch (Exception e) {
            json(ex, 500, error(e.toString()));
        } finally {
            if (!path.equals("/api/events")) ex.close();
        }
    }

    private void startCrawl(HttpExchange ex) throws IOException {
        if (state.running()) { json(ex, 409, error("已有任务在运行")); return; }
        Map<String, Object> body = body(ex);
        @SuppressWarnings("unchecked")
        List<Object> seeds = body.get("seeds") instanceof List<?> l ? new ArrayList<>((List<Object>) l) : new ArrayList<>();
        if (seeds.isEmpty()) {
            Object fallback = state.config.get("seeds");
            if (fallback instanceof List<?> l) seeds.addAll(l);
        }
        if (seeds.isEmpty()) { json(ex, 400, error("请先填写至少一个起始 URL")); return; }
        Map<String, Object> merged = new LinkedHashMap<>(state.config);
        merged.putAll(body);
        merged.put("seeds", seeds);
        try {
            Config cfg = new Config(merged);
            state.config = cfg.toMap();
            state.summary = null;
            state.error = "";
            Engine engine = new Engine(cfg);
            state.engine = engine;
            Thread t = new Thread(() -> {
                try { state.summary = engine.run(); }
                catch (Exception e) { state.error = e.toString(); state.log("ERROR", state.error, "desktop"); }
            }, "opencrawler-desktop-run");
            t.setDaemon(true);
            state.task = t;
            t.start();
            state.log("INFO", "任务启动：" + seeds.size() + " 个种子，并发 " + cfg.concurrency, "desktop");
            json(ex, 200, Map.of("ok", true, "status", state.status()));
        } catch (IllegalArgumentException e) {
            json(ex, 400, error("配置无效: " + e.getMessage()));
        }
    }

    private void events(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
            ex.getResponseBody(), StandardCharsets.UTF_8))) {
            int seq = 0;
            while (!closed) {
                List<Map<String, Object>> fresh = state.logsSince(seq);
                for (Map<String, Object> e : fresh) {
                    seq = ((Number) e.get("seq")).intValue();
                    w.write("event: log\ndata: " + Json.stringify(e) + "\n\n");
                }
                w.write("event: status\ndata: " + Json.stringify(state.status()) + "\n\n");
                w.flush();
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 客户端关闭连接
        }
    }

    private void export(HttpExchange ex) throws IOException {
        Path p = Path.of(String.valueOf(state.config.getOrDefault("output_dir", "./output")), "items.jsonl");
        if (!Files.isRegularFile(p)) { send(ex, 404, "还没有结果文件", "text/plain; charset=utf-8"); return; }
        byte[] data = Files.readAllBytes(p);
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"opencrawler-items.jsonl\"");
        ex.sendResponseHeaders(200, data.length);
        ex.getResponseBody().write(data);
    }

    private void asset(HttpExchange ex, String name, String mime) throws IOException {
        byte[] data;
        try (InputStream in = Desktop.class.getResourceAsStream("/web/" + name)) {
            data = in == null ? Files.readAllBytes(Path.of("desktop", "web", name)) : in.readAllBytes();
        } catch (IOException e) { data = new byte[0]; }
        ex.sendResponseHeaders(200, data.length);
        ex.getResponseBody().write(data);
    }

    private static Map<String, Object> body(HttpExchange ex) throws IOException {
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        return raw.isEmpty() ? Map.of() : Json.obj(Json.parse(raw));
    }

    private static Map<String, Object> error(String message) { return Map.of("error", message); }

    private static int queryInt(HttpExchange ex, String key, int def) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return def;
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(key)) try { return Integer.parseInt(kv[1]); } catch (NumberFormatException ignored) { }
        }
        return def;
    }

    private static void json(HttpExchange ex, int status, Object payload) throws IOException {
        send(ex, status, Json.stringify(payload), "application/json; charset=utf-8");
    }

    private static void send(HttpExchange ex, int status, String body, String mime) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", mime);
        ex.sendResponseHeaders(status, data.length);
        ex.getResponseBody().write(data);
    }

    public static boolean openDesktopWindow(String url) {
        String[] candidates = {
            System.getenv("ProgramFiles(x86)") + "\\Microsoft\\Edge\\Application\\msedge.exe",
            System.getenv("ProgramFiles") + "\\Microsoft\\Edge\\Application\\msedge.exe",
            "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
            "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
            "/usr/bin/microsoft-edge", "/usr/bin/google-chrome",
        };
        for (String exe : candidates) {
            if (exe == null || !Path.of(exe).toFile().isFile()) continue;
            try {
                List<String> args = List.of(exe, "--app=" + url, "--window-size=1320,900",
                    "--user-data-dir=" + Path.of(System.getProperty("java.io.tmpdir"), "opencrawler-java-desktop"),
                    "--no-first-run", "--no-default-browser-check");
                new ProcessBuilder(args).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                return true;
            } catch (IOException ignored) { }
        }
        System.out.println("未找到 Chromium 浏览器，请手动打开: " + url);
        return false;
    }

    @Override public void close() {
        closed = true;
        if (state.engine != null && state.running()) state.engine.requestStop("桌面服务关闭");
        state.close();
        server.stop(0);
    }
}
