package com.pipeline.api.dto;

import lombok.Data;

/**
 * Scene query response: 3D Tiles batch metadata + tileset URL.
 *
 * tilesetUrl is server-relative (e.g. /scene-tiles/abc123/tileset.json) so the
 * front-end can use it directly with Mars3d.layer.TilesetLayer({url: ...}).
 */
@Data
public class SceneResponse {
    private String batchId;
    private int    buildingCount;
    private int    roadCount;
    private String tilesetUrl;
}
