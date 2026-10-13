package com.pipeline.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class PolygonQueryRequest {

    /** "wkt" or "geojson" — controls how `geometry` is parsed. */
    @NotBlank
    @Pattern(regexp = "wkt|geojson", flags = Pattern.Flag.CASE_INSENSITIVE,
             message = "format must be 'wkt' or 'geojson'")
    private String format;

    /**
     * Geometry payload:
     *   - wkt:     "POLYGON((x1 y1, x2 y2, ...))" in EPSG:4544
     *   - geojson: '{"type":"Polygon","coordinates":[[...]]}' in WGS84 (EPSG:4326)
     */
    @NotBlank
    private String geometry;
}
