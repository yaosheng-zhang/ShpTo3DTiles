package com.pipeline.api.service;

import com.pipeline.api.geom.WhiteModelObjWriter;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;

/**
 * Manages per-request temporary directories for realtime-generated white-models.
 *
 *   {project-root}/pipeline_v2/output/buildings_tmp/<batchId>/
 *     ├── white.mtl     (shared material palette, one per batch)
 *     ├── <bid>.obj     (one per building in the area-query result)
 *     ...
 *
 * `project-root` is configured via `pipeline.project-root` (default `../..`,
 * relative to the JVM working directory; `mvn spring-boot:run` / `java -jar
 * target/*.jar` from `pipeline_v2/api/` puts project root at `../..`).
 *
 * On startup the entire `buildings_tmp/` tree is wiped so previous-session
 * batches don't accumulate on disk.
 */
@Service
@Slf4j
public class BuildingTempStorage {

    private static final String TMP_DIR_REL = "pipeline_v2/output/buildings_tmp";

    @Value("${pipeline.project-root:../..}")
    private String projectRootStr;

    private Path projectRoot;
    private Path tmpRoot;

    @PostConstruct
    void init() throws IOException {
        projectRoot = Paths.get(projectRootStr).toAbsolutePath().normalize();
        tmpRoot = projectRoot.resolve(TMP_DIR_REL);
        Files.createDirectories(tmpRoot);
        cleanupAll();
        log.info("BuildingTempStorage: project_root={}", projectRoot);
        log.info("BuildingTempStorage: tmp_root={}",     tmpRoot);
    }

    /** Create a new batch directory + write shared MTL. */
    public Batch createBatch() throws IOException {
        String batchId = UUID.randomUUID().toString().substring(0, 12);
        Path batchDir = tmpRoot.resolve(batchId);
        Files.createDirectories(batchDir);
        Files.writeString(batchDir.resolve(WhiteModelObjWriter.MTL_NAME),
                WhiteModelObjWriter.mtlContent(), StandardCharsets.UTF_8);
        return new Batch(batchId, batchDir);
    }

    /** Project-root-relative path for response payloads. */
    public String buildingRelPath(String batchId, long bid) {
        return TMP_DIR_REL + "/" + batchId + "/" + bid + ".obj";
    }

    /** Absolute path used for serving download requests. Null if not found. */
    public Path resolveAbsolute(String batchId, String file) {
        Path abs = tmpRoot.resolve(batchId).resolve(file).normalize();
        // Path-traversal guard: must stay under tmpRoot
        if (!abs.startsWith(tmpRoot) || !Files.exists(abs)) return null;
        return abs;
    }

    private void cleanupAll() {
        if (!Files.exists(tmpRoot)) return;
        try (var stream = Files.walk(tmpRoot)) {
            stream.sorted(Comparator.reverseOrder())
                  .filter(p -> !p.equals(tmpRoot))
                  .forEach(p -> {
                      try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                  });
        } catch (IOException e) {
            log.warn("temp cleanup failed: {}", e.toString());
        }
    }

    /** Container for a single batch (one polygon query). */
    public record Batch(String id, Path dir) { }
}
