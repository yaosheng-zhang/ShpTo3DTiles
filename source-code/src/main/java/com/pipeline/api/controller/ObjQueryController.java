package com.pipeline.api.controller;

import com.pipeline.api.dto.ObjModelDTO;
import com.pipeline.api.dto.PolygonQueryRequest;
import com.pipeline.api.service.ObjQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/objs")
@RequiredArgsConstructor
public class ObjQueryController {

    private final ObjQueryService service;

    /**
     * 接口 1: 范围 (polygon) 查 OBJ 模型
     * POST /api/objs/by-area
     * Body:
     *   { "format": "wkt",     "geometry": "POLYGON((648500 3261000, ...))" }
     *   { "format": "geojson", "geometry": "{\"type\":\"Polygon\",\"coordinates\":[[...]]}" }
     */
    @PostMapping("/by-area")
    public Map<String, Object> byArea(@Valid @RequestBody PolygonQueryRequest req) {
        List<ObjModelDTO> data = service.findObjsByArea(req);
        return Map.of("count", data.size(), "data", data);
    }

    /**
     * 接口 2: 按道路名查 OBJ 模型
     * GET /api/objs/by-road?name=巴滨路
     */
    @GetMapping("/by-road")
    public Map<String, Object> byRoad(@RequestParam("name") String name) {
        List<ObjModelDTO> data = service.findObjsByRoadName(name);
        return Map.of("count", data.size(), "data", data);
    }
}
