package com.pipeline.api.mapper;

import com.pipeline.api.dto.BuildingFootprintDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface BuildingQueryMapper {

    /** Footprints whose polygon intersects the given WKT (EPSG:4544). */
    List<BuildingFootprintDTO> findFootprintsByWktPolygon(@Param("wkt") String wkt);

    /** Footprints whose polygon intersects the given GeoJSON (WGS84 -> 4544). */
    List<BuildingFootprintDTO> findFootprintsByGeoJsonPolygon(@Param("json") String json);
}
