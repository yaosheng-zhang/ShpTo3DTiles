package com.pipeline.api.geom;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class WhiteModelObjWriter {
   public static final String MTL_NAME = "white.mtl";

   public static String mtlContent() {
      return "# pipeline_v2 white-model materials\n# 4-segment palette: warm-white wall, gray roof, dark plinth, dim parapet\n\nnewmtl wall\nKa 0.20 0.20 0.18\nKd 0.92 0.90 0.85\nKs 0.05 0.05 0.05\nNs 16\nillum 2\n\nnewmtl roof\nKa 0.15 0.15 0.15\nKd 0.70 0.70 0.70\nKs 0.10 0.10 0.10\nNs 8\nillum 2\n\nnewmtl base\nKa 0.10 0.10 0.10\nKd 0.55 0.55 0.55\nKs 0.05 0.05 0.05\nNs 4\nillum 2\n\nnewmtl parapet\nKa 0.18 0.18 0.15\nKd 0.78 0.75 0.70\nKs 0.05 0.05 0.05\nNs 8\nillum 2\n\nnewmtl shaft\nKa 0.18 0.18 0.18\nKd 0.82 0.80 0.76\nKs 0.06 0.06 0.06\nNs 12\nillum 2\n";
   }

   public static void writeObj(Path var0, long var1, List<BuildingExtruder.Segment> var3) throws IOException {
      try (BufferedWriter var4 = Files.newBufferedWriter(var0, StandardCharsets.UTF_8)) {
         int var5 = var3.stream().mapToInt(var0x -> var0x.verts.size()).sum();
         int var6 = var3.stream().mapToInt(var0x -> var0x.faces.size()).sum();
         var4.write("# Building white-model bldg_" + var1 + "\n");
         var4.write("# realtime 4-segment watertight extrusion\n");
         var4.write("# vertices=" + var5 + "  faces=" + var6 + "\n");
         var4.write("mtllib white.mtl\n");
         int var7 = 0;

         for (BuildingExtruder.Segment var9 : var3) {
            if (!var9.verts.isEmpty() && !var9.faces.isEmpty()) {
               var4.write("o " + var9.groupName + "\n");

               for (double[] var11 : var9.verts) {
                  var4.write(String.format(Locale.ROOT, "v %.4f %.4f %.4f%n", var11[0], var11[1], var11[2]));
               }

               var4.write("s off\n");
               if ("wall".equals(var9.material)) {
                  var4.write("usemtl wall\n");
                  writeFaces(var4, var9, var7, false);
                  var4.write("usemtl roof\n");
                  writeFaces(var4, var9, var7, true);
               } else {
                  var4.write("usemtl " + var9.material + "\n");
                  writeFaces(var4, var9, var7, null);
               }

               var7 += var9.verts.size();
            }
         }
      }
   }

   private static void writeFaces(BufferedWriter var0, BuildingExtruder.Segment var1, int var2, Boolean var3) throws IOException {
      for (int[] var5 : var1.faces) {
         boolean var6 = isHorizontal(var1, var5);
         if (var3 == null || var6 == var3) {
            var0.write(String.format(Locale.ROOT, "f %d %d %d%n", var5[0] + var2, var5[1] + var2, var5[2] + var2));
         }
      }
   }

   private static boolean isHorizontal(BuildingExtruder.Segment var0, int[] var1) {
      double var2 = var0.verts.get(var1[0] - 1)[2];
      double var4 = var0.verts.get(var1[1] - 1)[2];
      double var6 = var0.verts.get(var1[2] - 1)[2];
      return Math.abs(var2 - var4) < 1.0E-7 && Math.abs(var2 - var6) < 1.0E-7;
   }

   private WhiteModelObjWriter() {
   }
}
