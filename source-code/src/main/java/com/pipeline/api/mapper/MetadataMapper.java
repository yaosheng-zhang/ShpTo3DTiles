package com.pipeline.api.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * Read-only catalog queries that power the front-end filter dropdowns,
 * plus one PostGIS helper used by SceneService to translate a 4544 point
 * into WGS84 lon/lat for the tileset transform.
 */
@Mapper
public interface MetadataMapper {

    /** Distinct non-null road names, sorted. */
    List<String> listRoadNames();

    /** Distinct non-null building usages, sorted. */
    List<String> listBldUsages();

    /** {min: ..., max: ...} of building_footprint.floors, ignoring nulls. */
    Map<String, Integer> floorsRange();

    /** PostGIS ST_Transform 4544 → 4326; returns {"lon": deg, "lat": deg}. */
    Map<String, Double> transformToWgs84(@Param("x") double x, @Param("y") double y);

    /** ST_Centroid of a 4544 WKT polygon; returns {"x": m, "y": m}. */
    Map<String, Double> centroidByWkt(@Param("wkt") String wkt);

    /** ST_Centroid of a WGS84 GeoJSON polygon after transforming to 4544. */
    Map<String, Double> centroidByGeoJson(@Param("json") String json);
}
