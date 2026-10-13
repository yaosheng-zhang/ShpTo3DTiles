package com.pipeline.api.mapper;

import com.pipeline.api.dto.ObjModelDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ObjQueryMapper {

    /** Polygon given as EPSG:4544 WKT. */
    List<ObjModelDTO> findObjsByWktPolygon(@Param("wkt") String wkt);

    /** Polygon given as WGS84 GeoJSON; the SQL transforms 4326 -> 4544. */
    List<ObjModelDTO> findObjsByGeoJsonPolygon(@Param("json") String json);

    /** Fuzzy match on road_centerline.name and src_meta JSONB. */
    List<ObjModelDTO> findObjsByRoadName(@Param("name") String name);
}
