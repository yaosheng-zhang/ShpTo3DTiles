package com.pipeline.api.mapper;

import com.pipeline.api.dto.BuildingFootprintDTO;
import com.pipeline.api.dto.ObjModelDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Range + filter queries for the scene composition endpoint.
 *
 * WKT and GeoJSON variants share the same filter clauses (defined in the
 * mapper XML via &lt;sql&gt; fragments) — only the geometry-parsing branch
 * differs. All `usages` / `names` lists are nullable / may be empty; when
 * null or empty the corresponding clause is skipped.
 *
 * The `predicate` parameter selects the PostGIS spatial test:
 *   "intersects" — feature overlaps the query polygon (default)
 *   "within"     — feature is fully contained inside the query polygon
 */
@Mapper
public interface SceneQueryMapper {

    /* ---------- buildings ---------- */

    List<BuildingFootprintDTO> findBuildingsByWktAndFilter(
            @Param("wkt")       String wkt,
            @Param("usages")    List<String> usages,
            @Param("minFloors") Integer minFloors,
            @Param("maxFloors") Integer maxFloors,
            @Param("predicate") String predicate);

    List<BuildingFootprintDTO> findBuildingsByGeoJsonAndFilter(
            @Param("json")      String json,
            @Param("usages")    List<String> usages,
            @Param("minFloors") Integer minFloors,
            @Param("maxFloors") Integer maxFloors,
            @Param("predicate") String predicate);

    /* ---------- roads ---------- */

    List<ObjModelDTO> findRoadObjsByWktAndFilter(
            @Param("wkt")       String wkt,
            @Param("names")     List<String> names,
            @Param("predicate") String predicate);

    List<ObjModelDTO> findRoadObjsByGeoJsonAndFilter(
            @Param("json")      String json,
            @Param("names")     List<String> names,
            @Param("predicate") String predicate);
}
