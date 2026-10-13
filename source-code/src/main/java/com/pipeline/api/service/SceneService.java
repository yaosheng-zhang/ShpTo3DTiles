package com.pipeline.api.service;

import com.pipeline.api.dto.BuildingFootprintDTO;
import com.pipeline.api.dto.ObjModelDTO;
import com.pipeline.api.dto.SceneQueryRequest;
import com.pipeline.api.dto.SceneStatusResponse;
import com.pipeline.api.geom.BuildingExtruder;
import com.pipeline.api.geom.WhiteModelObjWriter;
import com.pipeline.api.mapper.MetadataMapper;
import com.pipeline.api.mapper.SceneQueryMapper;
import com.pipeline.api.scene.B3dmWriter;
import com.pipeline.api.scene.BuildingTextureSelector;
import com.pipeline.api.scene.ObjToGlbConverter;
import com.pipeline.api.scene.TilesetWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.io.WKTReader;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates a scene-build request asynchronously.
 *
 *   1. query buildings / roads from PostGIS with the user's filters
 *   2. compute a 4544 origin (centroid of building footprints) → translate to
 *      WGS84 lon/lat via PostGIS for the tileset transform
 *   3. fan out per-item work (extrude + obj write + obj2gltf + b3dm wrap) to
 *      a shared CPU-bound thread pool. Progress is published via JobState so
 *      the front-end can poll /api/scene/status/{batchId}.
 *   4. write tileset.json with one child per b3dm
 *
 * The batch dir is created by SceneController before submission; this method
 * just populates it. URL is served via WebConfig static-resource mapping.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SceneService {

    private final SceneQueryMapper  sceneMapper;
    private final MetadataMapper    metaMapper;
    private final SceneTempStorage  storage;
    private final ObjToGlbConverter glbConverter;
    private final SceneJobRegistry  registry;

    /**
     * Run a full scene build, updating `state` as it progresses. Runs on a
     * caller-supplied thread (typically registry.jobExecutor()). Inner work
     * is parallelised across registry.convertExecutor().
     *
     * @param spatialMode "intersects" (default) or "within" — selects whether
     *                    PostGIS uses ST_Intersects or ST_Within when matching
     *                    features against the query polygon.
     */
    public void buildAsync(SceneQueryRequest req,
                           SceneTempStorage.Batch batch,
                           SceneJobRegistry.JobState state,
                           String spatialMode) throws Exception {

        state.setStatus(SceneJobRegistry.RUNNING);

        validate(req);
        boolean isWkt = "wkt".equalsIgnoreCase(req.getFormat());
        String geom   = req.getGeometry().trim();
        String predicate = "within".equalsIgnoreCase(spatialMode) ? "within" : "intersects";

        // 1. Fetch matching rows.
        List<BuildingFootprintDTO> buildings = List.of();
        if (req.isIncludeBuildings()) {
            SceneQueryRequest.BuildingFilter f = req.getBldFilter();
            List<String> usages = (f != null) ? f.getUsages()    : null;
            Integer minF        = (f != null) ? f.getMinFloors() : null;
            Integer maxF        = (f != null) ? f.getMaxFloors() : null;
            buildings = isWkt
                    ? sceneMapper.findBuildingsByWktAndFilter(geom, usages, minF, maxF, predicate)
                    : sceneMapper.findBuildingsByGeoJsonAndFilter(geom, usages, minF, maxF, predicate);
        }
        List<ObjModelDTO> roads = List.of();
        if (req.isIncludeRoads()) {
            SceneQueryRequest.RoadFilter f = req.getRoadFilter();
            List<String> names = (f != null) ? f.getNames() : null;
            roads = isWkt
                    ? sceneMapper.findRoadObjsByWktAndFilter(geom, names, predicate)
                    : sceneMapper.findRoadObjsByGeoJsonAndFilter(geom, names, predicate);
        }
        log.info("scene {} query (predicate={}): buildings={} roads={}",
                batch.id(), predicate, buildings.size(), roads.size());

        int totalItems = buildings.size() + roads.size();
        state.setTotal(totalItems);

        // Empty result → still produce a valid (empty) tileset so the API
        // contract is consistent and the front-end never blocks waiting for
        // a file that will never appear.
        if (buildings.isEmpty() && roads.isEmpty()) {
            TilesetWriter.write(batch.dir(), 0, 0, 0, List.of());
            state.finishOk(storage.tilesetUrl(batch.id()), 0, 0);
            return;
        }

        // 2. Origin (XY only). Z values stay absolute — Cesium overlays a DEM
        //    terrain layer so buildings sit on real ground elevations.
        double[] origin = computeOrigin4544(buildings);
        if (origin == null) {
            Map<String, Double> qc = isWkt
                    ? metaMapper.centroidByWkt(geom)
                    : metaMapper.centroidByGeoJson(geom);
            origin = new double[]{ qc.get("x"), qc.get("y") };
        }
        final double cx = origin[0], cy = origin[1];
        Map<String, Double> lonlat = metaMapper.transformToWgs84(cx, cy);
        double lon = lonlat.get("lon"), lat = lonlat.get("lat");

        // Shared materials file referenced by every building OBJ's mtllib line.
        Files.writeString(
                batch.dir().resolve(WhiteModelObjWriter.MTL_NAME),
                WhiteModelObjWriter.mtlContent(),
                StandardCharsets.UTF_8);

        long t0 = System.nanoTime();
        ConcurrentLinkedQueue<TilesetWriter.Tile> tiles = new ConcurrentLinkedQueue<>();
        AtomicInteger bldOk = new AtomicInteger();
        AtomicInteger bldFail = new AtomicInteger();
        AtomicInteger roadOk = new AtomicInteger();
        AtomicInteger roadFail = new AtomicInteger();

        // 3. Buildings — fan out across the convert pool. Each task does
        //    extrude → obj write → obj2gltf → b3dm wrap → cleanup.
        List<Future<?>> bldFutures = new ArrayList<>(buildings.size());
        for (BuildingFootprintDTO dto : buildings) {
            bldFutures.add(registry.convertExecutor().submit(() -> {
                try {
                    if (processBuilding(dto, cx, cy, batch.dir(), tiles)) {
                        bldOk.incrementAndGet();
                        state.incSuccess();
                    } else {
                        bldFail.incrementAndGet();
                        state.incFailed();
                    }
                } catch (Exception ex) {
                    bldFail.incrementAndGet();
                    state.incFailed();
                    log.warn("building bid={} failed: {}", dto.getBid(), ex.toString());
                } finally {
                    state.incProcessed();
                    if (state.progressDue(5000L)) logProgress(batch.id(), state);
                }
            }));
        }
        awaitAll(bldFutures);

        // 4. Roads — same fan-out pattern.
        List<Future<?>> roadFutures = new ArrayList<>(roads.size());
        for (ObjModelDTO road : roads) {
            roadFutures.add(registry.convertExecutor().submit(() -> {
                try {
                    if (processRoad(road, cx, cy, batch.dir(), tiles)) {
                        roadOk.incrementAndGet();
                        state.incSuccess();
                    } else {
                        roadFail.incrementAndGet();
                        state.incFailed();
                    }
                } catch (Exception ex) {
                    roadFail.incrementAndGet();
                    state.incFailed();
                    log.warn("road {} failed: {}", road.getFilename(), ex.toString());
                } finally {
                    state.incProcessed();
                    if (state.progressDue(5000L)) logProgress(batch.id(), state);
                }
            }));
        }
        awaitAll(roadFutures);

        // 5. tileset.json — collect concurrent queue into a stable list.
        List<TilesetWriter.Tile> tileList = new ArrayList<>(tiles);
        TilesetWriter.write(batch.dir(), lon, lat, 0.0, tileList);
        Files.deleteIfExists(batch.dir().resolve(WhiteModelObjWriter.MTL_NAME));

        double dtMs = (System.nanoTime() - t0) / 1_000_000.0;
        log.info("scene batch {}: {} buildings ({} ok / {} fail), {} roads ({} ok / {} fail), "
                        + "built in {} ms (pool={})",
                batch.id(),
                buildings.size(), bldOk.get(), bldFail.get(),
                roads.size(),     roadOk.get(), roadFail.get(),
                String.format(Locale.ROOT, "%.0f", dtMs),
                registry.convertParallelism());

        state.finishOk(storage.tilesetUrl(batch.id()), bldOk.get(), roadOk.get());
        logProgress(batch.id(), state);
    }

    private void logProgress(String batchId, SceneJobRegistry.JobState state) {
        SceneStatusResponse snapshot = state.toResponse();
        log.info("scene {} progress: {}/{} success={} failed={} rate={}/s eta={}s",
                batchId, snapshot.getProcessed(), snapshot.getTotal(), snapshot.getSuccess(),
                snapshot.getFailed(), String.format(Locale.ROOT, "%.2f", snapshot.getRatePerSecond()),
                snapshot.getEtaSeconds());
    }

    /* ============== per-item workers ============== */

    /** @return true if the building produced a tile, false on skip/fail. */
    private boolean processBuilding(BuildingFootprintDTO dto, double cx, double cy,
                                    Path batchDir,
                                    ConcurrentLinkedQueue<TilesetWriter.Tile> sink) throws Exception {
        List<BuildingExtruder.Segment> segs = BuildingExtruder.extrude(dto);
        if (segs.isEmpty()) return false;
        double[] box = shiftAndBox(segs, -cx, -cy, 0);

        String stem = "b" + dto.getBid();
        Path objPath  = batchDir.resolve(stem + ".obj");
        Path glbPath  = batchDir.resolve(stem + ".glb");
        Path b3dmPath = batchDir.resolve(stem + ".b3dm");

        // Pick the façade texture for this building's usage + storey count, and
        // tile it once per floor (jzgd/lc), so layered textures show floor bands.
        // baseZ is the building base elevation (z is left absolute by shiftAndBox).
        byte[] texture   = BuildingTextureSelector.selectJpeg(dto.getBldUsage(), dto.getFloors());
        double baseZ     = dto.getBaseZ() != null ? dto.getBaseZ() : 0.0;
        int    lc        = (dto.getFloors() == null) ? 1 : Math.max(1, dto.getFloors());
        double floorH    = Math.max(0.5, dto.getJzgd() / lc);

        WhiteModelObjWriter.writeObj(objPath, dto.getBid(), segs);
        glbConverter.convert(objPath, glbPath, texture, baseZ, floorH, 4.0);
        Files.write(b3dmPath, B3dmWriter.wrap(Files.readAllBytes(glbPath)));
        Files.deleteIfExists(objPath);
        Files.deleteIfExists(glbPath);

        sink.add(toTile(stem + ".b3dm", box));
        return true;
    }

    /** @return true if the road produced a tile, false on skip/fail. */
    private boolean processRoad(ObjModelDTO road, double cx, double cy,
                                Path batchDir,
                                ConcurrentLinkedQueue<TilesetWriter.Tile> sink) throws Exception {
        Path srcObj = storage.projectRoot().resolve(road.getRelPath()).normalize();
        if (!Files.exists(srcObj)) return false;

        String stem = "r_" + stripExt(road.getFilename());
        Path objPath  = batchDir.resolve(stem + ".obj");
        Path glbPath  = batchDir.resolve(stem + ".glb");
        Path b3dmPath = batchDir.resolve(stem + ".b3dm");

        double[] box = copyAndShiftObj(srcObj, objPath, -cx, -cy, 0);
        if (box[3] < box[0]) return false;   // empty vertex set sentinel

        glbConverter.convert(objPath, glbPath, false);   // roads: no edge outlines
        Files.write(b3dmPath, B3dmWriter.wrap(Files.readAllBytes(glbPath)));
        Files.deleteIfExists(objPath);
        Files.deleteIfExists(glbPath);

        sink.add(toTile(stem + ".b3dm", box));
        return true;
    }

    /* ============== helpers ============== */

    private static void validate(SceneQueryRequest req) {
        if (!req.isIncludeRoads() && !req.isIncludeBuildings()) {
            throw new IllegalArgumentException(
                    "at least one of includeRoads/includeBuildings must be true");
        }
        String fmt = req.getFormat() == null ? "" : req.getFormat().trim().toLowerCase();
        if (!"wkt".equals(fmt) && !"geojson".equals(fmt)) {
            throw new IllegalArgumentException("format must be 'wkt' or 'geojson'");
        }
        String geom = req.getGeometry() == null ? "" : req.getGeometry().trim();
        if (geom.isEmpty()) {
            throw new IllegalArgumentException("geometry must not be blank");
        }
    }

    /** Wait for every future; swallow per-task exceptions (already handled inside). */
    private static void awaitAll(List<Future<?>> futs) {
        for (Future<?> f : futs) {
            try { f.get(); }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            catch (ExecutionException ee) {
                // The task wrappers already catch + log; this only fires if the
                // wrapper itself threw, which is a programming error.
                log.warn("convert task threw unexpectedly: {}", ee.getCause().toString());
            }
        }
    }

    /** Mean of building footprint centroids in 4544. Returns null when there
     *  are no buildings (caller falls back to the query polygon's centroid). */
    private static double[] computeOrigin4544(List<BuildingFootprintDTO> buildings) {
        if (buildings.isEmpty()) return null;
        try {
            // Each invocation creates its own WKTReader — JTS WKTReader isn't
            // thread-safe, but this method runs serially on the job thread.
            WKTReader rd = new WKTReader();
            double sx = 0, sy = 0;
            int n = 0;
            for (BuildingFootprintDTO d : buildings) {
                if (d.getWkt() == null) continue;
                var c = rd.read(d.getWkt()).getCentroid().getCoordinate();
                sx += c.x;  sy += c.y;  n++;
            }
            return (n > 0) ? new double[]{sx / n, sy / n} : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Shift segment vertices in-place; return bounding box of new coords. */
    private static double[] shiftAndBox(List<BuildingExtruder.Segment> segs,
                                        double dx, double dy, double dz) {
        double mnx=Double.POSITIVE_INFINITY, mny=mnx, mnz=mnx;
        double mxx=Double.NEGATIVE_INFINITY, mxy=mxx, mxz=mxx;
        for (BuildingExtruder.Segment seg : segs) {
            for (double[] v : seg.verts) {
                v[0] += dx;  v[1] += dy;  v[2] += dz;
                if (v[0] < mnx) mnx = v[0];  if (v[0] > mxx) mxx = v[0];
                if (v[1] < mny) mny = v[1];  if (v[1] > mxy) mxy = v[1];
                if (v[2] < mnz) mnz = v[2];  if (v[2] > mxz) mxz = v[2];
            }
        }
        return new double[]{mnx, mny, mnz, mxx, mxy, mxz};
    }

    /** Stream-copy an OBJ shifting every `v x y z` line; return local bounding box.
     *  ISO-8859-1 is used because road-export OBJs are 3ds Max exports with
     *  non-UTF-8 bytes in comments / mtllib names; ISO-8859-1 is byte-perfect
     *  on read+write, and we only parse the ASCII-only `v` lines numerically. */
    private static double[] copyAndShiftObj(Path src, Path dst,
                                            double dx, double dy, double dz) throws IOException {
        double mnx=Double.POSITIVE_INFINITY, mny=mnx, mnz=mnx;
        double mxx=Double.NEGATIVE_INFINITY, mxy=mxx, mxz=mxx;
        try (BufferedReader r = Files.newBufferedReader(src, StandardCharsets.ISO_8859_1);
             BufferedWriter w = Files.newBufferedWriter(dst, StandardCharsets.ISO_8859_1)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() > 2 && line.charAt(0) == 'v'
                        && (line.charAt(1) == ' ' || line.charAt(1) == '\t')) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 4) {
                        double x = Double.parseDouble(parts[1]) + dx;
                        double y = Double.parseDouble(parts[2]) + dy;
                        double z = Double.parseDouble(parts[3]) + dz;
                        if (x < mnx) mnx = x;  if (x > mxx) mxx = x;
                        if (y < mny) mny = y;  if (y > mxy) mxy = y;
                        if (z < mnz) mnz = z;  if (z > mxz) mxz = z;
                        w.write(String.format(Locale.ROOT, "v %.4f %.4f %.4f%n", x, y, z));
                        continue;
                    }
                }
                w.write(line);
                w.newLine();
            }
        }
        if (mnx == Double.POSITIVE_INFINITY) {
            // Sentinel: mxx < mnx → caller treats as empty
            return new double[]{0, 0, 0, -1, -1, -1};
        }
        return new double[]{mnx, mny, mnz, mxx, mxy, mxz};
    }

    private static TilesetWriter.Tile toTile(String uri, double[] box) {
        double cx = (box[0] + box[3]) / 2;
        double cy = (box[1] + box[4]) / 2;
        double cz = (box[2] + box[5]) / 2;
        double hx = Math.max(0.5, (box[3] - box[0]) / 2);
        double hy = Math.max(0.5, (box[4] - box[1]) / 2);
        double hz = Math.max(0.5, (box[5] - box[2]) / 2);
        return new TilesetWriter.Tile(uri,
                new double[]{cx, cy, cz}, new double[]{hx, hy, hz});
    }

    private static String stripExt(String fn) {
        int dot = fn.lastIndexOf('.');
        return dot < 0 ? fn : fn.substring(0, dot);
    }
}
