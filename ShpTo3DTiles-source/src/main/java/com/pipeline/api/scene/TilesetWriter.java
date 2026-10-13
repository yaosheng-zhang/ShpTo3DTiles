package com.pipeline.api.scene;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes a Cesium 3D Tiles 1.0 tileset with a bounded spatial quadtree. */
public final class TilesetWriter {
    private static final double WGS84_A = 6378137.0;
    private static final double WGS84_E2 = 0.00669437999014;
    private static final double ROOT_GEOM_ERR = 500.0;

    private TilesetWriter() {}
    public record LodContent(String uri, double geometricError) {}
    public record Tile(String uri, double[] localBoxCenter, double[] localBoxHalf,
                       List<LodContent> lods) {
        public Tile(String uri, double[] localBoxCenter, double[] localBoxHalf) {
            this(uri, localBoxCenter, localBoxHalf, List.of());
        }
        public Tile {
            lods = lods == null ? List.of() : List.copyOf(lods);
        }
    }

    /** Limits the number of content nodes under one spatial node. */
    public record Options(int maxLeafContents, int maxDepth, double errorScale, int externalizeDepth) {
        public Options(int maxLeafContents, int maxDepth, double errorScale) {
            this(maxLeafContents, maxDepth, errorScale, 2);
        }
        public Options {
            if (maxLeafContents < 1) throw new IllegalArgumentException("maxLeafContents must be > 0");
            if (maxDepth < 1) throw new IllegalArgumentException("maxDepth must be > 0");
            if (!(errorScale > 0)) throw new IllegalArgumentException("errorScale must be > 0");
            if (externalizeDepth < 1) throw new IllegalArgumentException("externalizeDepth must be > 0");
        }
        public static Options defaults() { return new Options(64, 12, 1.0); }
    }

    public static void write(Path batchDir, double lon, double lat, double h, List<Tile> tiles)
            throws IOException { write(batchDir, lon, lat, h, tiles, Options.defaults()); }

    public static void write(Path batchDir, double lon, double lat, double h, List<Tile> tiles,
                             Options options) throws IOException {
        double[] transform = eastNorthUpToFixedFrame(lon, lat, h);
        double[] rootCenter = new double[]{0, 0, 0};
        double[] rootHalf = new double[]{0, 0, 0};
        if (!tiles.isEmpty()) unionBoxes(tiles, rootCenter, rootHalf);
        double sceneError = tiles.isEmpty() ? ROOT_GEOM_ERR
                : Math.max(ROOT_GEOM_ERR, maxDimension(rootHalf) * options.errorScale());

        Map<String, Object> tileset = new LinkedHashMap<>();
        tileset.put("asset", Map.of("version", "1.0"));
        tileset.put("geometricError", sceneError);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("transform", boxedArray(transform));
        root.put("boundingVolume", Map.of("box", boxArray(rootCenter, rootHalf)));
        root.put("geometricError", sceneError);
        root.put("refine", "REPLACE");
        if (!tiles.isEmpty()) root.put("children", buildChildren(batchDir, batchDir.resolve("tileset.json"), tiles, rootCenter, rootHalf, 0, options));
        tileset.put("root", root);

        ObjectMapper om = new ObjectMapper();
        om.enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(batchDir.resolve("tileset.json"), om.writeValueAsString(tileset));
        System.out.printf(java.util.Locale.ROOT,
                "[tileset] wrote contents=%d rootError=%.1f maxLeaf=%d maxDepth=%d%n",
                tiles.size(), sceneError, options.maxLeafContents(), options.maxDepth());
    }

    private static List<Map<String, Object>> buildChildren(Path batchDir, Path currentFile,
                                                             List<Tile> tiles, double[] center,
                                                             double[] half, int depth, Options options) throws IOException {
        if (tiles.size() <= options.maxLeafContents() || depth >= options.maxDepth()) return contentLeaves(tiles, currentFile, batchDir);
        double midX = center[0], midY = center[1];
        @SuppressWarnings("unchecked")
        List<Tile>[] buckets = new List[]{new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>()};
        for (Tile tile : tiles) {
            double[] c = tile.localBoxCenter();
            int quadrant = (c[0] >= midX ? 1 : 0) | (c[1] >= midY ? 2 : 0);
            buckets[quadrant].add(tile);
        }
        int nonEmpty = 0;
        for (List<Tile> bucket : buckets) if (!bucket.isEmpty()) nonEmpty++;
        if (nonEmpty <= 1) return contentLeaves(tiles, currentFile, batchDir);
        List<Map<String, Object>> children = new ArrayList<>(nonEmpty);
        for (List<Tile> bucket : buckets) {
            if (bucket.isEmpty()) continue;
            double[] childCenter = new double[3], childHalf = new double[3];
            unionBoxes(bucket, childCenter, childHalf);
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("boundingVolume", Map.of("box", boxArray(childCenter, childHalf)));
            child.put("geometricError", Math.max(1.0, maxDimension(childHalf)));
            child.put("refine", "REPLACE");
            if (depth + 1 >= options.externalizeDepth() && depth + 1 < options.maxDepth()) {
                String file = "tileset_" + depth + "_" + children.size() + "_" +
                        Math.abs(bucket.get(0).uri().hashCode()) + ".json";
                Path childPath = batchDir.resolve(file);
                writeSubtree(childPath, bucket, childCenter, childHalf, depth + 1, options);
                child.put("content", Map.of("uri", file));
            } else {
                // Spatial nodes are index-only. The first renderable level is
                // the per-building L1/L2/L3 chain; no box proxy is emitted.
                child.put("children", buildChildren(batchDir, currentFile, bucket,
                        childCenter, childHalf, depth + 1, options));
            }
            children.add(child);
        }
        return children;
    }

    private static void writeSubtree(Path path, List<Tile> tiles, double[] center,
                                     double[] half, int depth, Options options) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("boundingVolume", Map.of("box", boxArray(center, half)));
        root.put("geometricError", Math.max(1.0, maxDimension(half)));
        root.put("refine", "REPLACE");
        if (tiles.size() <= options.maxLeafContents() || depth >= options.maxDepth()) {
            root.put("children", contentLeaves(tiles, path, path.getParent()));
        } else {
            root.put("children", buildChildren(path.getParent(), path, 
                    tiles, center, half, depth, options));
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("asset", Map.of("version", "1.0"));
        doc.put("geometricError", Math.max(1.0, maxDimension(half)));
        doc.put("root", root);
        ObjectMapper om = new ObjectMapper();
        om.enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(path, om.writeValueAsString(doc));
    }

    private static String relativeB3dmUri(Path currentFile, Path batchDir, String fileName) {
        Path from = currentFile.getParent() == null ? batchDir : currentFile.getParent();
        Path target = batchDir.resolve("b3dm").resolve(fileName);
        return from.relativize(target).toString().replace('\\', '/');
    }

    private static List<Map<String, Object>> contentLeaves(List<Tile> tiles, Path currentFile, Path batchDir) {
        List<Map<String, Object>> leaves = new ArrayList<>(tiles.size());
        for (Tile tile : tiles) {
            Map<String, Object> leaf = new LinkedHashMap<>();
            leaf.put("boundingVolume", Map.of("box", boxArray(tile.localBoxCenter(), tile.localBoxHalf())));
            if (tile.lods().isEmpty()) {
                    leaf.put("content", Map.of("uri", relativeTileUri(currentFile, batchDir, tile.uri())));
                leaf.put("geometricError", 0);
            } else {
                Map<String, Object> current = leaf;
                List<LodContent> lods = tile.lods();
                for (int i = 0; i < lods.size(); i++) {
                    LodContent lod = lods.get(i);
                    current.put("content", Map.of("uri", lod.uri()));
                    current.put("geometricError", lod.geometricError());
                    current.put("refine", "REPLACE");
                    if (i + 1 < lods.size()) {
                        Map<String, Object> child = new LinkedHashMap<>();
                        child.put("boundingVolume", Map.of("box", boxArray(tile.localBoxCenter(), tile.localBoxHalf())));
                        current.put("children", List.of(child));
                        current = child;
                    }
                }
            }
            leaves.add(leaf);
        }
        return leaves;
    }

    private static String relativeTileUri(Path currentFile, Path batchDir, String uri) {
        Path from = currentFile.getParent() == null ? batchDir : currentFile.getParent();
        Path target = batchDir.resolve(uri.replace('/', java.io.File.separatorChar));
        return from.relativize(target).toString().replace('\\', '/');
    }

    private static double maxDimension(double[] half) { return Math.max(half[0], Math.max(half[1], half[2])); }

    /** Cesium-compatible ENU to ECEF matrix, column-major. */
    public static double[] eastNorthUpToFixedFrame(double lonDeg, double latDeg, double h) {
        double lon = Math.toRadians(lonDeg), lat = Math.toRadians(latDeg);
        double sinLat = Math.sin(lat), cosLat = Math.cos(lat), sinLon = Math.sin(lon), cosLon = Math.cos(lon);
        double n = WGS84_A / Math.sqrt(1.0 - WGS84_E2 * sinLat * sinLat);
        double ox = (n + h) * cosLat * cosLon, oy = (n + h) * cosLat * sinLon;
        double oz = (n * (1.0 - WGS84_E2) + h) * sinLat;
        double ex = -sinLon, ey = cosLon, ez = 0.0;
        double nx = -sinLat * cosLon, ny = -sinLat * sinLon, nz = cosLat;
        double ux = cosLat * cosLon, uy = cosLat * sinLon, uz = sinLat;
        return new double[]{ex, ey, ez, 0.0, nx, ny, nz, 0.0, ux, uy, uz, 0.0, ox, oy, oz, 1.0};
    }

    private static double[] boxArray(double[] center, double[] half) {
        return new double[]{center[0], center[1], center[2], half[0], 0, 0, 0, half[1], 0, 0, 0, half[2]};
    }
    private static Double[] boxedArray(double[] values) {
        Double[] result = new Double[values.length];
        for (int i = 0; i < values.length; i++) result[i] = values[i];
        return result;
    }
    private static void unionBoxes(List<Tile> tiles, double[] outCenter, double[] outHalf) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (Tile tile : tiles) {
            double[] c = tile.localBoxCenter(), half = tile.localBoxHalf();
            minX = Math.min(minX, c[0] - half[0]); maxX = Math.max(maxX, c[0] + half[0]);
            minY = Math.min(minY, c[1] - half[1]); maxY = Math.max(maxY, c[1] + half[1]);
            minZ = Math.min(minZ, c[2] - half[2]); maxZ = Math.max(maxZ, c[2] + half[2]);
        }
        outCenter[0] = (minX + maxX) / 2; outCenter[1] = (minY + maxY) / 2; outCenter[2] = (minZ + maxZ) / 2;
        outHalf[0] = Math.max(1.0, (maxX - minX) / 2); outHalf[1] = Math.max(1.0, (maxY - minY) / 2); outHalf[2] = Math.max(1.0, (maxZ - minZ) / 2);
    }
}
