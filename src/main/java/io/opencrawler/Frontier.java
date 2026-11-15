package io.opencrawler;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 持久优先队列：内存调度 + append-only journal 实现断点续爬。 */
public final class Frontier implements AutoCloseable {
    private final PriorityBlockingQueue<Models.Request> queue = new PriorityBlockingQueue<>(64,
        Comparator.<Models.Request>comparingInt(r -> -r.priority())
            .thenComparingInt(Models.Request::depth)
            .thenComparing(Models.Request::url));
    private final ConcurrentHashMap<String, Boolean> seen = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> done = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> failures = new ConcurrentHashMap<>();
    private final BufferedWriter journal;

    public Frontier(String stateFile) throws IOException {
        Path path = Path.of(stateFile);
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Map<String, Models.Request> pending = new LinkedHashMap<>();
        if (Files.exists(path)) {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length < 2) continue;
                if (f[0].equals("Q") && f.length >= 5) {
                    try {
                        pending.put(f[1], new Models.Request(f[1], Integer.parseInt(f[2]),
                            Integer.parseInt(f[3]), f[4]));
                    } catch (NumberFormatException ignored) { }
                } else if (f[0].equals("D")) {
                    pending.remove(f[1]);
                    done.put(f[1], Boolean.TRUE);
                }
            }
            for (Models.Request r : pending.values()) { seen.put(r.url(), Boolean.TRUE); queue.add(r); }
        }
        journal = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized boolean add(Models.Request r) {
        if (r == null || r.url().isEmpty() || done.containsKey(r.url())
            || seen.putIfAbsent(r.url(), Boolean.TRUE) != null) return false;
        queue.add(r);
        append("Q\t" + r.url() + "\t" + r.depth() + "\t" + r.priority() + "\t" + r.kind());
        return true;
    }

    public int addAll(List<Models.Request> requests) {
        int n = 0;
        for (Models.Request r : requests) if (add(r)) n++;
        return n;
    }

    public Models.Request poll(long timeoutMs) throws InterruptedException {
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void markDone(String url, String hash) {
        done.put(url, Boolean.TRUE);
        failures.remove(url);
        append("D\t" + url);
    }

    /** 失败重试：返回 true 表示已重新入队。 */
    public synchronized boolean markFailed(Models.Request r, int maxRetries, String reason) {
        int n = failures.merge(r.url(), 1, Integer::sum);
        if (n > maxRetries) { done.put(r.url(), Boolean.TRUE); append("D\t" + r.url()); return false; }
        queue.add(r);
        append("Q\t" + r.url() + "\t" + r.depth() + "\t" + r.priority() + "\t" + r.kind());
        return true;
    }

    public Map<String, Object> counts() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("queued", queue.size());
        m.put("done", done.size());
        m.put("seen", seen.size());
        return m;
    }

    public boolean isEmpty() { return queue.isEmpty(); }

    private void append(String line) {
        try { journal.write(line); journal.newLine(); journal.flush(); }
        catch (IOException ignored) { /* 日志写入失败不阻断抓取 */ }
    }

    @Override public void close() {
        try { journal.flush(); journal.close(); }
        catch (IOException ignored) { }
    }
}
