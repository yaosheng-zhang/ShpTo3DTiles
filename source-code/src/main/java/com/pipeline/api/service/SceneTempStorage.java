package com.pipeline.api.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;

/**
 * Per-request temporary directories for 3D-Tiles scene batches.
 *
 *   {project-root}/pipeline_v2/output/scene_tmp/<batchId>/
 *     ├── tileset.json
 *     ├── b<bid>.b3dm     one per included building
 *     ├── r_<filename>.b3dm    one per included road OBJ
 *     ...
 *
 * Served at /scene-tiles/<batchId>/... via WebConfig.addResourceHandlers,
 * which is exactly the tilesetUrl returned to the front-end. On startup the
 * entire scene_tmp/ tree is wiped (same pattern as BuildingTempStorage).
 */
@Service
@Slf4j
public class SceneTempStorage {

    private static final String TMP_DIR_REL = "pipeline_v2/output/scene_tmp";

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
        log.info("SceneTempStorage: project_root={}", projectRoot);
        log.info("SceneTempStorage: tmp_root={}",     tmpRoot);
    }

    public Path projectRoot() { return projectRoot; }

    public Batch createBatch() throws IOException {
        String batchId = UUID.randomUUID().toString().substring(0, 12);
        Path batchDir = tmpRoot.resolve(batchId);
        Files.createDirectories(batchDir);
        return new Batch(batchId, batchDir);
    }

    /** Public URL of the batch's tileset.json, served by WebConfig. */
    public String tilesetUrl(String batchId) {
        return "/scene-tiles/" + batchId + "/tileset.json";
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
            log.warn("scene_tmp cleanup failed: {}", e.toString());
        }
    }

    public record Batch(String id, Path dir) { }
}
