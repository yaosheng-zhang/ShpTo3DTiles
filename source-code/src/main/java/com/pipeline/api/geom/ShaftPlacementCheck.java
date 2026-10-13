package com.pipeline.api.geom;

import com.pipeline.api.dto.BuildingFootprintDTO;

import java.util.ArrayList;
import java.util.List;

/**
 * TEMPORARY verification harness for rooftop-shaft placement — NOT production
 * code. Checks the LC>=9 gate, max count=2, usage-independent count, legal
 * size caps (len≤8, wid≤6, height≤6), and symmetric / even / collinear rows
 * for regular roofs. Delete after verifying. Run:
 *   mvn -q -DskipTests compile
 *   mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *       -Dexec.mainClass=com.pipeline.api.geom.ShaftPlacementCheck
 */
public final class ShaftPlacementCheck {

    public static void main(String[] args) throws Exception {
        // below LC gate -> 0
        check("8F compact tower", "POLYGON((0 0, 28 0, 28 28, 0 28, 0 0))",
                "办公", 8, Double.NaN, Double.NaN, 0, false);
        // small compact tower -> 1
        check("tower 24x24 office", "POLYGON((0 0, 24 0, 24 24, 0 24, 0 0))",
                "办公", 18, 12.0, 12.0, 1, false);
        // usage-independent: same regular slab gets the same count as residential
        check("slab 50x14 industrial", "POLYGON((0 0, 50 0, 50 14, 0 14, 0 0))",
                "工业", 18, 25.0, 7.0, 2, true);
        // long slab -> 2, even row centered on the roof axis
        check("slab 90x12 residential", "POLYGON((0 0, 90 0, 90 12, 0 12, 0 0))",
                "住宅", 18, 45.0, 6.0, 2, true);
        // large compact plate -> one block because area alone no longer adds cores
        check("plate 120x90 office", "POLYGON((0 0, 120 0, 120 90, 0 90, 0 0))",
                "办公", 18, Double.NaN, Double.NaN, 1, false);
        // irregular L-shaped roof -> one scored block in a usable pocket
        check("L plan 70x50 notch", "POLYGON((0 0, 70 0, 70 20, 35 20, 35 50, 0 50, 0 0))",
                "商业", 18, Double.NaN, Double.NaN, 1, false);
    }

    private static void check(String label, String wkt, String usage, int floors,
                              double expCx, double expCy, int expCount, boolean expectRow) throws Exception {
        BuildingFootprintDTO dto = new BuildingFootprintDTO();
        dto.setBid(1L);
        dto.setJzgd(floors * 3.0);
        dto.setBaseZ(0.0);
        dto.setBldUsage(usage);
        dto.setFloors(floors);
        dto.setWkt(wkt);

        List<BuildingExtruder.Segment> segs = BuildingExtruder.extrude(dto);

        List<double[]> mains = new ArrayList<>();   // {cx, cy, len, wid, height}
        for (BuildingExtruder.Segment s : segs) {
            if (!"shaft".equals(s.material)) continue;
            double mnx = 1e18, mny = 1e18, mnz = 1e18, mxx = -1e18, mxy = -1e18, mxz = -1e18;
            for (double[] v : s.verts) {
                mnx = Math.min(mnx, v[0]); mxx = Math.max(mxx, v[0]);
                mny = Math.min(mny, v[1]); mxy = Math.max(mxy, v[1]);
                mnz = Math.min(mnz, v[2]); mxz = Math.max(mxz, v[2]);
            }
            double dx = mxx - mnx, dy = mxy - mny;
            double[] dims = orientedFootprintDims(s, mnz);
            double len = dims == null ? Math.max(dx, dy) : dims[0];
            double wid = dims == null ? Math.min(dx, dy) : dims[1];
            mains.add(new double[]{(mnx + mxx) / 2, (mny + mxy) / 2,
                    len, wid, mxz - mnz});
        }
        mains.sort((a, b) -> Double.compare(a[0], b[0]));

        System.out.println("=== " + label + " ===");
        System.out.printf("  count: %d (expected %d) %s%n", mains.size(), expCount,
                mains.size() == expCount ? "PASS" : "FAIL");

        boolean dimsOk = true;
        for (double[] m : mains) {
            boolean ok = m[2] <= 8.0 + 1e-6 && m[3] <= 6.0 + 1e-6 && m[4] <= 6.0 + 1e-6;
            dimsOk &= ok;
            System.out.printf("    box center=(%.2f,%.2f) len=%.2f wid=%.2f height=%.2f %s%n",
                    m[0], m[1], m[2], m[3], m[4], ok ? "" : "<-- OVER CAP");
        }
        System.out.printf("  size caps (len≤8,wid≤6,h≤6): %s%n", dimsOk ? "PASS" : "FAIL");

        if (expectRow && mains.size() >= 2) {
            int n = mains.size();
            double maxDy = 0, yRef = mains.get(0)[1];
            for (int i = 0; i < n; i++) {
                maxDy = Math.max(maxDy, Math.abs(mains.get(i)[1] - yRef));
            }
            double rowMid = (mains.get(0)[0] + mains.get(n - 1)[0]) * 0.5;
            double midShift = Math.abs(rowMid - expCx);
            System.out.printf("  row midpoint near x=%.1f: %s (shift=%.4f)%n",
                    expCx, midShift <= 5.0 ? "PASS" : "FAIL", midShift);
            System.out.printf("  collinear in y: %s (max dy=%.4f)%n",
                    maxDy < 0.05 ? "PASS" : "FAIL", maxDy);
            if (n >= 3) {
                double g1 = mains.get(1)[0] - mains.get(0)[0];
                double g2 = mains.get(2)[0] - mains.get(1)[0];
                System.out.printf("  even spacing: %s (gaps %.2f / %.2f)%n",
                        Math.abs(g1 - g2) < 0.05 ? "PASS" : "FAIL", g1, g2);
            }
        }
        System.out.println();
    }

    private static double[] orientedFootprintDims(BuildingExtruder.Segment s, double zBot) {
        List<double[]> bottom = new ArrayList<>();
        for (double[] v : s.verts) {
            if (Math.abs(v[2] - zBot) > 1e-6) continue;
            boolean seen = false;
            for (double[] b : bottom) {
                if (Math.hypot(v[0] - b[0], v[1] - b[1]) < 1e-6) {
                    seen = true;
                    break;
                }
            }
            if (!seen) bottom.add(v);
        }
        if (bottom.size() != 4) return null;

        double longSide = 0.0;
        double shortSide = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            double[] a = bottom.get(i);
            double[] b = bottom.get((i + 1) % 4);
            double len = Math.hypot(a[0] - b[0], a[1] - b[1]);
            longSide = Math.max(longSide, len);
            shortSide = Math.min(shortSide, len);
        }
        return new double[]{longSide, shortSide};
    }

    private ShaftPlacementCheck() {}
}
