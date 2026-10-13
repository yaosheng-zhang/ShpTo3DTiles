package com.pipeline.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * Scene query: polygon range + optional road / building filters.
 *
 * - geometry parsing follows the same rules as PolygonQueryRequest:
 *   format=wkt    → EPSG:4544 WKT polygon
 *   format=geojson → WGS84 (EPSG:4326) GeoJSON polygon (server transforms)
 * - includeRoads / includeBuildings toggle the two model classes; at least
 *   one must be true (validated in the service layer).
 * - roadFilter / bldFilter are optional; null or empty fields = no filter.
 */
@Data
public class SceneQueryRequest {

    @NotBlank
    private String format;

    @NotBlank
    private String geometry;

    private boolean includeRoads = false;

    private boolean includeBuildings = true;

    @Valid
    private RoadFilter roadFilter;

    @Valid
    private BuildingFilter bldFilter;

    @Data
    public static class RoadFilter {
        /** Any of these names; ILIKE '%name%' on name OR src_meta::text. Empty = no filter. */
        private List<String> names;
    }

    @Data
    public static class BuildingFilter {
        /** bld_usage IN (...) when non-empty. */
        private List<String> usages;
        /** floors >= min (inclusive) when non-null. */
        private Integer minFloors;
        /** floors <= max (inclusive) when non-null. */
        private Integer maxFloors;
    }
}
