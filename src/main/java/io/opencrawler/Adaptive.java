package io.opencrawler;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/** AIMD 自适应并发：错误率或 p95 延迟越界时乘性收敛，健康时加性试探。 */
public final class Adaptive {
    private final int initial, minimum, maximum, window;
    private final double targetLatencyMs, errorThreshold;
    private final long probeIntervalMs;
    private final ArrayDeque<double[]> samples = new ArrayDeque<>();
    private int limit, adjustments;
    private long lastAdjust;

    public Adaptive(int initial, int targetLatencyMs, double probeIntervalSeconds) {
        this.initial = Math.max(1, initial);
        this.minimum = Math.max(1, Math.min(2, this.initial));
        this.maximum = this.initial * 4;
        this.limit = this.initial;
        this.window = 30;
        this.targetLatencyMs = targetLatencyMs;
        this.errorThreshold = 0.15;
        this.probeIntervalMs = (long) (probeIntervalSeconds * 1000);
    }

    public synchronized void record(double latencyMs, boolean ok) {
        samples.addLast(new double[]{Math.max(0, latencyMs), ok ? 1 : 0});
        if (samples.size() < window) return;
        long now = System.currentTimeMillis();
        if (probeIntervalMs > 0 && now - lastAdjust < probeIntervalMs) return;
        lastAdjust = now;
        int errors = 0;
        double[] lats = new double[samples.size()];
        int i = 0;
        for (double[] s : samples) { errors += s[1] == 0 ? 1 : 0; lats[i++] = s[0]; }
        java.util.Arrays.sort(lats);
        double p95 = lats[(int) (0.95 * (lats.length - 1))];
        double errorRate = (double) errors / samples.size();
        int next;
        if (errorRate > errorThreshold || p95 > targetLatencyMs * 1.5) {
            next = Math.max(minimum, (int) (limit * 0.8));
        } else if (errorRate < errorThreshold / 2 && p95 < targetLatencyMs) {
            next = Math.min(maximum, limit + Math.max(1, limit / 8));
        } else next = limit;
        if (next != limit) { limit = next; adjustments++; }
        samples.clear();
    }

    public synchronized int limit() { return limit; }
    public synchronized boolean enabled() { return true; }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", true);
        m.put("limit", limit);
        m.put("initial", initial);
        m.put("minimum", minimum);
        m.put("maximum", maximum);
        m.put("target_latency_ms", (long) targetLatencyMs);
        m.put("adjustments", adjustments);
        m.put("samples_in_window", samples.size());
        return m;
    }
}
