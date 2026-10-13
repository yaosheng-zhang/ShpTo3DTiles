package com.pipeline.api.dto;

import lombok.Data;

/**
 * Building footprint row used by the realtime white-model extruder.
 * Returned by the mapper, consumed by BuildingExtruder.
 */
@Data
public class BuildingFootprintDTO {
    private Long   bid;
    private Double jzgd;       // extrusion height (m)
    private Double baseZ;      // ground elevation (mapped from base_z)
    private String bldUsage;   // mapped from bld_usage
    private Integer floors;    // storey count (LC) — drives parapet / shaft gating
    private String wkt;        // EPSG:4544 WKT, parsed by JTS in Java
}
