package com.pipeline.api.service;

import com.pipeline.api.dto.ObjModelDTO;
import com.pipeline.api.dto.PolygonQueryRequest;
import com.pipeline.api.mapper.ObjQueryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ObjQueryService {

    private final ObjQueryMapper mapper;

    public List<ObjModelDTO> findObjsByArea(PolygonQueryRequest req) {
        String fmt = req.getFormat() == null ? "" : req.getFormat().trim().toLowerCase();
        String geom = req.getGeometry() == null ? "" : req.getGeometry().trim();
        if (geom.isEmpty()) {
            return List.of();
        }
        return switch (fmt) {
            case "wkt"     -> mapper.findObjsByWktPolygon(geom);
            case "geojson" -> mapper.findObjsByGeoJsonPolygon(geom);
            default -> throw new IllegalArgumentException(
                    "format must be 'wkt' or 'geojson', got: " + req.getFormat());
        };
    }

    public List<ObjModelDTO> findObjsByRoadName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return List.of();
        }
        return mapper.findObjsByRoadName(name.trim());
    }
}
