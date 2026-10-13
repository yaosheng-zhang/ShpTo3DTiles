package com.pipeline.api.cli;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Lightweight, thread-safe progress reporter for long running batch jobs. */
public final class BatchProgressLogger {
    private final String stage;
    private final int total;
    private final long startedAt = System.nanoTime();
    private final long intervalNanos;
    private final AtomicInteger processed = new AtomicInteger();
    private final AtomicInteger success = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicLong lastReport = new AtomicLong(startedAt);

    public BatchProgressLogger(String stage, int total, int intervalSeconds) {
        this.stage = stage;
        this.total = Math.max(0, total);
        this.intervalNanos = Math.max(1L, intervalSeconds) * 1_000_000_000L;
    }

    public void complete(boolean ok) {
        processed.incrementAndGet();
        if (ok) success.incrementAndGet(); else failed.incrementAndGet();
        report(false);
    }

    public void report(boolean force) {
        long now = System.nanoTime();
        long previous = lastReport.get();
        if (!force && now - previous < intervalNanos && processed.get() < total) return;
        if (!lastReport.compareAndSet(previous, now) && !force) return;

        int done = processed.get();
        double elapsed = (now - startedAt) / 1_000_000_000.0;
        double rate = elapsed <= 0 ? 0 : done / elapsed;
        long eta = rate <= 0 ? 0 : Math.max(0, Math.round((total - done) / rate));
        System.out.printf(Locale.ROOT,
                "[progress] stage=%s processed=%d/%d success=%d failed=%d rate=%.2f/s elapsed=%s eta=%s%n",
                stage, done, total, success.get(), failed.get(), rate,
                formatSeconds(Math.round(elapsed)), formatSeconds(eta));
    }

    public int processed() { return processed.get(); }
    public int success() { return success.get(); }
    public int failed() { return failed.get(); }
    public long elapsedMillis() { return (System.nanoTime() - startedAt) / 1_000_000L; }

    private static String formatSeconds(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        return m == 0 ? s + "s" : m + "m" + String.format(Locale.ROOT, "%02ds", s);
    }
}
