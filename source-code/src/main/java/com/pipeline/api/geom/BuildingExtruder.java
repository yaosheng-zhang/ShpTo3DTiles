package com.pipeline.api.geom;

import com.pipeline.api.dto.BuildingFootprintDTO;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.triangulate.polygon.ConstrainedDelaunayTriangulator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

/**
 * Lightweight realtime building extruder — white model + parapet + rooftop shaft.
 *
 * Z stack, bottom to top (total height == jzgd):
 *
 *   parapet  [zRoofTop .. zMin+jzgd]   RING (outer=footprint, hole=inset)   "parapet"
 *   wall     [zMin     .. zRoofTop]    original footprint                  "wall"
 *
 * + optional rooftop machine room (shaft) sitting on the roof plane.
 *   Only ever one shaft per building, placed at a random point in the central
 *   band of the inward-offset roof bounding box (legacy shape-aware placement
 *   and the multi-shaft count decision have been removed).
 *
 * Removed for light-weight generation:
 *   - base plinth (BASE_OFFSET / BASE_HEIGHT)
 *   - roof slab overhang (ROOF_OFFSET / ROOF_HEIGHT)
 *   - multi-shaft "2-block" placement for long slabs (SHAFT_TWO_BLOCK_*)
 *   - shape-aware placement strategies (SLAB row, TOWER cluster, IRREGULAR symmetry)
 *   - PIPELINE_SHAFT_PLACEMENT environment switch (core-zones is the only mode now)
 *
 * Parapet remains a true RING so the recess is visible and the roof plane
 * is readable. Each segment is independently watertight.
 */
public final class BuildingExtruder {

    /* ============== tunable profile (defaults) ============== */

    /**
     * All parapet / shaft knobs in one immutable bag. CLI / GUI builds one
     * of these from its form values and passes it to {@link #extrude}.
     */
    public static final class Profile {
        // ---- parapet ----
        public int    parapetMinLc      = 5;     // lc >= this ⇒ add parapet
        public double parapetMinHeight  = 0.30;  // metres
        public double parapetMaxHeight  = 1.60;  // metres
        public double parapetPerFloor   = 0.10;  // metres added per floor above min-lc
        public double parapetThickness  = 0.25;  // metres (ring wall thickness)

        // ---- rooftop machine room (single shaft) ----
        public int    shaftMinLc        = 9;     // lc >= this ⇒ add one shaft
        public double shaftInset        = 1.50;  // metres (footprint inset for safe roof area)
        public double shaftClearance    = 2.00;  // metres (shaft to inset-boundary clearance)
        public double shaftMaxLen       = 4.00;  // metres (long axis cap)
        public double shaftMaxWid       = 3.00;  // metres (short axis cap)
        public double shaftMaxHeight    = 3.00;  // metres (rise cap)
        public double shaftCenterBoxRatio = 0.40; // central band width (1.0 = full span, 0.0 = point)

        public static Profile defaults() {
            return new Profile();
        }

        public Profile copy() {
            Profile c = new Profile();
            c.parapetMinLc     = parapetMinLc;
            c.parapetMinHeight = parapetMinHeight;
            c.parapetMaxHeight = parapetMaxHeight;
            c.parapetPerFloor  = parapetPerFloor;
            c.parapetThickness = parapetThickness;
            c.shaftMinLc       = shaftMinLc;
            c.shaftInset       = shaftInset;
            c.shaftClearance   = shaftClearance;
            c.shaftMaxLen      = shaftMaxLen;
            c.shaftMaxWid      = shaftMaxWid;
            c.shaftMaxHeight   = shaftMaxHeight;
            c.shaftCenterBoxRatio = shaftCenterBoxRatio;
            return c;
        }
    }

    /* ============== internal sizing helpers ============== */

    private static double parapetHeightFor(int floors, Profile p) {
        return Math.min(p.parapetMaxHeight,
                p.parapetMinHeight + Math.max(0, floors - p.parapetMinLc) * p.parapetPerFloor);
    }

    /** Mitred buffer — used only for the inward shaft-safe inset. */
    private static final BufferParameters MITRE = createMitreParams();
    private static BufferParameters createMitreParams() {
        BufferParameters bp = new BufferParameters();
        bp.setJoinStyle(BufferParameters.JOIN_MITRE);
        bp.setEndCapStyle(BufferParameters.CAP_FLAT);
        bp.setMitreLimit(2.0);
        return bp;
    }

    /** Round-joined buffer — used for the inward parapet offset only
     *  (uniform wall thickness including corners). */
    private static final BufferParameters ROUND_INSET = createRoundParams();
    private static BufferParameters createRoundParams() {
        BufferParameters bp = new BufferParameters();
        bp.setJoinStyle(BufferParameters.JOIN_ROUND);
        bp.setEndCapStyle(BufferParameters.CAP_FLAT);
        bp.setQuadrantSegments(8);
        return bp;
    }

    public static final class Segment {
        public final List<double[]> verts = new ArrayList<>();
        public final List<int[]>    faces = new ArrayList<>();
        public final String material;
        public final String groupName;
        Segment(String material, String groupName) {
            this.material = material;
            this.groupName = groupName;
        }
    }

    /**
     * Convenience overload using the default profile. Keeps the old call sites
     * (SceneService, tests, harness) working without source edits.
     */
    public static List<Segment> extrude(BuildingFootprintDTO row) throws Exception {
        return extrude(row, Profile.defaults());
    }

    public static List<Segment> extrude(BuildingFootprintDTO row, Profile profile) throws Exception {
        Geometry g = new WKTReader().read(row.getWkt());
        List<Polygon> footprints = collectPolygons(g);
        if (footprints.isEmpty()) return List.of();

        double zMin = row.getBaseZ() != null ? row.getBaseZ() : 0.0;
        double jzgd = row.getJzgd();
        if (!Double.isFinite(jzgd) || jzgd <= 0) return List.of();

        Integer floorsBox = row.getFloors();
        int lc = (floorsBox == null) ? 0 : floorsBox;
        boolean hasParapet = lc >= profile.parapetMinLc;

        double paraHeight = hasParapet ? parapetHeightFor(lc, profile) : 0.0;
        double zParTop  = zMin + jzgd;
        double zRoofTop = zParTop - paraHeight;

        // Degenerate safeguard: keep a thin body when the building is very short
        if (zRoofTop <= zMin + 0.05) {
            zRoofTop = zMin + Math.max(0.05, jzgd * 0.7);
            zParTop  = zMin + jzgd;
        }

        long bid = row.getBid();
        List<Segment> out = new ArrayList<>();

        for (Polygon p : footprints) {
            // Main white-model body (original footprint, no offsets)
            out.add(extrudeOne(p, zMin, zRoofTop, "wall", "bldg_" + bid + "_wall"));

            // Parapet RING
            if (hasParapet) {
                Polygon paraRing = buildParapetRing(p, profile.parapetThickness);
                if (paraRing != null) {
                    out.add(extrudeOne(paraRing, zRoofTop, zParTop, "parapet",
                            "bldg_" + bid + "_parapet"));
                }
            }

            // Rooftop machine room (LC >= shaftMinLc) — always exactly one.
            out.addAll(buildRooftopShaft(p, zRoofTop, bid, lc, profile));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Shaft generation — single shaft, random point in the central band of
    // the inward-offset roof bounding box. Shape classification removed.
    // ------------------------------------------------------------------

    /** Shaft physical parameters + the roof it lives on (for placement math). */
    private static final class ShaftFit {
        final Polygon roofArea;
        final double len, wid, rise;
        ShaftFit(Polygon roofArea, double len, double wid, double rise) {
            this.roofArea = roofArea; this.len = len; this.wid = wid; this.rise = rise;
        }
    }

    /**
     * Build at most one rooftop shaft. Returns an empty list if the building is
     * below the gate, the inward-offset roof has no usable area, or no
     * acceptable random placement can be found (very tight roof).
     */
    private static List<Segment> buildRooftopShaft(Polygon footprint, double zRoofTop,
                                                   long bid, int lc, Profile profile) {
        if (lc < profile.shaftMinLc) return List.of();

        Geometry innerG = BufferOp.bufferOp(footprint, -profile.shaftInset, MITRE);
        List<Polygon> roofAreas = collectPolygons(innerG);
        if (roofAreas.isEmpty()) return List.of();

        Random rng = new Random(shaftSeed(bid, footprint));
        ShaftFit fit = pickShaftFit(roofAreas, lc, profile, rng);
        if (fit == null) return List.of();

        Polygon body = placeShaftRandomCenter(roofAreas, fit, profile.shaftClearance,
                profile.shaftCenterBoxRatio, rng);
        if (body == null) return List.of();

        Segment seg = extrudeOne(body, zRoofTop, zRoofTop + fit.rise, "shaft",
                "bldg_" + bid + "_shaft");
        return List.of(seg);
    }

    /**
     * Pick one shaft's dimensions: capped by the dominant roof's long/short
     * extent and the profile caps, with a deterministic random share of that
     * range. No more shape classification — TOWER / SLAB / IRREGULAR all use
     * the same envelope.
     */
    private static ShaftFit pickShaftFit(List<Polygon> roofAreas, int lc, Profile profile,
                                         Random rng) {
        Polygon dominant = dominantRoofArea(roofAreas);
        if (dominant == null) return null;

        double[] longShort = roofLongShortExtent(dominant);
        double longExtent  = longShort[0];
        double shortExtent = longShort[1];

        // Cap by both footprint-derived extents and the hard caps.
        double maxLen = Math.min(profile.shaftMaxLen, longExtent  * 0.28);
        double maxWid = Math.min(profile.shaftMaxWid, shortExtent * 0.34);
        // A small absolute floor so tiny footprints still get a non-trivial shaft.
        maxLen = Math.max(2.4, maxLen);
        maxWid = Math.max(2.0, maxWid);

        // Skip if there isn't enough room at all.
        if (longExtent < maxLen + profile.shaftClearance * 2
                || shortExtent < maxWid + profile.shaftClearance * 2) {
            return null;
        }

        double len = randomRange(rng, Math.min(maxLen, longExtent * 0.40), maxLen);
        double wid = randomRange(rng, Math.min(maxWid, shortExtent * 0.40), maxWid);
        // Keep long ≥ short for a readable shape.
        if (len < wid) { double t = len; len = wid; wid = t; }

        // Rise: 2.4 m at lc=shaftMinLc, linear to ~4.8 m at lc=shaftMinLc+24.
        double growth = clamp((lc - profile.shaftMinLc) / 24.0, 0.0, 1.0);
        double rise   = Math.min(profile.shaftMaxHeight - 0.20,
                                  2.40 + growth * 2.40 + rng.nextDouble() * 0.40);
        rise = Math.max(2.0, rise);

        return new ShaftFit(dominant, len, wid, rise);
    }

    /**
     * Sample one point inside the central band of the dominant roof and print a
     * watertight oriented rectangle. The rectangle is always aligned to the
     * roof's principal axes (we pick the principal axis by edge-length
     * weighting — same approach as before, just no shape branching).
     */
    private static Polygon placeShaftRandomCenter(List<Polygon> roofAreas, ShaftFit fit,
                                                   double clearance, double centerBoxRatio,
                                                   Random rng) {
        Polygon roofArea = fit.roofArea;
        GeometryFactory gf = roofArea.getFactory();

        Coordinate origin = roofArea.getCentroid().getCoordinate();
        if (!roofArea.covers(gf.createPoint(origin))) {
            origin = roofArea.getInteriorPoint().getCoordinate();
        }
        double[] axis = principalAxis(roofArea);
        double longX  = axis[0], longY = axis[1];
        double shortX = -longY,    shortY = longX;

        double[] longRange  = projectRangeFromOrigin(roofArea, origin, longX,  longY);
        double[] shortRange = projectRangeFromOrigin(roofArea, origin, shortX, shortY);

        double[] longCenter  = centerBoxCenterRange(longRange,  fit.len, clearance, centerBoxRatio);
        double[] shortCenter = centerBoxCenterRange(shortRange, fit.wid, clearance, centerBoxRatio);

        if (longCenter == null || shortCenter == null) {
            return fitFallbackCenter(gf, roofArea, origin, longX, longY, shortX, shortY,
                    fit.len, fit.wid, clearance, centerBoxRatio);
        }

        // Try the random target first; fall back to a deterministic centre fit
        // when the random sample can't be placed (very tight / concave roof).
        for (int attempt = 0; attempt < 60; attempt++) {
            double lo = randomRange(rng, longCenter[0],  longCenter[1]);
            double so = randomRange(rng, shortCenter[0], shortCenter[1]);
            double cx = origin.x + lo * longX + so * shortX;
            double cy = origin.y + lo * longY + so * shortY;
            Polygon body = orientedRect(gf, cx, cy, fit.len, fit.wid,
                    longX, longY, shortX, shortY);
            if (roofArea.contains(body)
                    && body.distance(roofArea.getBoundary()) >= clearance) {
                return body;
            }
        }
        return fitFallbackCenter(gf, roofArea, origin, longX, longY, shortX, shortY,
                fit.len, fit.wid, clearance, centerBoxRatio);
    }

    /** Try shrinking the box from 95% to 55% of full size, placing at the centre. */
    private static Polygon fitFallbackCenter(GeometryFactory gf, Polygon roofArea, Coordinate origin,
                                             double longX, double longY,
                                             double shortX, double shortY,
                                             double len, double wid, double clearance,
                                             double centerBoxRatio) {
        for (double scale = 0.95; scale >= 0.55; scale -= 0.10) {
            double tryLen = Math.max(2.0, len * scale);
            double tryWid = Math.max(1.8, wid * scale);
            double[] longRange  = projectRangeFromOrigin(roofArea, origin, longX,  longY);
            double[] shortRange = projectRangeFromOrigin(roofArea, origin, shortX, shortY);
            double[] longCenter  = centerBoxCenterRange(longRange,  tryLen, clearance, centerBoxRatio);
            double[] shortCenter = centerBoxCenterRange(shortRange, tryWid, clearance, centerBoxRatio);
            if (longCenter == null || shortCenter == null) continue;

            double lo = (longCenter[0]  + longCenter[1])  * 0.5;
            double so = (shortCenter[0] + shortCenter[1]) * 0.5;
            double cx = origin.x + lo * longX + so * shortX;
            double cy = origin.y + lo * longY + so * shortY;
            Polygon body = orientedRect(gf, cx, cy, tryLen, tryWid,
                    longX, longY, shortX, shortY);
            if (roofArea.contains(body)
                    && body.distance(roofArea.getBoundary()) >= clearance) {
                return body;
            }
        }
        return null;
    }

    /** Centre {@code centerBoxRatio} of [min, max] minus half-size and clearance on both sides. */
    private static double[] centerBoxCenterRange(double[] range, double size, double clearance,
                                                 double centerBoxRatio) {
        double ratio = clamp(centerBoxRatio, 0.0, 1.0);
        double span = range[1] - range[0];
        double centralSpan = span * ratio;
        double centralMin = (range[0] + range[1] - centralSpan) * 0.5;
        double centralMax = (range[0] + range[1] + centralSpan) * 0.5;
        double halfWithClearance = size * 0.5 + clearance;
        double min = centralMin + halfWithClearance;
        double max = centralMax - halfWithClearance;
        if (min > max) return null;
        return new double[]{min, max};
    }

    private static Polygon dominantRoofArea(List<Polygon> roofAreas) {
        Polygon best = null;
        double bestArea = -1.0;
        for (Polygon p : roofAreas) {
            double a = p.getArea();
            if (a > bestArea) { bestArea = a; best = p; }
        }
        return best;
    }

    /** Dominant roof's long / short extent in the (axis, perp) frame. */
    private static double[] roofLongShortExtent(Polygon p) {
        double[] axis = principalAxis(p);
        double lx = axis[0], ly = axis[1];
        double sx = -ly,   sy = lx;
        return new double[]{
                projectExtent(List.of(p), lx, ly),
                projectExtent(List.of(p), sx, sy)
        };
    }

    // ------------------------------------------------------------------
    // Geometry helpers
    // ------------------------------------------------------------------

    private static Polygon buildParapetRing(Polygon outer, double wallThickness) {
        Geometry innerG = BufferOp.bufferOp(outer, -wallThickness, ROUND_INSET);
        List<Polygon> inners = collectPolygons(innerG);
        if (inners.isEmpty()) return null;

        GeometryFactory gf = outer.getFactory();
        LinearRing outerRing = toCCW(outer.getExteriorRing());
        List<LinearRing> holes = new ArrayList<>(inners.size());
        for (Polygon inner : inners) {
            holes.add(toCW(inner.getExteriorRing()));
        }
        return gf.createPolygon(outerRing, holes.toArray(new LinearRing[0]));
    }

    private static LinearRing toCCW(LinearRing r) {
        return Orientation.isCCW(r.getCoordinates()) ? r : reverseRing(r);
    }

    private static LinearRing toCW(LinearRing r) {
        return Orientation.isCCW(r.getCoordinates()) ? reverseRing(r) : r;
    }

    private static LinearRing reverseRing(LinearRing r) {
        Coordinate[] cs = r.getCoordinates();
        Coordinate[] rev = new Coordinate[cs.length];
        for (int i = 0; i < cs.length; i++) rev[i] = cs[cs.length - 1 - i];
        return r.getFactory().createLinearRing(rev);
    }

    private static List<Polygon> collectPolygons(Geometry g) {
        List<Polygon> out = new ArrayList<>();
        if (g instanceof Polygon p && !p.isEmpty()) {
            out.add(p);
        } else if (g instanceof MultiPolygon mp) {
            for (int i = 0; i < mp.getNumGeometries(); i++) {
                Geometry sub = mp.getGeometryN(i);
                if (sub instanceof Polygon pp && !pp.isEmpty()) out.add(pp);
            }
        } else if (g != null) {
            for (int i = 0; i < g.getNumGeometries(); i++) {
                Geometry sub = g.getGeometryN(i);
                if (sub instanceof Polygon pp && !pp.isEmpty()) out.add(pp);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Watertight prism extrusion
    // ------------------------------------------------------------------

    private static Segment extrudeOne(Polygon p, double zBot, double zTop,
                                      String mat, String group) {
        if (zTop - zBot < 0.01) zTop = zBot + 0.01;

        Polygon pp = (Polygon) p.copy();
        if (!Orientation.isCCW(pp.getExteriorRing().getCoordinates())) {
            pp = (Polygon) pp.reverse();
        }

        Segment seg = new Segment(mat, group);

        LinearRing outer = pp.getExteriorRing();
        Coordinate[] outerC = outer.getCoordinates();
        int nOuter = outerC.length - 1;

        int[] outerBot = new int[nOuter];
        int[] outerTop = new int[nOuter];
        for (int i = 0; i < nOuter; i++) {
            seg.verts.add(new double[]{outerC[i].x, outerC[i].y, zBot});
            outerBot[i] = seg.verts.size();
        }
        for (int i = 0; i < nOuter; i++) {
            seg.verts.add(new double[]{outerC[i].x, outerC[i].y, zTop});
            outerTop[i] = seg.verts.size();
        }

        int nHoles = pp.getNumInteriorRing();
        int[][] holeBot = new int[nHoles][];
        int[][] holeTop = new int[nHoles][];
        for (int h = 0; h < nHoles; h++) {
            Coordinate[] hc = pp.getInteriorRingN(h).getCoordinates();
            int nh = hc.length - 1;
            holeBot[h] = new int[nh];
            holeTop[h] = new int[nh];
            for (int i = 0; i < nh; i++) {
                seg.verts.add(new double[]{hc[i].x, hc[i].y, zBot});
                holeBot[h][i] = seg.verts.size();
            }
            for (int i = 0; i < nh; i++) {
                seg.verts.add(new double[]{hc[i].x, hc[i].y, zTop});
                holeTop[h][i] = seg.verts.size();
            }
        }

        Geometry trisGeom;
        try {
            trisGeom = ConstrainedDelaunayTriangulator.triangulate(pp);
        } catch (Exception ex) {
            trisGeom = pp.getEnvelope();
        }

        HashMap<Long, Integer> botByXY = new HashMap<>(nOuter * 2);
        HashMap<Long, Integer> topByXY = new HashMap<>(nOuter * 2);
        for (int i = 0; i < nOuter; i++) {
            long k = packXY(outerC[i].x, outerC[i].y);
            botByXY.put(k, outerBot[i]);
            topByXY.put(k, outerTop[i]);
        }
        for (int h = 0; h < nHoles; h++) {
            Coordinate[] hc = pp.getInteriorRingN(h).getCoordinates();
            int nh = hc.length - 1;
            for (int i = 0; i < nh; i++) {
                long k = packXY(hc[i].x, hc[i].y);
                botByXY.put(k, holeBot[h][i]);
                topByXY.put(k, holeTop[h][i]);
            }
        }

        for (int t = 0; t < trisGeom.getNumGeometries(); t++) {
            Geometry tg = trisGeom.getGeometryN(t);
            if (!(tg instanceof Polygon tri)) continue;
            Coordinate[] tc = tri.getCoordinates();
            if (tc.length < 4) continue;

            Integer at = topByXY.get(packXY(tc[0].x, tc[0].y));
            Integer bt = topByXY.get(packXY(tc[1].x, tc[1].y));
            Integer ct = topByXY.get(packXY(tc[2].x, tc[2].y));
            Integer ab = botByXY.get(packXY(tc[0].x, tc[0].y));
            Integer bb = botByXY.get(packXY(tc[1].x, tc[1].y));
            Integer cb = botByXY.get(packXY(tc[2].x, tc[2].y));
            if (at == null || bt == null || ct == null
                    || ab == null || bb == null || cb == null) {
                continue;
            }
            double cross = (tc[1].x - tc[0].x) * (tc[2].y - tc[0].y)
                    - (tc[1].y - tc[0].y) * (tc[2].x - tc[0].x);
            if (cross > 0) {
                seg.faces.add(new int[]{at, bt, ct});
                seg.faces.add(new int[]{ab, cb, bb});
            } else {
                seg.faces.add(new int[]{at, ct, bt});
                seg.faces.add(new int[]{ab, bb, cb});
            }
        }

        for (int i = 0; i < nOuter; i++) {
            int i1 = (i + 1) % nOuter;
            int bi = outerBot[i], bi1 = outerBot[i1];
            int ti = outerTop[i], ti1 = outerTop[i1];
            seg.faces.add(new int[]{bi, bi1, ti1});
            seg.faces.add(new int[]{bi, ti1, ti});
        }

        for (int h = 0; h < nHoles; h++) {
            int nh = holeBot[h].length;
            for (int i = 0; i < nh; i++) {
                int i1 = (i + 1) % nh;
                int bi = holeBot[h][i], bi1 = holeBot[h][i1];
                int ti = holeTop[h][i], ti1 = holeTop[h][i1];
                seg.faces.add(new int[]{bi, ti1, bi1});
                seg.faces.add(new int[]{bi, ti, ti1});
            }
        }
        return seg;
    }

    private static long packXY(double x, double y) {
        long xi = Math.round(x * 1000.0);
        long yi = Math.round(y * 1000.0);
        return (xi << 32) ^ (yi & 0xFFFFFFFFL);
    }

    private static double projectExtent(List<Polygon> polys, double ux, double uy) {
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (Polygon p : polys) {
            for (Coordinate c : p.getCoordinates()) {
                double d = c.x * ux + c.y * uy;
                if (d < lo) lo = d;
                if (d > hi) hi = d;
            }
        }
        if (!Double.isFinite(lo) || !Double.isFinite(hi)) return 0.0;
        return hi - lo;
    }

    private static double[] projectRangeFromOrigin(Polygon p, Coordinate origin,
                                                   double ux, double uy) {
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (Coordinate c : p.getCoordinates()) {
            double d = (c.x - origin.x) * ux + (c.y - origin.y) * uy;
            if (d < lo) lo = d;
            if (d > hi) hi = d;
        }
        return new double[]{lo, hi};
    }

    private static double randomRange(Random rng, double min, double max) {
        if (max <= min) return min;
        return min + rng.nextDouble() * (max - min);
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static long shaftSeed(long bid, Polygon footprint) {
        Coordinate c = footprint.getCentroid().getCoordinate();
        long seed = bid * 0x9E3779B97F4A7C15L;
        seed ^= Math.round(c.x * 100.0) * 0xBF58476D1CE4E5B9L;
        seed ^= Math.round(c.y * 100.0) * 0x94D049BB133111EBL;
        seed ^= Math.round(footprint.getArea() * 10.0);
        return mix64(seed);
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double[] principalAxis(Polygon footprint) {
        Coordinate[] cs = footprint.getExteriorRing().getCoordinates();
        double sumCos = 0, sumSin = 0;
        for (int i = 0; i + 1 < cs.length; i++) {
            double dx = cs[i + 1].x - cs[i].x;
            double dy = cs[i + 1].y - cs[i].y;
            double len = Math.hypot(dx, dy);
            if (len < 1e-9) continue;
            double a4 = 4.0 * Math.atan2(dy, dx);
            sumCos += len * Math.cos(a4);
            sumSin += len * Math.sin(a4);
        }
        if (sumCos == 0 && sumSin == 0) return new double[]{1.0, 0.0};
        double theta = Math.atan2(sumSin, sumCos) / 4.0;
        return new double[]{Math.cos(theta), Math.sin(theta)};
    }

    private static Polygon orientedRect(GeometryFactory gf,
                                        double cx, double cy,
                                        double lenLong, double lenShort,
                                        double lx, double ly,
                                        double sx, double sy) {
        double hl = lenLong * 0.5, hs = lenShort * 0.5;
        Coordinate[] c = new Coordinate[5];
        c[0] = new Coordinate(cx + lx * hl + sx * hs, cy + ly * hl + sy * hs);
        c[1] = new Coordinate(cx - lx * hl + sx * hs, cy - ly * hl + sy * hs);
        c[2] = new Coordinate(cx - lx * hl - sx * hs, cy - ly * hl - sy * hs);
        c[3] = new Coordinate(cx + lx * hl - sx * hs, cy + ly * hl - sy * hs);
        c[4] = c[0];
        return gf.createPolygon(c);
    }

    private BuildingExtruder() {}
}