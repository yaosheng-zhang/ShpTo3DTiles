package com.pipeline.api.controller;

import com.pipeline.api.dto.SceneQueryRequest;
import com.pipeline.api.dto.SceneStatusResponse;
import com.pipeline.api.service.SceneJobRegistry;
import com.pipeline.api.service.SceneService;
import com.pipeline.api.service.SceneTempStorage;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Async scene composition.
 *
 *   POST /api/scene/by-area          — features that INTERSECT the polygon
 *                                       (original behaviour, ST_Intersects)
 *   POST /api/scene/by-area-within   — features fully CONTAINED in the polygon
 *                                       (ST_Within)
 *   GET  /api/scene/status/{id}      — current job snapshot; poll while running.
 *
 * Both build endpoints return immediately with status=QUEUED and a batchId;
 * the actual scene generation runs on the SceneJobRegistry executor and the
 * client polls the status endpoint until DONE / FAILED. On DONE the snapshot
 * carries `tilesetUrl` ready to feed Mars3d TilesetLayer.
 */
@RestController
@RequestMapping("/api/scene")
@RequiredArgsConstructor
@Slf4j
public class SceneController {

    private final SceneService     service;
    private final SceneJobRegistry registry;
    private final SceneTempStorage storage;

    @PostMapping("/by-area")
    public SceneStatusResponse byArea(@Valid @RequestBody SceneQueryRequest req) throws Exception {
        return submit(req, "intersects");
    }

    @PostMapping("/by-area-within")
    public SceneStatusResponse byAreaWithin(@Valid @RequestBody SceneQueryRequest req) throws Exception {
        return submit(req, "within");
    }

    @GetMapping("/status/{batchId}")
    public ResponseEntity<SceneStatusResponse> status(@PathVariable String batchId) {
        SceneStatusResponse snap = registry.snapshot(batchId);
        if (snap == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(snap);
    }

    /* ------------ shared submission path ------------ */

    private SceneStatusResponse submit(SceneQueryRequest req, String spatialMode) throws java.io.IOException {
        // Allocate the batch directory up front so we have a stable ID to
        // hand back before any heavy work starts.
        SceneTempStorage.Batch batch = storage.createBatch();
        SceneJobRegistry.JobState state = registry.create(batch.id());

        registry.jobExecutor().submit(() -> {
            try {
                service.buildAsync(req, batch, state, spatialMode);
            } catch (Throwable t) {
                log.error("scene build {} failed: {}", batch.id(), t.toString(), t);
                state.finishFail(t.getMessage() == null ? t.toString() : t.getMessage());
            }
        });

        return state.toResponse();
    }
}
