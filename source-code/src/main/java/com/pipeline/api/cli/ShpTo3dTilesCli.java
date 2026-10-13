package com.pipeline.api.cli;

import com.pipeline.api.dto.BuildingFootprintDTO;
import com.pipeline.api.geom.BuildingExtruder;
import com.pipeline.api.geom.WhiteModelObjWriter;
import com.pipeline.api.scene.B3dmWriter;
import com.pipeline.api.scene.BuildingTextureSelector;
import com.pipeline.api.scene.ObjToGlbConverter;
import com.pipeline.api.scene.TilesetWriter;
import org.geotools.api.data.DataStore;
import org.geotools.api.data.DataStoreFinder;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.WKTWriter;

import java.io.IOException;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Comparator;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Stand-alone SHP -> OBJ -> GLB -> b3dm -> 3D Tiles converter.
 *
 * Run from the Spring Boot fat jar without starting Spring:
 *
 * java -Dloader.main=com.pipeline.api.cli.ShpTo3dTilesCli \
 *      -jar pipeline-api-1.0.0.jar \
 *      --shp buildings.shp --out tiles
 *
 * The tileset root lon/lat are derived automatically:
 *   1. Resolve the source CRS: an explicit --prj file wins, otherwise the
 *      SHP companion .prj (WKT) is used.
 *   2. Build a MathTransform from the source CRS to EPSG:4326.
 *   3. Average every footprint's centroid in source CRS, transform to WGS84,
 *      and use that as the tileset origin.
 * If neither .prj source is available or the source CRS is already WGS84,
 * the centroid mean is treated as (lon, lat) directly.
 *
 * All parapet / shaft tuning knobs are exposed via --para-* and --shaft-*.
 */
public final class ShpTo3dTilesCli {

    private static final String DEFAULT_HEIGHT_ATTR = "jzgd";
    private static final String DEFAULT_BASE_Z_ATTR = "base_z";
    private static final String DEFAULT_FLOORS_ATTR = "lc";
    private static final String DEFAULT_USAGE_ATTR  = "bld_usage";
    private static final String DEFAULT_ID_ATTR     = "bid";

    private ShpTo3dTilesCli() {}

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.help) {
            Options.printUsage();
            return;
        }
        options.validate();

        Files.createDirectories(options.outDir);
        Path objDir  = options.outDir.resolve("obj");
        Path glbDir  = options.outDir.resolve("glb");
        Path b3dmDir = options.outDir.resolve("b3dm");
        Files.createDirectories(objDir);
        Files.createDirectories(glbDir);
        Files.createDirectories(b3dmDir);
        Path atlasDir = options.outDir.resolve("b3dm").resolve("textures");
        Files.createDirectories(atlasDir);
        if (options.textures && options.buildingLodChain) writeTextureAtlas(atlasDir.resolve("atlas.jpg"));

        List<BuildingRecord> records = readShapefile(options);
        if (records.isEmpty()) {
            TilesetWriter.write(options.outDir, 0.0, 0.0, 0.0, List.of());
            System.out.println("[ShpTo3dTilesCli] no polygon features found");
            return;
        }

        double[] centroidSrc = centroidMean(records);
        double[] originSrc   = centroidSrc;
        double[] originLL  = toLonLat(options.prjCrs, centroidSrc[0], centroidSrc[1]);
        if (originLL == null) {
            if (options.prjCrs == null) {
                System.err.println("[ShpTo3dTilesCli] WARNING: no .prj found "
                        + "(neither --prj nor a companion file). Falling back to "
                        + "treating source coordinates as lon/lat - if the data is "
                        + "projected, the tileset will be misplaced. "
                        + "Use --prj <file.prj> to specify the CRS.");
            }
            originLL = new double[]{centroidSrc[0], centroidSrc[1]};
        }
        System.out.printf(Locale.ROOT,
                "[ShpTo3dTilesCli] source centroid=(%.3f, %.3f) -> WGS84=(%.6f, %.6f)%n",
                centroidSrc[0], centroidSrc[1], originLL[0], originLL[1]);
        System.out.printf(Locale.ROOT,
                "[ShpTo3dTilesCli] convert workers=%d inFlight<=%d lod=%s resume=%s%n",
                Math.min(options.parallelism, Math.max(1, records.size())),
                Math.min(options.parallelism, Math.max(1, records.size())) * 2,
                options.lod, options.resume);

        BatchProgressLogger progress = new BatchProgressLogger(
                "convert/" + options.lod.name().toLowerCase(Locale.ROOT),
                records.size(), options.progressIntervalSeconds);
        int workers = Math.min(options.parallelism, Math.max(1, records.size()));
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CompletionService<ConversionResult> completion = new ExecutorCompletionService<>(executor);
        List<TilesetWriter.Tile> tiles = new ArrayList<>();
        int submitted = 0;
        int completed = 0;
        int ok = 0;
        int failed = 0;
        try {
            int maxInFlight = Math.max(1, workers * 2);
            for (BuildingRecord record : records) {
                completion.submit(() -> convertOne(record, options, originSrc,
                        objDir, glbDir, b3dmDir));
                submitted++;
                if (submitted - completed >= maxInFlight) {
                    ConversionResult result = completion.take().get();
                    completed++;
                    if (result.tile != null) { tiles.add(result.tile); ok++; }
                    else failed++;
                    progress.complete(result.tile != null);
                    logConversionResult(result);
                }
            }
            while (completed < submitted) {
                ConversionResult result = completion.take().get();
                completed++;
                if (result.tile != null) { tiles.add(result.tile); ok++; }
                else failed++;
                progress.complete(result.tile != null);
                logConversionResult(result);
            }
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(1, TimeUnit.MINUTES)) executor.shutdownNow();
        }
        tiles.sort(Comparator.comparing(TilesetWriter.Tile::uri));
        progress.report(true);

        TilesetWriter.write(options.outDir, originLL[0], originLL[1], 0.0, tiles,
                new TilesetWriter.Options(options.tileMaxContents, options.tileMaxDepth,
                        1.0, options.tileExternalDepth));
        System.out.printf(Locale.ROOT,
                "[ShpTo3dTilesCli] input=%s features=%d ok=%d failed=%d%n",
                options.shp, records.size(), ok, failed);
        System.out.println("[ShpTo3dTilesCli] output=" + options.outDir.toAbsolutePath());
    }

    /* ---------------- shapefile + CRS ---------------- */

    private static List<BuildingRecord> readShapefile(Options options) throws IOException {
        Map<String, Object> params = new HashMap<>();
        URL url = options.shp.toUri().toURL();
        params.put("url", url);

        // CRS resolution order: explicit --prj > SHP companion .prj > none.
        if (options.prj != null) {
            String prjWkt = Files.readString(options.prj, StandardCharsets.US_ASCII).trim();
            options.prjCrs = parseCrs(prjWkt);
            System.out.println("[ShpTo3dTilesCli] using explicit .prj: "
                    + options.prj.toAbsolutePath());
        } else {
            Path prjCandidate = options.shp.toAbsolutePath().getParent()
                    .resolve(stripShpExt(options.shp.getFileName().toString()) + ".prj");
            if (Files.exists(prjCandidate)) {
                String prjWkt = Files.readString(prjCandidate, StandardCharsets.US_ASCII).trim();
                options.prjCrs = parseCrs(prjWkt);
            }
        }

        List<BuildingRecord> records = new ArrayList<>();
        DataStore store = DataStoreFinder.getDataStore(params);
        try {
            if (store == null) {
                throw new IOException("cannot open shapefile: " + options.shp);
            }
            String typeName = store.getTypeNames()[0];
            SimpleFeatureSource source = store.getFeatureSource(typeName);
            SimpleFeatureCollection collection = source.getFeatures();
            WKTWriter wktWriter = new WKTWriter();
            int index = 0;
            try (SimpleFeatureIterator it = collection.features()) {
                while (it.hasNext()) {
                    SimpleFeature feature = it.next();
                    Object value = feature.getDefaultGeometry();
                    if (!(value instanceof Geometry geometry) || geometry.isEmpty()) continue;

                    long id = longValue(attr(feature, options.idAttr), index + 1L);
                    BuildingFootprintDTO dto = new BuildingFootprintDTO();
                    dto.setBid(id);
                    dto.setJzgd(doubleValue(attr(feature, options.heightAttr), 3.0));
                    dto.setBaseZ(doubleValue(attr(feature, options.baseZAttr), 0.0));
                    int floors = intValue(attr(feature, options.floorsAttr), 0);
                    if (floors <= 0) {
                        // Fallback: datasets often carry a decimal floor count
                        // next to the integer one (e.g. LC -> LC1).
                        floors = Math.max(0, (int) Math.round(
                                doubleValue(attr(feature, options.floorsAttr + "1"), 0.0)));
                    }
                    dto.setFloors(floors);
                    dto.setBldUsage(stringValue(attr(feature, options.usageAttr), ""));
                    dto.setWkt(wktWriter.write(geometry));
                    records.add(new BuildingRecord(id, index, dto));
                    index++;
                }
            }
        } finally {
            if (store != null) {
                store.dispose();
            }
        }
        // Floor-attribute diagnostics 鈥?a missing floors field silently turns
        // off parapets and rooftop shafts, so make it visible.
        int withFloors = 0, parapetCount = 0, shaftCount = 0;
        for (BuildingRecord r : records) {
            Integer f = r.dto.getFloors();
            if (f != null && f > 0) withFloors++;
            if (f != null && f >= options.parapetMinLc) parapetCount++;
            if (f != null && f >= options.shaftMinLc) shaftCount++;
        }
        System.out.printf(Locale.ROOT,
                "[ShpTo3dTilesCli] floors attr='%s': %d/%d buildings have floors, "
                        + "%d >= parapet-min-lc(%d), %d >= shaft-min-lc(%d)%n",
                options.floorsAttr, withFloors, records.size(),
                parapetCount, options.parapetMinLc, shaftCount, options.shaftMinLc);
        if (withFloors == 0) {
            System.out.println("[ShpTo3dTilesCli] WARNING: no building has a usable floors "
                    + "value 鈥?parapets and rooftop shafts will NOT be generated. "
                    + "Check the --floors-attr field name.");
        }
        return records;
    }

    private static CoordinateReferenceSystem parseCrs(String wkt) {
        try {
            return CRS.parseWKT(wkt);
        } catch (Exception ex) {
            System.err.println("[ShpTo3dTilesCli] failed to parse .prj WKT: " + ex.getMessage());
            return null;
        }
    }

    /**
     * Convert a point in {@code crs} to WGS84 lon/lat. Returns null if the CRS
     * is missing, already WGS84 (the input is treated as lon/lat directly),
     * or the transformation can't be constructed.
     */
    private static double[] toLonLat(CoordinateReferenceSystem crs, double x, double y) {
        if (crs == null) return null;
        try {
            CoordinateReferenceSystem wgs84 = CRS.decode("EPSG:4326", true);
            if (CRS.equalsIgnoreMetadata(crs, wgs84)) {
                return new double[]{x, y};
            }
            MathTransform tx = CRS.findMathTransform(crs, wgs84, true);
            double[] pt = new double[]{x, y};
            tx.transform(pt, 0, pt, 0, 1);
            return new double[]{pt[0], pt[1]};
        } catch (Exception ex) {
            System.err.println("[ShpTo3dTilesCli] CRS->WGS84 transform failed: " + ex.getMessage());
            return null;
        }
    }

    private static String stripShpExt(String name) {
        int dot = name.toLowerCase(Locale.ROOT).lastIndexOf(".shp");
        return dot <= 0 ? name : name.substring(0, dot);
    }

    /* ---------------- geometric helpers ---------------- */

    private static double[] centroidMean(List<BuildingRecord> records) {
        double sx = 0.0;
        double sy = 0.0;
        int count = 0;
        for (BuildingRecord record : records) {
            try {
                Geometry geometry = new WKTReader().read(record.dto.getWkt());
                Coordinate c = geometry.getCentroid().getCoordinate();
                sx += c.x;
                sy += c.y;
                count++;
            } catch (Exception ignored) {
                // The feature will be reported by the conversion loop.
            }
        }
        return count == 0 ? new double[]{0.0, 0.0} : new double[]{sx / count, sy / count};
    }

    private static double[] shiftAndBox(List<BuildingExtruder.Segment> segments,
                                        double dx, double dy, double dz) {
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (BuildingExtruder.Segment segment : segments) {
            for (double[] vertex : segment.verts) {
                vertex[0] += dx;
                vertex[1] += dy;
                vertex[2] += dz;
                minX = Math.min(minX, vertex[0]);
                minY = Math.min(minY, vertex[1]);
                minZ = Math.min(minZ, vertex[2]);
                maxX = Math.max(maxX, vertex[0]);
                maxY = Math.max(maxY, vertex[1]);
                maxZ = Math.max(maxZ, vertex[2]);
            }
        }
        return new double[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    private static TilesetWriter.Tile toTile(String uri, double[] box) {
        return new TilesetWriter.Tile(uri,
                new double[]{(box[0] + box[3]) / 2,
                        (box[1] + box[4]) / 2,
                        (box[2] + box[5]) / 2},
                new double[]{Math.max(0.5, (box[3] - box[0]) / 2),
                        Math.max(0.5, (box[4] - box[1]) / 2),
                        Math.max(0.5, (box[5] - box[2]) / 2)});
    }

    private static TilesetWriter.Tile toTile(String uri, double[] box,
                                             List<TilesetWriter.LodContent> lods) {
        TilesetWriter.Tile tile = toTile(uri, box);
        return new TilesetWriter.Tile(tile.uri(), tile.localBoxCenter(), tile.localBoxHalf(), lods);
    }

    private record BuildingRecord(long id, int ordinal, BuildingFootprintDTO dto) {}

    private record ConversionResult(long id, TilesetWriter.Tile tile, String error) {}

    private static ConversionResult convertOne(BuildingRecord record, Options options,
                                               double[] originSrc, Path objDir,
                                               Path glbDir, Path b3dmDir) {
        if (options.buildingLodChain) {
            return convertOneChain(record, options, originSrc, objDir, glbDir, b3dmDir);
        }
        return convertOneSingle(record, options, originSrc, objDir, glbDir, b3dmDir);
    }

    private static ConversionResult convertOneSingle(BuildingRecord record, Options options,
                                                     double[] originSrc, Path objDir,
                                                     Path glbDir, Path b3dmDir) {
        Path obj = null;
        Path glb = null;
        try {
            BuildingFootprintDTO dto = copyForLod(record.dto, options.lod);
            BuildingExtruder.Profile profile = options.toProfile(options.lod);
            List<BuildingExtruder.Segment> segments = BuildingExtruder.extrude(dto, profile);
            if (segments.isEmpty()) return new ConversionResult(record.id, null, "empty extrusion");

            // Ordinal is part of the stem so repeated business IDs cannot overwrite output.
            String stem = String.format(Locale.ROOT, "b%012d_%06d", record.id, record.ordinal);
            obj = objDir.resolve(stem + ".obj");
            glb = glbDir.resolve(stem + ".glb");
            Path b3dm = b3dmDir.resolve(stem + ".b3dm");
            Path done = b3dm.resolveSibling(b3dm.getFileName() + ".done");
            String resumeKey = options.resumeKey(record);
            if (options.resume && Files.exists(done) && Files.size(b3dm) > 0
                    && resumeKey.equals(Files.readString(done, StandardCharsets.US_ASCII).trim())) {
                return new ConversionResult(record.id,
                        toTile("b3dm/" + b3dm.getFileName(), shiftAndBox(segments,
                                -originSrc[0], -originSrc[1], 0.0)), null);
            }

            double[] box = shiftAndBox(segments, -originSrc[0], -originSrc[1], 0.0);
            WhiteModelObjWriter.writeObj(obj, record.id, segments);
            byte[] texture = options.textures
                    ? BuildingTextureSelector.selectJpeg(dto.getBldUsage(), dto.getFloors()) : null;
            int floors = dto.getFloors() == null ? 1 : Math.max(1, dto.getFloors());
            double floorHeight = Math.max(0.5, dto.getJzgd() / floors);
            new ObjToGlbConverter().convert(obj, glb, texture,
                    dto.getBaseZ() == null ? 0.0 : dto.getBaseZ(), floorHeight, 4.0);
            Path temp = b3dm.resolveSibling(b3dm.getFileName() + ".tmp");
            Files.write(temp, B3dmWriter.wrap(Files.readAllBytes(glb)));
            moveReplacing(temp, b3dm);
            Files.writeString(done, resumeKey + "\n", StandardCharsets.US_ASCII);
            if (!options.keepIntermediate) {
                Files.deleteIfExists(obj);
                Files.deleteIfExists(glb);
            }
            return new ConversionResult(record.id, toTile("b3dm/" + b3dm.getFileName(), box), null);
        } catch (Exception ex) {
            return new ConversionResult(record.id, null,
                    ex.getClass().getSimpleName() + ": " + String.valueOf(ex.getMessage()));
        }
    }

    private static ConversionResult convertOneChain(BuildingRecord record, Options options,
                                                    double[] originSrc, Path objDir,
                                                    Path glbDir, Path b3dmDir) {
        try {
            String stem = String.format(Locale.ROOT, "b%012d_%06d", record.id, record.ordinal);
            List<TilesetWriter.LodContent> lods = new ArrayList<>();
            double[] finalBox = null;
            LodProfile[] profiles = {LodProfile.L1_SIMPLE, LodProfile.L2_STANDARD, LodProfile.L3_DETAIL};
            // These values are Cesium screen-space refinement thresholds in
            // model units. Keep the coarse model selected over a useful range
            // and avoid an immediate zero-error transition to the final mesh.
            double[] errors = {18.0, 8.0, 2.0};
            for (int i = 0; i < profiles.length; i++) {
                LodProfile profile = profiles[i];
                BuildingFootprintDTO dto = copyForLod(record.dto, profile);
                List<BuildingExtruder.Segment> segments = BuildingExtruder.extrude(dto, options.toProfile(profile));
                if (segments.isEmpty()) continue;
                double[] box = shiftAndBox(segments, -originSrc[0], -originSrc[1], 0.0);
                if (finalBox == null) finalBox = box;
                else for (int k = 0; k < 3; k++) {
                    finalBox[k] = Math.min(finalBox[k], box[k]);
                    finalBox[k + 3] = Math.max(finalBox[k + 3], box[k + 3]);
                }
                Path obj = objDir.resolve(stem + "_l" + (i + 1) + ".obj");
                Path glb = glbDir.resolve(stem + "_l" + (i + 1) + ".glb");
                Path b3dm = b3dmDir.resolve(stem + "_l" + (i + 1) + ".b3dm");
                Path done = b3dm.resolveSibling(b3dm.getFileName() + ".done");
                String key = options.resumeKey(record) + "|chain|" + profile.name();
                if (!(options.resume && Files.exists(done) && Files.size(b3dm) > 0
                        && key.equals(Files.readString(done, StandardCharsets.US_ASCII).trim()))) {
                    WhiteModelObjWriter.writeObj(obj, record.id, segments);
                    byte[] texture = options.textures && profile != LodProfile.L1_SIMPLE
                            ? BuildingTextureSelector.selectJpeg(dto.getBldUsage(), dto.getFloors()) : null;
                    int floors = dto.getFloors() == null ? 1 : Math.max(1, dto.getFloors());
                    double floorHeight = Math.max(0.5, dto.getJzgd() / floors);
                    if (profile == LodProfile.L1_SIMPLE) {
                        new ObjToGlbConverter().convert(obj, glb);
                    } else {
                        String stemTex = BuildingTextureSelector.selectStem(dto.getBldUsage(), dto.getFloors());
                        AtlasCell cell = atlasCell(stemTex);
                        new ObjToGlbConverter().convertAtlas(obj, glb, "textures/atlas.jpg",
                                dto.getBaseZ() == null ? 0.0 : dto.getBaseZ(), floorHeight, 4.0,
                                cell.scaleU, cell.scaleV, cell.offsetU, cell.offsetV);
                    }
                    Path temp = b3dm.resolveSibling(b3dm.getFileName() + ".tmp");
                    Files.write(temp, B3dmWriter.wrap(Files.readAllBytes(glb)));
                    moveReplacing(temp, b3dm);
                    Files.writeString(done, key + "\n", StandardCharsets.US_ASCII);
                    if (!options.keepIntermediate) {
                        Files.deleteIfExists(obj);
                        Files.deleteIfExists(glb);
                    }
                }
                lods.add(new TilesetWriter.LodContent("b3dm/" + b3dm.getFileName(), errors[i]));
            }
            if (lods.isEmpty() || finalBox == null) return new ConversionResult(record.id, null, "empty LOD chain");
            return new ConversionResult(record.id,
                    toTile(lods.get(0).uri(), finalBox, lods), null);
        } catch (Exception ex) {
            return new ConversionResult(record.id, null,
                    ex.getClass().getSimpleName() + ": " + String.valueOf(ex.getMessage()));
        }
    }

    private record AtlasCell(double scaleU, double scaleV, double offsetU, double offsetV) {}

    private static AtlasCell atlasCell(String stem) {
        String[] stems = {"db_sumo_bui_001", "dise_G1_5", "dise_G1_dx_01",
                "dise_G1_dx_02", "dise_G1_dx_05", "dise_G1_dx_06"};
        int index = 0;
        for (int i = 0; i < stems.length; i++) if (stems[i].equals(stem)) { index = i; break; }
        return new AtlasCell(1.0 / 3.0, 0.5, (index % 3) / 3.0, (index / 3) * 0.5);
    }

    private static void writeTextureAtlas(Path out) throws IOException {
        BufferedImage atlas = new BufferedImage(1536, 1024, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = atlas.createGraphics();
        g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, atlas.getWidth(), atlas.getHeight());
        String[] stems = {"db_sumo_bui_001", "dise_G1_5", "dise_G1_dx_01",
                "dise_G1_dx_02", "dise_G1_dx_05", "dise_G1_dx_06"};
        for (int i = 0; i < stems.length; i++) {
            try (InputStream in = ShpTo3dTilesCli.class.getResourceAsStream("/textures/" + stems[i] + ".jpg")) {
                if (in == null) continue;
                BufferedImage src = ImageIO.read(in);
                g.drawImage(src, (i % 3) * 512, (i / 3) * 512, 512, 512, null);
            }
        }
        g.dispose();
        ImageIO.write(atlas, "jpg", out.toFile());
        System.out.println("[textures] wrote shared atlas=" + out.toAbsolutePath());
    }

    private static void logConversionResult(ConversionResult result) {
        if (result.error != null) {
            System.err.printf(Locale.ROOT, "[ShpTo3dTilesCli] feature %d failed: %s%n",
                    result.id, result.error);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static BuildingFootprintDTO copyForLod(BuildingFootprintDTO source, LodProfile lod)
            throws Exception {
        BuildingFootprintDTO dto = new BuildingFootprintDTO();
        dto.setBid(source.getBid());
        dto.setJzgd(source.getJzgd());
        dto.setBaseZ(source.getBaseZ());
        dto.setBldUsage(source.getBldUsage());
        dto.setFloors(source.getFloors());
        Geometry geometry = new WKTReader().read(source.getWkt());
        Geometry simplified = lod.simplify(geometry);
        dto.setWkt(new WKTWriter().write(simplified));
        return dto;
    }

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number n) return n.doubleValue();
        if (value != null) {
            try {
                return Double.parseDouble(value.toString().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static int intValue(Object value, int fallback) {
        return (int) Math.round(doubleValue(value, fallback));
    }

    private static long longValue(Object value, long fallback) {
        return Math.round(doubleValue(value, fallback));
    }

    private static String stringValue(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    /**
     * Case-insensitive attribute lookup. GeoTools {@code getAttribute} matches
     * field names exactly, so a default like "lc" silently misses a DBF field
     * spelled "LC" 鈥?this helper falls back to an equalsIgnoreCase scan over
     * the feature type so attribute casing never silently disables geometry
     * (parapet / rooftop shaft) again.
     */
    private static Object attr(SimpleFeature feature, String name) {
        Object value = feature.getAttribute(name);
        if (value != null) return value;
        if (name == null) return null;
        for (org.geotools.api.feature.type.AttributeDescriptor d
                : feature.getFeatureType().getAttributeDescriptors()) {
            if (d.getLocalName().equalsIgnoreCase(name)) {
                return feature.getAttribute(d.getLocalName());
            }
        }
        return null;
    }

    private static final class Options {
        Path shp;
        Path outDir;
        Path prj;                          // optional explicit .prj override
        CoordinateReferenceSystem prjCrs;   // populated by readShapefile

        // SHP field names
        String idAttr      = DEFAULT_ID_ATTR;
        String heightAttr  = DEFAULT_HEIGHT_ATTR;
        String baseZAttr   = DEFAULT_BASE_Z_ATTR;
        String floorsAttr  = DEFAULT_FLOORS_ATTR;
        String usageAttr   = DEFAULT_USAGE_ATTR;

        // Generation switches
        boolean textures;
        boolean buildingLodChain;
        boolean keepIntermediate;
        boolean resume;
        int parallelism = Math.min(8, Math.max(1, Runtime.getRuntime().availableProcessors()));
        int progressIntervalSeconds = 5;
        LodProfile lod = LodProfile.NONE;
        int tileMaxContents = 64;
        int tileMaxDepth = 12;
        int tileExternalDepth = 2;

        // Parapet knobs
        int    parapetMinLc     = 5;
        double parapetMinHeight = 0.30;
        double parapetMaxHeight = 1.60;
        double parapetPerFloor  = 0.10;
        double parapetThickness = 0.25;

        // Shaft knobs (single shaft, no count decision)
        int    shaftMinLc       = 9;
        double shaftInset       = 1.50;
        double shaftClearance   = 2.00;
        double shaftMaxLen      = 8.00;
        double shaftMaxWid      = 6.00;
        double shaftMaxHeight   = 6.00;
        double shaftCenterBoxRatio = 0.80;

        boolean help;

        BuildingExtruder.Profile toProfile() {
            return toProfile(lod);
        }

        BuildingExtruder.Profile toProfile(LodProfile selectedLod) {
            BuildingExtruder.Profile out = new BuildingExtruder.Profile();
            out.parapetMinLc     = parapetMinLc;
            out.parapetMinHeight = parapetMinHeight;
            out.parapetMaxHeight = parapetMaxHeight;
            out.parapetPerFloor  = parapetPerFloor;
            out.parapetThickness = parapetThickness;
            out.shaftMinLc       = shaftMinLc;
            out.shaftInset       = shaftInset;
            out.shaftClearance   = shaftClearance;
            out.shaftMaxLen      = shaftMaxLen;
            out.shaftMaxWid      = shaftMaxWid;
            out.shaftMaxHeight   = shaftMaxHeight;
            out.shaftCenterBoxRatio = shaftCenterBoxRatio;
            if (!selectedLod.includeParapet()) out.parapetMinLc = Integer.MAX_VALUE;
            if (!selectedLod.includeShaft()) out.shaftMinLc = Integer.MAX_VALUE;
            return out;
        }

        String resumeKey(BuildingRecord record) {
            return String.join("|", lod.name(), idAttr, heightAttr, baseZAttr, floorsAttr,
                    usageAttr, Boolean.toString(textures), Integer.toString(parapetMinLc),
                    Double.toString(parapetThickness), Integer.toString(shaftMinLc),
                    Long.toString(record.id), Integer.toString(record.ordinal),
                    Integer.toHexString(record.dto.getWkt() == null ? 0 : record.dto.getWkt().hashCode()),
                    String.valueOf(record.dto.getJzgd()), String.valueOf(record.dto.getBaseZ()),
                    String.valueOf(record.dto.getFloors()));
        }

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                String key = args[i];
                if ("--help".equals(key) || "-h".equals(key)) {
                    o.help = true;
                    continue;
                }
                if (!key.startsWith("--") || i + 1 >= args.length) {
                    throw new IllegalArgumentException("invalid argument: " + key);
                }
                String value = args[++i];
                switch (key) {
                    case "--shp"                 -> o.shp = Paths.get(value);
                    case "--out"                 -> o.outDir = Paths.get(value);
                    case "--prj"                 -> o.prj = Paths.get(value);

                    case "--id-attr"             -> o.idAttr     = value;
                    case "--height-attr"         -> o.heightAttr = value;
                    case "--base-z-attr"         -> o.baseZAttr  = value;
                    case "--floors-attr"         -> o.floorsAttr = value;
                    case "--usage-attr"          -> o.usageAttr  = value;

                    case "--textures"            -> o.textures          = Boolean.parseBoolean(value);
                    case "--building-lod-chain"  -> o.buildingLodChain   = Boolean.parseBoolean(value);
                    case "--keep-intermediate"   -> o.keepIntermediate  = Boolean.parseBoolean(value);
                    case "--resume"              -> o.resume            = Boolean.parseBoolean(value);
                    case "--parallelism"         -> o.parallelism       = Integer.parseInt(value);
                    case "--progress-interval-seconds" -> o.progressIntervalSeconds = Integer.parseInt(value);
                    case "--lod"                 -> o.lod = LodProfile.valueOf(value.trim().toUpperCase(Locale.ROOT));
                    case "--tile-max-contents"  -> o.tileMaxContents = Integer.parseInt(value);
                    case "--tile-max-depth"     -> o.tileMaxDepth = Integer.parseInt(value);
                    case "--tile-external-depth" -> o.tileExternalDepth = Integer.parseInt(value);

                    case "--parapet-min-lc"      -> o.parapetMinLc      = Integer.parseInt(value);
                    case "--parapet-min-height"  -> o.parapetMinHeight  = Double.parseDouble(value);
                    case "--parapet-max-height"  -> o.parapetMaxHeight  = Double.parseDouble(value);
                    case "--parapet-per-floor"   -> o.parapetPerFloor   = Double.parseDouble(value);
                    case "--parapet-thickness"   -> o.parapetThickness  = Double.parseDouble(value);

                    case "--shaft-min-lc"        -> o.shaftMinLc        = Integer.parseInt(value);
                    case "--shaft-inset"         -> o.shaftInset        = Double.parseDouble(value);
                    case "--shaft-clearance"     -> o.shaftClearance    = Double.parseDouble(value);
                    case "--shaft-max-len"       -> o.shaftMaxLen       = Double.parseDouble(value);
                    case "--shaft-max-wid"       -> o.shaftMaxWid       = Double.parseDouble(value);
                    case "--shaft-max-height"    -> o.shaftMaxHeight    = Double.parseDouble(value);
                    case "--shaft-center-ratio" -> o.shaftCenterBoxRatio = Double.parseDouble(value);

                    default -> throw new IllegalArgumentException("unknown argument: " + key);
                }
            }
            return o;
        }

        void validate() {
            if (shp == null || !Files.exists(shp)) {
                throw new IllegalArgumentException("--shp must point to an existing .shp file");
            }
            if (outDir == null) {
                throw new IllegalArgumentException("--out is required");
            }
            if (parallelism <= 0) throw new IllegalArgumentException("--parallelism must be > 0");
            if (tileMaxContents <= 0 || tileMaxDepth <= 0 || tileExternalDepth <= 0
                    || tileExternalDepth >= tileMaxDepth) {
                throw new IllegalArgumentException("tile hierarchy parameters are invalid");
            }
            if (progressIntervalSeconds <= 0) {
                throw new IllegalArgumentException("--progress-interval-seconds must be > 0");
            }
            if (prj != null && !Files.exists(prj)) {
                throw new IllegalArgumentException("--prj must point to an existing .prj file");
            }
            if (parapetMinHeight < 0 || parapetMaxHeight < parapetMinHeight) {
                throw new IllegalArgumentException(
                        "--parapet-max-height must be >= --parapet-min-height");
            }
            if (parapetThickness <= 0) {
                throw new IllegalArgumentException("--parapet-thickness must be > 0");
            }
            if (shaftInset <= 0 || shaftClearance < 0
                    || shaftMaxLen <= 0 || shaftMaxWid <= 0 || shaftMaxHeight <= 0) {
                throw new IllegalArgumentException("shaft dimensions must be positive");
            }
            if (shaftCenterBoxRatio <= 0 || shaftCenterBoxRatio > 1) {
                throw new IllegalArgumentException(
                        "--shaft-center-ratio must be in (0, 1]");
            }
        }

        static void printUsage() {
            System.out.println("""
                    SHP -> OBJ -> GLB -> b3dm -> 3D Tiles

                    The tileset root lon/lat are derived from the source CRS
                    (explicit --prj wins, else the SHP companion .prj) and the
                    centroid mean of every footprint, projected to WGS84.
                    No --lon/--lat/--origin arguments are needed.

                    Required:
                      --shp <file.shp>          input polygon shapefile (with .prj alongside)
                      --out <directory>         output directory

                    Coordinate system:
                      --prj <file.prj>          optional explicit .prj (WKT). Use it when
                                                the SHP has no companion .prj, when the
                                                companion one is wrong, or to reuse one
                                                .prj for a batch of shapefiles.

                    Optional SHP field overrides (defaults shown):
                      --id-attr <name>           default: bid
                      --height-attr <name>       default: jzgd
                      --base-z-attr <name>       default: base_z
                      --floors-attr <name>       default: lc
                      --usage-attr <name>        default: bld_usage

                    Generation switches:
                      --textures true|false       embed facade textures, default: false
                      --keep-intermediate true|false
                                                 keep obj/ and glb/ files, default: false
                      --resume true|false          reuse completed .b3dm + .done outputs
                      --parallelism N              conversion workers, default: min(8, CPU)
                      --progress-interval-seconds N
                                                 progress log interval, default: 5
                      --lod NONE|L1_SIMPLE|L2_STANDARD|L3_DETAIL
                                                 per-building simplification/detail level
                      --tile-max-contents N      max b3dm contents per leaf, default: 64
                      --tile-max-depth N         max spatial tree depth, default: 12
                      --tile-external-depth N    write nested tilesets from this depth, default: 2

                    Parapet knobs (metres unless noted):
                      --parapet-min-lc      N     lc threshold, default: 5
                      --parapet-min-height   m     default: 0.30
                      --parapet-max-height   m     default: 1.60
                      --parapet-per-floor    m     default: 0.10
                      --parapet-thickness    m     ring wall thickness, default: 0.25

                    Rooftop machine-room knobs (single shaft, metres):
                      --shaft-min-lc         N     lc threshold, default: 9
                      --shaft-inset          m     footprint inward offset, default: 1.50
                      --shaft-clearance      m     shaft-to-boundary clearance, default: 2.00
                      --shaft-max-len        m     max long axis, default: 8.00
                      --shaft-max-wid        m     max short axis, default: 6.00
                      --shaft-max-height     m     max rise, default: 6.00
                      --shaft-center-ratio  ratio central band / full span, default: 0.80
                    """);
        }
    }
}

