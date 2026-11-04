package com.pipeline.api.geom;

import com.pipeline.api.dto.BuildingFootprintDTO;
import java.util.ArrayList;
import java.util.List;

public final class ShaftPlacementCheck {
   public static void main(String[] var0) throws Exception {
      check("8F compact tower", "POLYGON((0 0, 28 0, 28 28, 0 28, 0 0))", "办公", 8, Double.NaN, Double.NaN, 0, false);
      check("tower 24x24 office", "POLYGON((0 0, 24 0, 24 24, 0 24, 0 0))", "办公", 18, 12.0, 12.0, 1, false);
      check("slab 50x14 industrial", "POLYGON((0 0, 50 0, 50 14, 0 14, 0 0))", "工业", 18, 25.0, 7.0, 2, true);
      check("slab 90x12 residential", "POLYGON((0 0, 90 0, 90 12, 0 12, 0 0))", "住宅", 18, 45.0, 6.0, 2, true);
      check("plate 120x90 office", "POLYGON((0 0, 120 0, 120 90, 0 90, 0 0))", "办公", 18, Double.NaN, Double.NaN, 1, false);
      check("L plan 70x50 notch", "POLYGON((0 0, 70 0, 70 20, 35 20, 35 50, 0 50, 0 0))", "商业", 18, Double.NaN, Double.NaN, 1, false);
   }

   private static void check(String var0, String var1, String var2, int var3, double var4, double var6, int var8, boolean var9) throws Exception {
      BuildingFootprintDTO var10 = new BuildingFootprintDTO();
      var10.setBid(1L);
      var10.setJzgd(var3 * 3.0);
      var10.setBaseZ(0.0);
      var10.setBldUsage(var2);
      var10.setFloors(var3);
      var10.setWkt(var1);
       List<BuildingExtruder.Segment> var11 = BuildingExtruder.extrude(var10);
       ArrayList<double[]> var12 = new ArrayList<>();

      for (BuildingExtruder.Segment var14 : var11) {
         if ("shaft".equals(var14.material)) {
            double var15 = 1.0E18;
            double var17 = 1.0E18;
            double var19 = 1.0E18;
            double var21 = -1.0E18;
            double var23 = -1.0E18;
            double var25 = -1.0E18;

            for (double[] var28 : var14.verts) {
               var15 = Math.min(var15, var28[0]);
               var21 = Math.max(var21, var28[0]);
               var17 = Math.min(var17, var28[1]);
               var23 = Math.max(var23, var28[1]);
               var19 = Math.min(var19, var28[2]);
               var25 = Math.max(var25, var28[2]);
            }

            double var47 = var21 - var15;
            double var29 = var23 - var17;
            double[] var31 = orientedFootprintDims(var14, var19);
            double var32 = var31 == null ? Math.max(var47, var29) : var31[0];
            double var34 = var31 == null ? Math.min(var47, var29) : var31[1];
            var12.add(new double[]{(var15 + var21) / 2.0, (var17 + var23) / 2.0, var32, var34, var25 - var19});
         }
      }

      var12.sort((var0x, var1x) -> Double.compare(var0x[0], var1x[0]));
      System.out.println("=== " + var0 + " ===");
      System.out.printf("  count: %d (expected %d) %s%n", var12.size(), var8, var12.size() == var8 ? "PASS" : "FAIL");
      boolean var36 = true;

      for (double[] var39 : var12) {
         boolean var16 = var39[2] <= 8.000001 && var39[3] <= 6.000001 && var39[4] <= 6.000001;
         var36 &= var16;
         System.out
            .printf(
               "    box center=(%.2f,%.2f) len=%.2f wid=%.2f height=%.2f %s%n", var39[0], var39[1], var39[2], var39[3], var39[4], var16 ? "" : "<-- OVER CAP"
            );
      }

      System.out.printf("  size caps (len≤8,wid≤6,h≤6): %s%n", var36 ? "PASS" : "FAIL");
      if (var9 && var12.size() >= 2) {
         int var38 = var12.size();
         double var40 = 0.0;
         double var41 = ((double[])var12.get(0))[1];

         for (int var42 = 0; var42 < var38; var42++) {
            var40 = Math.max(var40, Math.abs(((double[])var12.get(var42))[1] - var41));
         }

         double var43 = (((double[])var12.get(0))[0] + ((double[])var12.get(var38 - 1))[0]) * 0.5;
         double var44 = Math.abs(var43 - var4);
         System.out.printf("  row midpoint near x=%.1f: %s (shift=%.4f)%n", var4, var44 <= 5.0 ? "PASS" : "FAIL", var44);
         System.out.printf("  collinear in y: %s (max dy=%.4f)%n", var40 < 0.05 ? "PASS" : "FAIL", var40);
         if (var38 >= 3) {
            double var45 = ((double[])var12.get(1))[0] - ((double[])var12.get(0))[0];
            double var46 = ((double[])var12.get(2))[0] - ((double[])var12.get(1))[0];
            System.out.printf("  even spacing: %s (gaps %.2f / %.2f)%n", Math.abs(var45 - var46) < 0.05 ? "PASS" : "FAIL", var45, var46);
         }
      }

      System.out.println();
   }

   private static double[] orientedFootprintDims(BuildingExtruder.Segment var0, double var1) {
       ArrayList<double[]> var3 = new ArrayList<>();

      for (double[] var5 : var0.verts) {
         if (!(Math.abs(var5[2] - var1) > 1.0E-6)) {
            boolean var6 = false;

            for (double[] var8 : var3) {
               if (Math.hypot(var5[0] - var8[0], var5[1] - var8[1]) < 1.0E-6) {
                  var6 = true;
                  break;
               }
            }

            if (!var6) {
               var3.add(var5);
            }
         }
      }

      if (var3.size() != 4) {
         return null;
      }

      double var13 = 0.0;
      double var14 = Double.POSITIVE_INFINITY;

      for (int var15 = 0; var15 < 4; var15++) {
         double[] var9 = (double[])var3.get(var15);
         double[] var10 = (double[])var3.get((var15 + 1) % 4);
         double var11 = Math.hypot(var9[0] - var10[0], var9[1] - var10[1]);
         var13 = Math.max(var13, var11);
         var14 = Math.min(var14, var11);
      }

      return new double[]{var13, var14};
   }

   private ShaftPlacementCheck() {
   }
}
