package com.pipeline.api.controller;

import com.pipeline.api.mapper.MetadataMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Catalog endpoints powering the front-end filter dropdowns.
 * All read-only, all very cheap (DISTINCT or MIN/MAX on indexed columns).
 */
@RestController
@RequestMapping("/api/meta")
@RequiredArgsConstructor
public class MetadataController {

    private final MetadataMapper mapper;

    @GetMapping("/road-names")
    public List<String> roadNames() {
        return mapper.listRoadNames();
    }

    @GetMapping("/bld-usages")
    public List<String> bldUsages() {
        return mapper.listBldUsages();
    }

    @GetMapping("/floors-range")
    public Map<String, Integer> floorsRange() {
        return mapper.floorsRange();
    }
}
