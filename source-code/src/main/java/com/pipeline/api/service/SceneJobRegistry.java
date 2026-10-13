package com.pipeline.api.service;

import com.pipeline.api.dto.SceneStatusResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Two-pool async job registry.
 *
 *  - `jobExecutor`     runs whole scene-build requests; small pool because each
 *                      build internally fans out to the inner pool.
 *  - `convertExecutor` runs individual obj2gltf invocations in parallel;
 *                      sized to CPU cores. Used by SceneService.
 *
 * Job state lives in a ConcurrentHashMap until the application restarts; not
 * persisted to disk (jobs older than the JVM are gone). Output b3dm files are
 * cleaned up by SceneTempStorage on JVM startup.
 */
@Service
@Slf4j
public class SceneJobRegistry {

    public static final String QUEUED  = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String DONE    = "DONE";
    public static final String FAILED  = "FAILED";

    private final ConcurrentHashMap<String, JobState> jobs = new ConcurrentHashMap<>();

    private ExecutorService jobExecutor;       // outer: scene-build requests
    private ExecutorService convertExecutor;   // inner: parallel obj2gltf
    private int convertParallelism;

    @PostConstruct
    void init() {
        int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        convertParallelism = cores;
        jobExecutor = Executors.newFixedThreadPool(2, namedThread("scene-job-"));
        convertExecutor = Executors.newFixedThreadPool(cores, namedThread("obj2gltf-"));
        log.info("SceneJobRegistry: jobPool=2, convertPool={}", cores);
    }

    @PreDestroy
    void shutdown() {
        if (jobExecutor != null) jobExecutor.shutdownNow();
        if (convertExecutor != null) convertExecutor.shutdownNow();
        try {
            if (jobExecutor != null)     jobExecutor.awaitTermination(5, TimeUnit.SECONDS);
            if (convertExecutor != null) convertExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public ExecutorService jobExecutor()     { return jobExecutor; }
    public ExecutorService convertExecutor() { return convertExecutor; }
    public int convertParallelism()          { return convertParallelism; }

    /* ------------ job state management ------------ */

    public JobState create(String batchId) {
        JobState js = new JobState(batchId);
        jobs.put(batchId, js);
        return js;
    }

    public JobState get(String batchId) {
        return jobs.get(batchId);
    }

    public SceneStatusResponse snapshot(String batchId) {
        JobState js = jobs.get(batchId);
        return js == null ? null : js.toResponse();
    }

    /**
     * Mutable per-batch state. All fields read+write through atomics or volatile
     * so the polling endpoint can read while the job thread is writing.
     */
    public static final class JobState {
        final String        batchId;
        final long          startedAtMs;
        volatile String     status = QUEUED;
        final AtomicInteger total     = new AtomicInteger(0);
        final AtomicInteger processed = new AtomicInteger(0);
        final AtomicInteger success   = new AtomicInteger(0);
        final AtomicInteger failed    = new AtomicInteger(0);
        final AtomicLong lastProgressLogMs = new AtomicLong(0L);
        final AtomicInteger buildingCount = new AtomicInteger(0);
        final AtomicInteger roadCount     = new AtomicInteger(0);
        volatile String     tilesetUrl;
        volatile String     errorMessage;
        volatile long       finishedAtMs;

        JobState(String batchId) {
            this.batchId = batchId;
            this.startedAtMs = System.currentTimeMillis();
        }

        public void setTotal(int t)         { total.set(t); }
        public void incProcessed()          { processed.incrementAndGet(); }
        public void incSuccess()            { success.incrementAndGet(); }
        public void incFailed()             { failed.incrementAndGet(); }
        public boolean progressDue(long intervalMs) {
            long now = System.currentTimeMillis();
            long previous = lastProgressLogMs.get();
            return now - previous >= intervalMs && lastProgressLogMs.compareAndSet(previous, now);
        }
        public void setStatus(String s)     { status = s; }
        public void setTilesetUrl(String u) { tilesetUrl = u; }
        public void setBuildingOk(int n)    { buildingCount.set(n); }
        public void setRoadOk(int n)        { roadCount.set(n); }

        public void finishOk(String url, int bOk, int rOk) {
            tilesetUrl = url;
            buildingCount.set(bOk);
            roadCount.set(rOk);
            finishedAtMs = System.currentTimeMillis();
            status = DONE;
        }

        public void finishFail(String msg) {
            errorMessage = msg;
            finishedAtMs = System.currentTimeMillis();
            status = FAILED;
        }

        public SceneStatusResponse toResponse() {
            SceneStatusResponse r = new SceneStatusResponse();
            r.setBatchId(batchId);
            r.setStatus(status);
            r.setTotal(total.get());
            r.setProcessed(processed.get());
            r.setSuccess(success.get());
            r.setFailed(failed.get());
            long elapsedMs = Math.max(1L, (status.equals(DONE) || status.equals(FAILED)
                    ? finishedAtMs : System.currentTimeMillis()) - startedAtMs);
            double rate = processed.get() * 1000.0 / elapsedMs;
            r.setRatePerSecond(rate);
            r.setEtaSeconds(rate > 0 ? Math.max(0L, Math.round((total.get() - processed.get()) / rate)) : 0L);
            r.setBuildingCount(buildingCount.get());
            r.setRoadCount(roadCount.get());
            r.setTilesetUrl(tilesetUrl);
            r.setErrorMessage(errorMessage);
            r.setStartedAtMs(startedAtMs);
            r.setFinishedAtMs(finishedAtMs);
            return r;
        }
    }

    private static java.util.concurrent.ThreadFactory namedThread(String prefix) {
        AtomicInteger idx = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + idx.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
