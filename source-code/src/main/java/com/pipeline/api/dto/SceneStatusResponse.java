package com.pipeline.api.dto;

import lombok.Data;

/**
 * Status snapshot for an async scene-build job.
 *
 * The front-end submits a build via POST /api/scene/by-area, gets back a
 * batchId, then polls GET /api/scene/status/{batchId} every ~1 s until
 * status == DONE or FAILED.
 *
 *   QUEUED  — registered, not yet started
 *   RUNNING — extruding + converting + writing tiles
 *   DONE    — all done; tilesetUrl is populated
 *   FAILED  — fatal error; errorMessage is populated
 */
@Data
public class SceneStatusResponse {
    private String status;
    private String batchId;
    private int    processed;       // items already converted (buildings + roads)
    private int    total;           // total items to convert
    private int    success;
    private int    failed;
    private double ratePerSecond;
    private long   etaSeconds;
    private int    buildingCount;   // final ok count, valid when DONE
    private int    roadCount;       // final ok count, valid when DONE
    private String tilesetUrl;      // null until DONE
    private String errorMessage;    // null unless FAILED
    private long   startedAtMs;
    private long   finishedAtMs;    // 0 until DONE/FAILED
}
