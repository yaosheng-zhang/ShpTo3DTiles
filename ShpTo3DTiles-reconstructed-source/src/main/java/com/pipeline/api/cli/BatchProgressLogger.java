package com.pipeline.api.cli;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class BatchProgressLogger {
   private final String stage;
   private final int total;
   private final long startedAt = System.nanoTime();
   private final long intervalNanos;
   private final AtomicInteger processed = new AtomicInteger();
   private final AtomicInteger success = new AtomicInteger();
   private final AtomicInteger failed = new AtomicInteger();
   private final AtomicLong lastReport = new AtomicLong(this.startedAt);

   public BatchProgressLogger(String var1, int var2, int var3) {
      this.stage = var1;
      this.total = Math.max(0, var2);
      this.intervalNanos = Math.max(1L, var3) * 1000000000L;
   }

   public void complete(boolean var1) {
      this.processed.incrementAndGet();
      if (var1) {
         this.success.incrementAndGet();
      } else {
         this.failed.incrementAndGet();
      }

      this.report(false);
   }

   public void report(boolean var1) {
      long var2 = System.nanoTime();
      long var4 = this.lastReport.get();
      if (var1 || var2 - var4 >= this.intervalNanos || this.processed.get() >= this.total) {
         if (this.lastReport.compareAndSet(var4, var2) || var1) {
            int var6 = this.processed.get();
            double var7 = (var2 - this.startedAt) / 1.0E9;
            double var9 = var7 <= 0.0 ? 0.0 : var6 / var7;
            long var11 = var9 <= 0.0 ? 0L : Math.max(0L, Math.round((this.total - var6) / var9));
            System.out
               .printf(
                  Locale.ROOT,
                  "[progress] stage=%s processed=%d/%d success=%d failed=%d rate=%.2f/s elapsed=%s eta=%s%n",
                  this.stage,
                  var6,
                  this.total,
                  this.success.get(),
                  this.failed.get(),
                  var9,
                  formatSeconds(Math.round(var7)),
                  formatSeconds(var11)
               );
         }
      }
   }

   public int processed() {
      return this.processed.get();
   }

   public int success() {
      return this.success.get();
   }

   public int failed() {
      return this.failed.get();
   }

   public long elapsedMillis() {
      return (System.nanoTime() - this.startedAt) / 1000000L;
   }

   private static String formatSeconds(long var0) {
      long var2 = var0 / 60L;
      long var4 = var0 % 60L;
      return var2 == 0L ? var4 + "s" : var2 + "m" + String.format(Locale.ROOT, "%02ds", var4);
   }
}
