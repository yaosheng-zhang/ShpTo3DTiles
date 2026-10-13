package com.pipeline.api.geom;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Writes a multi-segment building OBJ alongside a shared white.mtl material.
 * Uses `usemtl` + `o` to delimit segments so the four-material palette
 * (wall / roof / base / parapet) renders correctly in any OBJ-aware viewer.
 */
public final class WhiteModelObjWriter {

    public static final String MTL_NAME = "white.mtl";

    public static String mtlContent() {
        return ""
            + "# pipeline_v2 white-model materials\n"
            + "# 4-segment palette: warm-white wall, gray roof, dark plinth, dim parapet\n"
            + "\n"
            + "newmtl wall\n"
            + "Ka 0.20 0.20 0.18\n"
            + "Kd 0.92 0.90 0.85\n"
            + "Ks 0.05 0.05 0.05\n"
            + "Ns 16\n"
            + "illum 2\n"
            + "\n"
            + "newmtl roof\n"
            + "Ka 0.15 0.15 0.15\n"
            + "Kd 0.70 0.70 0.70\n"
            + "Ks 0.10 0.10 0.10\n"
            + "Ns 8\n"
            + "illum 2\n"
            + "\n"
            + "newmtl base\n"
            + "Ka 0.10 0.10 0.10\n"
            + "Kd 0.55 0.55 0.55\n"
            + "Ks 0.05 0.05 0.05\n"
            + "Ns 4\n"
            + "illum 2\n"
            + "\n"
            + "newmtl parapet\n"
            + "Ka 0.18 0.18 0.15\n"
            + "Kd 0.78 0.75 0.70\n"
            + "Ks 0.05 0.05 0.05\n"
            + "Ns 8\n"
            + "illum 2\n"
            + "\n"
            + "newmtl shaft\n"
            + "Ka 0.18 0.18 0.18\n"
            + "Kd 0.82 0.80 0.76\n"
            + "Ks 0.06 0.06 0.06\n"
            + "Ns 12\n"
            + "illum 2\n";
    }

    public static void writeObj(Path out, long bid, List<BuildingExtruder.Segment> segs) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            int totalVerts = segs.stream().mapToInt(s -> s.verts.size()).sum();
            int totalFaces = segs.stream().mapToInt(s -> s.faces.size()).sum();

            bw.write("# Building white-model bldg_" + bid + "\n");
            bw.write("# realtime 4-segment watertight extrusion\n");
            bw.write("# vertices=" + totalVerts + "  faces=" + totalFaces + "\n");
            bw.write("mtllib " + MTL_NAME + "\n");

            int vOffset = 0;
            for (BuildingExtruder.Segment seg : segs) {
                if (seg.verts.isEmpty() || seg.faces.isEmpty()) continue;
                bw.write("o " + seg.groupName + "\n");
                for (double[] v : seg.verts) {
                    bw.write(String.format(Locale.ROOT, "v %.4f %.4f %.4f%n", v[0], v[1], v[2]));
                }
                bw.write("usemtl " + seg.material + "\n");
                bw.write("s off\n");
                for (int[] f : seg.faces) {
                    bw.write(String.format(Locale.ROOT, "f %d %d %d%n",
                            f[0] + vOffset, f[1] + vOffset, f[2] + vOffset));
                }
                vOffset += seg.verts.size();
            }
        }
    }

    private WhiteModelObjWriter() {}
}
