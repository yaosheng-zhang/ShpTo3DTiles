package com.pipeline.api.controller;

import com.pipeline.api.dto.PolygonQueryRequest;
import com.pipeline.api.service.BuildingQueryService;
import com.pipeline.api.service.BuildingTempStorage;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/buildings")
@RequiredArgsConstructor
public class BuildingQueryController {

    private final BuildingQueryService service;
    private final BuildingTempStorage  storage;

    /**
     * 范围查询 + 实时拉伸白模 + 临时存储, 返回 batchId 和每栋建筑的相对地址.
     *
     * POST /api/buildings/by-area
     * Body:
     *   { "format": "wkt",     "geometry": "POLYGON((...))" }
     *   { "format": "geojson", "geometry": "{...}" }
     * 返回:
     *   {
     *     "batchId": "ab12cd34ef56",
     *     "count": 41,
     *     "data": [
     *       { "bid": 0, "jzgd": 15.23,
     *         "relPath": "pipeline_v2/output/buildings_tmp/ab12.../0.obj" },
     *       ...
     *     ]
     *   }
     */
    @PostMapping("/by-area")
    public Map<String, Object> byArea(@Valid @RequestBody PolygonQueryRequest req) throws IOException {
        BuildingQueryService.BatchResult br = service.findBuildingsByAreaRealtime(req);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batchId", br.batchId());
        body.put("count",   br.models().size());
        body.put("data",    br.models());
        return body;
    }

    /**
     * 下载临时白模文件 (OBJ 或 MTL).
     * GET /api/buildings/temp/{batchId}/{filename}
     *
     * 路径遍历由 BuildingTempStorage.resolveAbsolute 拦截.
     */
    @GetMapping("/temp/{batchId}/{filename:.+}")
    public ResponseEntity<Resource> download(@PathVariable String batchId,
                                             @PathVariable String filename) {
        Path abs = storage.resolveAbsolute(batchId, filename);
        if (abs == null) {
            return ResponseEntity.notFound().build();
        }
        Resource res = new PathResource(abs);
        MediaType ct = filename.endsWith(".mtl")
                ? MediaType.TEXT_PLAIN
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(ct)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + filename + "\"")
                .body(res);
    }
}
