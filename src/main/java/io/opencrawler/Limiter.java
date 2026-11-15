package io.opencrawler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.LockSupport;

/** 每域并发 + 请求间隔限速。睡眠必须在锁外，否则同域并发会被人为串行化。 */
public final class Limiter {
    private static final class Domain {
        final Semaphore semaphore;
        long nextAllowedNanos;
        long penaltyUntilNanos;
        Domain(int permits) { semaphore = new Semaphore(permits); }
    }

    public static final class Slot implements AutoCloseable {
        private final Semaphore sem;
        Slot(Semaphore sem) { this.sem = sem; }
        @Override public void close() { sem.release(); }
    }

    private final ConcurrentHashMap<String, Domain> domains = new ConcurrentHashMap<>();
    private final int perDomain;
    private final long defaultIntervalNs;
    private final long rpsIntervalNs;

    public Limiter(Config cfg) {
        perDomain = cfg.perDomainConcurrency;
        defaultIntervalNs = cfg.defaultDelayMs * 1_000_000L;
        rpsIntervalNs = cfg.perDomainRps > 0 ? (long) (1_000_000_000L / cfg.perDomainRps) : 0;
    }

    public Slot acquire(String host, long crawlDelayMs) throws InterruptedException {
        Domain d = domains.computeIfAbsent(host, k -> new Domain(perDomain));
        long interval = Math.max(defaultIntervalNs,
            Math.max(rpsIntervalNs, crawlDelayMs * 1_000_000L));
        long now = System.nanoTime();
        long target;
        synchronized (d) {
            target = Math.max(now, Math.max(d.nextAllowedNanos, d.penaltyUntilNanos));
            d.nextAllowedNanos = target + interval;
        }
        long wait = target - now;   // 锁外等待：让同域的并发请求各自排队而不互相阻塞
        if (wait > 0) LockSupport.parkNanos(wait);
        d.semaphore.acquire();
        return new Slot(d.semaphore);
    }

    public void penalize(String host, long seconds) {
        Domain d = domains.computeIfAbsent(host, k -> new Domain(perDomain));
        synchronized (d) {
            d.penaltyUntilNanos = Math.max(d.penaltyUntilNanos,
                System.nanoTime() + seconds * 1_000_000_000L);
        }
    }
}
