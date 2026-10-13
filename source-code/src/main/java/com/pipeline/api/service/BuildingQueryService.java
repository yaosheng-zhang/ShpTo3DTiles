package com.pipeline.api.service;

import com.pipeline.api.dto.BuildingFootprintDTO;
import com.pipeline.api.dto.BuildingModelDTO;
import com.pipeline.api.dto.PolygonQueryRequest;
import com.pipeline.api.geom.BuildingExtruder;
import com.pipeline.api.geom.WhiteModelObjWriter;
import com.pipeline.api.mapper.BuildingQueryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Realtime building white-model service.
 *
 * Pipeline per request:
 *   1. PostGIS query returns footprints whose polygon intersects the input area.
 *   2. For each footprint, BuildingExtruder produces a 4-segment watertight mesh.
 *   3. WhiteModelObjWriter writes one OBJ per building into the batch directory.
 *   4. Response carries the batchId + per-building relative paths.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BuildingQueryService {

    private final BuildingQueryMapper mapper;
    private final BuildingTempStorage storage;

    public BatchResult findBuildingsByAreaRealtime(PolygonQueryRequest req) throws IOException {
        String fmt  = req.getFormat()   == null ? "" : req.getFormat().trim().toLowerCase();
        String geom = req.getGeometry() == null ? "" : req.getGeometry().trim();
        if (geom.isEmpty()) {
            return BatchResult.empty();
        }

        List<BuildingFootprintDTO> footprints = switch (fmt) {
            case "wkt"     -> mapper.findFootprintsByWktPolygon(geom);
            case "geojson" -> mapper.findFootprintsByGeoJsonPolygon(geom);
            default -> throw new IllegalArgumentException(
                    "format must be 'wkt' or 'geojson', got: " + req.getFormat());
        };

        if (footprints.isEmpty()) {
            return BatchResult.empty();
        }

        BuildingTempStorage.Batch batch = storage.createBatch();
        long t0 = System.nanoTime();
        List<BuildingModelDTO> models = new ArrayList<>(footprints.size());
        int fail = 0;
        for (BuildingFootprintDTO fp : footprints) {
            try {
                List<BuildingExtruder.Segment> segs = BuildingExtruder.extrude(fp);
                if (segs.isEmpty()) { fail++; continue; }

                Path outFile = batch.dir().resolve(fp.getBid() + ".obj");
                WhiteModelObjWriter.writeObj(outFile, fp.getBid(), segs);

                BuildingModelDTO dto = new BuildingModelDTO();
                dto.setBid(fp.getBid());
                dto.setJzgd(fp.getJzgd());
                dto.setRelPath(storage.buildingRelPath(batch.id(), fp.getBid()));
                models.add(dto);
            } catch (Exception ex) {
                fail++;
                log.warn("extrude failed for bid={}: {}", fp.getBid(), ex.toString());
            }
        }
        double dtMs = (System.nanoTime() - t0) / 1_000_000.0;
        log.info("batch {}: extruded {} / {} buildings in {} ms (failed {})",
                batch.id(), models.size(), footprints.size(),
                String.format("%.1f", dtMs), fail);

        return new BatchResult(batch.id(), models);
    }

    public record BatchResult(String batchId, List<BuildingModelDTO> models) {
        static BatchResult empty() { return new BatchResult(null, List.of()); }
    }
}
