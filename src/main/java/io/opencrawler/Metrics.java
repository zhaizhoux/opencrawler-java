package io.opencrawler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 线程安全的实时指标。 */
public final class Metrics {
    public final AtomicLong requests = new AtomicLong();
    public final AtomicLong succeeded = new AtomicLong();
    public final AtomicLong failed = new AtomicLong();
    public final AtomicLong skipped = new AtomicLong();
    public final AtomicLong items = new AtomicLong();
    public final AtomicLong duplicates = new AtomicLong();
    public final AtomicLong links = new AtomicLong();
    public final AtomicLong bytes = new AtomicLong();
    public final ConcurrentHashMap<Integer, AtomicLong> statusCodes = new ConcurrentHashMap<>();
    private final long startedAt = System.nanoTime();

    public void record(Models.Response r) {
        requests.incrementAndGet();
        bytes.addAndGet(r.body() == null ? 0 : r.body().length);
        statusCodes.computeIfAbsent(r.status(), k -> new AtomicLong()).incrementAndGet();
        if (r.status() >= 200 && r.status() < 300) succeeded.incrementAndGet();
    }

    public double elapsedSeconds() { return (System.nanoTime() - startedAt) / 1e9; }

    public double rps() { return requests.get() / Math.max(0.001, elapsedSeconds()); }

    public Map<String, Object> snapshot(Map<String, Object> frontier, int inflight) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("elapsed_seconds", Math.round(elapsedSeconds() * 10) / 10.0);
        m.put("requests", requests.get());
        m.put("succeeded", succeeded.get());
        m.put("failed", failed.get());
        m.put("skipped", skipped.get());
        m.put("items", items.get());
        m.put("duplicates", duplicates.get());
        m.put("links_discovered", links.get());
        m.put("bytes", bytes.get());
        m.put("requests_per_second", Math.round(rps() * 100) / 100.0);
        m.put("inflight", inflight);
        m.put("frontier", frontier);
        Map<String, Long> codes = new LinkedHashMap<>();
        statusCodes.forEach((k, v) -> codes.put(String.valueOf(k), v.get()));
        m.put("status_codes", codes);
        return m;
    }
}
