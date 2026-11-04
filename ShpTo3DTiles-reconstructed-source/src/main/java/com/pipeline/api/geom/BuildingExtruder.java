package com.pipeline.api.geom;

import com.pipeline.api.dto.BuildingFootprintDTO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateArrays;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.util.GeometryFixer;
import org.locationtech.jts.geom.util.GeometryEditor;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.overlayng.OverlayNG;
import org.locationtech.jts.operation.overlayng.OverlayNGRobust;
import org.locationtech.jts.triangulate.polygon.ConstrainedDelaunayTriangulator;
import org.locationtech.jts.triangulate.polygon.PolygonTriangulator;

public final class BuildingExtruder {
   private static final BufferParameters MITRE = createMitreParams();
   private static final BufferParameters ROUND_INSET = createRoundParams();

   private static double parapetHeightFor(int var0, BuildingExtruder.Profile var1) {
      return Math.min(var1.parapetMaxHeight, var1.parapetMinHeight + Math.max(0, var0 - var1.parapetMinLc) * var1.parapetPerFloor);
   }

   private static BufferParameters createMitreParams() {
      BufferParameters var0 = new BufferParameters();
      var0.setJoinStyle(2);
      var0.setEndCapStyle(2);
      var0.setMitreLimit(2.0);
      return var0;
   }

   private static BufferParameters createRoundParams() {
      BufferParameters var0 = new BufferParameters();
      var0.setJoinStyle(1);
      var0.setEndCapStyle(2);
      var0.setQuadrantSegments(8);
      return var0;
   }

   public static List<BuildingExtruder.Segment> extrude(BuildingFootprintDTO var0) throws Exception {
      return extrude(var0, BuildingExtruder.Profile.defaults());
   }

   public static List<BuildingExtruder.Segment> extrude(BuildingFootprintDTO var0, BuildingExtruder.Profile var1) throws Exception {
      Geometry var2 = normalizeGeometry(new WKTReader().read(var0.getWkt()));
       List<Polygon> var3 = collectPolygons(var2);
      if (var3.isEmpty()) {
         return List.of();
      }

      double var4 = var0.getBaseZ() != null ? var0.getBaseZ() : 0.0;
      double var6 = var0.getJzgd();
      if (Double.isFinite(var6) && !(var6 <= 0.0)) {
         Integer var8 = var0.getFloors();
         int var9 = var8 == null ? 0 : var8;
         boolean var10 = var9 >= var1.parapetMinLc;
         double var11 = var4 + var6;
         double var13 = var10 ? parapetHeightFor(var9, var1) : 0.0;
         long var15 = var0.getBid();
          ArrayList<BuildingExtruder.Segment> var17 = new ArrayList<>();

          for (Polygon var19 : var3) {
            double var21 = var11;
            var17.add(extrudeOne(var19, var4, var11, "wall", "bldg_" + var15 + "_wall"));
             if (var10) {
                try {
                   int var22 = 0;
                   for (Polygon var24 : buildParapetRings(var19, var1.parapetThickness)) {
                      String var25 = "bldg_" + var15 + "_parapet" + (var22 == 0 ? "" : "_part" + var22);
                      var17.add(extrudeOne(var24, var11, var11 + var13, "parapet", var25));
                      var22++;
                   }
                } catch (RuntimeException var26) {
                   GeometryDiagnostics.log(
                      "WARN stage=parapet buildingId=" + var15 + " " + var0.getDiagnosticContext() + " reason=" + var26.getMessage()
                   );
                }
            }

            var17.addAll(buildRooftopShaft(var19, var21, var15, var9, var1));
         }

         return var17;
      } else {
         return List.of();
      }
   }

   private static List<BuildingExtruder.Segment> buildRooftopShaft(Polygon var0, double var1, long var3, int var5, BuildingExtruder.Profile var6) {
      if (var5 < var6.shaftMinLc) {
         return List.of();
      }

      Geometry var7 = BufferOp.bufferOp(var0, -var6.shaftInset, MITRE);
      List var8 = collectPolygons(var7);
      if (var8.isEmpty()) {
         return List.of();
      }

      Random var9 = new Random(shaftSeed(var3, var0));
      BuildingExtruder.ShaftFit var10 = pickShaftFit(var8, var5, var6, var9);
      if (var10 == null) {
         return List.of();
      }

      Polygon var11 = placeShaftRandomCenter(var8, var10, var6.shaftClearance, var6.shaftCenterBoxRatio, var9);
      if (var11 == null) {
         return List.of();
      }

      BuildingExtruder.Segment var12 = extrudeOne(var11, var1, var1 + var10.rise, "shaft", "bldg_" + var3 + "_shaft");
      return List.of(var12);
   }

   private static BuildingExtruder.ShaftFit pickShaftFit(List<Polygon> var0, int var1, BuildingExtruder.Profile var2, Random var3) {
      Polygon var4 = dominantRoofArea(var0);
      if (var4 == null) {
         return null;
      }

      double[] var5 = roofLongShortExtent(var4);
      double var6 = var5[0];
      double var8 = var5[1];
      double var10 = Math.min(var2.shaftMaxLen, var6 * 0.28);
      double var12 = Math.min(var2.shaftMaxWid, var8 * 0.34);
      double var22 = Math.max(2.4, var10);
      double var23 = Math.max(2.0, var12);
      if (!(var6 < var22 + var2.shaftClearance * 2.0) && !(var8 < var23 + var2.shaftClearance * 2.0)) {
         double var14 = randomRange(var3, Math.min(var22, var6 * 0.4), var22);
         double var16 = randomRange(var3, Math.min(var23, var8 * 0.4), var23);
         if (var14 < var16) {
            double var18 = var14;
            var14 = var16;
            var16 = var18;
         }

         double var24 = clamp((var1 - var2.shaftMinLc) / 24.0, 0.0, 1.0);
         double var20 = Math.min(var2.shaftMaxHeight - 0.2, 2.4 + var24 * 2.4 + var3.nextDouble() * 0.4);
         var20 = Math.max(2.0, var20);
         return new BuildingExtruder.ShaftFit(var4, var14, var16, var20);
      } else {
         return null;
      }
   }

   private static Polygon placeShaftRandomCenter(List<Polygon> var0, BuildingExtruder.ShaftFit var1, double var2, double var4, Random var6) {
      Polygon var7 = var1.roofArea;
      GeometryFactory var8 = var7.getFactory();
      Coordinate var9 = var7.getCentroid().getCoordinate();
      if (!var7.covers(var8.createPoint(var9))) {
         var9 = var7.getInteriorPoint().getCoordinate();
      }

      double[] var10 = principalAxis(var7);
      double var11 = var10[0];
      double var13 = var10[1];
      double var15 = -var13;
      double var17 = var11;
      double[] var19 = projectRangeFromOrigin(var7, var9, var11, var13);
      double[] var20 = projectRangeFromOrigin(var7, var9, var15, var17);
      double[] var21 = centerBoxCenterRange(var19, var1.len, var2, var4);
      double[] var22 = centerBoxCenterRange(var20, var1.wid, var2, var4);
      if (var21 != null && var22 != null) {
         for (int var23 = 0; var23 < 60; var23++) {
            double var24 = randomRange(var6, var21[0], var21[1]);
            double var26 = randomRange(var6, var22[0], var22[1]);
            double var28 = var9.x + var24 * var11 + var26 * var15;
            double var30 = var9.y + var24 * var13 + var26 * var17;
            Polygon var32 = orientedRect(var8, var28, var30, var1.len, var1.wid, var11, var13, var15, var17);
            if (var7.contains(var32) && var32.distance(var7.getBoundary()) >= var2) {
               return var32;
            }
         }

         return fitFallbackCenter(var8, var7, var9, var11, var13, var15, var17, var1.len, var1.wid, var2, var4);
      } else {
         return fitFallbackCenter(var8, var7, var9, var11, var13, var15, var17, var1.len, var1.wid, var2, var4);
      }
   }

   private static Polygon fitFallbackCenter(
      GeometryFactory var0,
      Polygon var1,
      Coordinate var2,
      double var3,
      double var5,
      double var7,
      double var9,
      double var11,
      double var13,
      double var15,
      double var17
   ) {
      for (double var19 = 0.95; var19 >= 0.55; var19 -= 0.1) {
         double var21 = Math.max(2.0, var11 * var19);
         double var23 = Math.max(1.8, var13 * var19);
         double[] var25 = projectRangeFromOrigin(var1, var2, var3, var5);
         double[] var26 = projectRangeFromOrigin(var1, var2, var7, var9);
         double[] var27 = centerBoxCenterRange(var25, var21, var15, var17);
         double[] var28 = centerBoxCenterRange(var26, var23, var15, var17);
         if (var27 != null && var28 != null) {
            double var29 = (var27[0] + var27[1]) * 0.5;
            double var31 = (var28[0] + var28[1]) * 0.5;
            double var33 = var2.x + var29 * var3 + var31 * var7;
            double var35 = var2.y + var29 * var5 + var31 * var9;
            Polygon var37 = orientedRect(var0, var33, var35, var21, var23, var3, var5, var7, var9);
            if (var1.contains(var37) && var37.distance(var1.getBoundary()) >= var15) {
               return var37;
            }
         }
      }

      return null;
   }

   private static double[] centerBoxCenterRange(double[] var0, double var1, double var3, double var5) {
      double var7 = clamp(var5, 0.0, 1.0);
      double var9 = var1 * 0.5 + var3;
      double var11 = var0[0] + var9;
      double var13 = var0[1] - var9;
      if (var11 > var13) {
         return null;
      }

      double var15 = (var11 + var13) * 0.5;
      double var17 = (var13 - var11) * var7 * 0.5;
      return new double[]{var15 - var17, var15 + var17};
   }

   private static Polygon dominantRoofArea(List<Polygon> var0) {
      Polygon var1 = null;
      double var2 = -1.0;

      for (Polygon var5 : var0) {
         double var6 = var5.getArea();
         if (var6 > var2) {
            var2 = var6;
            var1 = var5;
         }
      }

      return var1;
   }

   private static double[] roofLongShortExtent(Polygon var0) {
      double[] var1 = principalAxis(var0);
      double var2 = var1[0];
      double var4 = var1[1];
      double var6 = -var4;
      double var8 = var2;
      return new double[]{projectExtent(List.of(var0), var2, var4), projectExtent(List.of(var0), var6, var8)};
   }

   private static Geometry normalizeGeometry(Geometry geometry) {
      Geometry normalized = new GeometryEditor(geometry.getFactory()).edit(geometry, new GeometryEditor.CoordinateOperation() {
         @Override
         public Coordinate[] edit(Coordinate[] coordinates, Geometry component) {
            return CoordinateArrays.removeRepeatedOrInvalidPoints(coordinates);
         }
      });

      if (!normalized.isValid()) {
         normalized = GeometryFixer.fix(normalized);
      }
      if (!normalized.isValid()) {
         normalized = normalized.buffer(0.0);
      }
      return normalized;
   }

   private static List<Polygon> buildParapetRings(Polygon var0, double var1) {
      Geometry var2 = normalizeGeometry(var0);
      ArrayList<Polygon> var3 = new ArrayList<>();

      for (Polygon var5 : collectPolygons(var2)) {
         Geometry var6 = normalizeGeometry(BufferOp.bufferOp(var5, -var1, ROUND_INSET));
         if (var6.isEmpty()) {
            continue;
         }

         Geometry var7 = normalizeGeometry(OverlayNGRobust.overlay(var5, var6, OverlayNG.DIFFERENCE));
         var3.addAll(collectPolygons(var7));
      }

      return var3;
   }

   private static LinearRing toCCW(LinearRing var0) {
      return Orientation.isCCW(var0.getCoordinates()) ? var0 : reverseRing(var0);
   }

   private static LinearRing toCW(LinearRing var0) {
      return Orientation.isCCW(var0.getCoordinates()) ? reverseRing(var0) : var0;
   }

   private static LinearRing reverseRing(LinearRing var0) {
      Coordinate[] var1 = var0.getCoordinates();
      Coordinate[] var2 = new Coordinate[var1.length];

      for (int var3 = 0; var3 < var1.length; var3++) {
         var2[var3] = var1[var1.length - 1 - var3];
      }

      return var0.getFactory().createLinearRing(var2);
   }

   private static List<Polygon> collectPolygons(Geometry var0) {
       ArrayList<Polygon> var1 = new ArrayList<>();
      if (var0 instanceof Polygon var2 && !var2.isEmpty()) {
         var1.add(var2);
      } else if (var0 instanceof MultiPolygon var3) {
         for (int var4 = 0; var4 < var3.getNumGeometries(); var4++) {
            if (var3.getGeometryN(var4) instanceof Polygon var6 && !var6.isEmpty()) {
               var1.add(var6);
            }
         }
      } else if (var0 != null) {
         for (int var7 = 0; var7 < var0.getNumGeometries(); var7++) {
            if (var0.getGeometryN(var7) instanceof Polygon var9 && !var9.isEmpty()) {
               var1.add(var9);
            }
         }
      }

      return var1;
   }

   private static BuildingExtruder.Segment extrudeOne(Polygon var0, double var1, double var3, String var5, String var6) {
      if (!(var3 > var1)) {
         throw new IllegalArgumentException("Prism top must exceed bottom");
      }

      Geometry var7 = var0.isValid() ? var0 : GeometryFixer.fix(var0);
       List<Polygon> var8 = collectPolygons(var7);
      if (var8.isEmpty()) {
         throw new IllegalArgumentException("Geometry repair produced no polygon for " + var6);
      }

      BuildingExtruder.Segment var9 = new BuildingExtruder.Segment(var5, var6);

      for (Polygon var11 : var8) {
         BuildingExtruder.Segment var12 = extrudeValidPolygon(var11, var1, var3, var5, var6);
         int var13 = var9.verts.size();
         int var16 = var9.faces.size();
         var9.verts.addAll(var12.verts);

         for (int[] var15 : var12.faces) {
            var9.faces.add(new int[]{var15[0] + var13, var15[1] + var13, var15[2] + var13});
         }

         for (int var17 : var12.roofFaceIndices) {
            var9.roofFaceIndices.add(var16 + var17);
         }
         for (int var17 : var12.bottomFaceIndices) {
            var9.bottomFaceIndices.add(var16 + var17);
         }
      }

      return var9;
   }

   private static BuildingExtruder.Segment extrudeValidPolygon(Polygon var0, double var1, double var3, String var5, String var6) {
      Polygon var7 = (Polygon)var0.copy();
      if (!Orientation.isCCW(var7.getExteriorRing().getCoordinates())) {
         var7 = var7.reverse();
      }

      BuildingExtruder.Segment var8 = new BuildingExtruder.Segment(var5, var6);
      LinearRing var9 = var7.getExteriorRing();
      Coordinate[] var10 = var9.getCoordinates();
      int var11 = var10.length - 1;
      int[] var12 = new int[var11];
      int[] var13 = new int[var11];

      for (int var14 = 0; var14 < var11; var14++) {
         var8.verts.add(new double[]{var10[var14].x, var10[var14].y, var1});
         var12[var14] = var8.verts.size();
      }

      for (int var34 = 0; var34 < var11; var34++) {
         var8.verts.add(new double[]{var10[var34].x, var10[var34].y, var3});
         var13[var34] = var8.verts.size();
      }

      int var35 = var7.getNumInteriorRing();
      int[][] var15 = new int[var35][];
      int[][] var16 = new int[var35][];

      for (int var17 = 0; var17 < var35; var17++) {
         Coordinate[] var18 = var7.getInteriorRingN(var17).getCoordinates();
         int var19 = var18.length - 1;
         var15[var17] = new int[var19];
         var16[var17] = new int[var19];

         for (int var20 = 0; var20 < var19; var20++) {
            var8.verts.add(new double[]{var18[var20].x, var18[var20].y, var1});
            var15[var17][var20] = var8.verts.size();
         }

         for (int var39 = 0; var39 < var19; var39++) {
            var8.verts.add(new double[]{var18[var39].x, var18[var39].y, var3});
            var16[var17][var39] = var8.verts.size();
         }
      }

      Geometry var36;
      try {
         var36 = ConstrainedDelaunayTriangulator.triangulate(var7);
      } catch (Exception var33) {
         try {
            var36 = PolygonTriangulator.triangulate(var7);
         } catch (Exception var32) {
            var32.addSuppressed(var33);
            throw new IllegalArgumentException(
               "Footprint triangulation failed for " + var6 + " (" + var32.getClass().getSimpleName() + "): " + var32.getMessage(), var32
            );
         }
      }

      HashMap<XYKey, Integer> var37 = new HashMap<>(var11 * 2);
      HashMap<XYKey, Integer> var38 = new HashMap<>(var11 * 2);

      for (int var40 = 0; var40 < var11; var40++) {
         XYKey var21 = XYKey.of(var10[var40].x, var10[var40].y);
         var37.put(var21, var12[var40]);
         var38.put(var21, var13[var40]);
      }

      for (int var41 = 0; var41 < var35; var41++) {
         Coordinate[] var45 = var7.getInteriorRingN(var41).getCoordinates();
         int var22 = var45.length - 1;

         for (int var23 = 0; var23 < var22; var23++) {
            XYKey var24 = XYKey.of(var45[var23].x, var45[var23].y);
            var37.put(var24, var15[var41][var23]);
            var38.put(var24, var16[var41][var23]);
         }
      }

      double capArea = 0.0;
      for (int var42 = 0; var42 < var36.getNumGeometries(); var42++) {
         if (var36.getGeometryN(var42) instanceof Polygon var49) {
            Coordinate[] var52 = var49.getCoordinates();
            if (var52.length >= 4) {
               int var55 = capVertex(var8, var38, var52[0].x, var52[0].y, var3);
               int var25 = capVertex(var8, var38, var52[1].x, var52[1].y, var3);
               int var26 = capVertex(var8, var38, var52[2].x, var52[2].y, var3);
               int var27 = capVertex(var8, var37, var52[0].x, var52[0].y, var1);
               int var28 = capVertex(var8, var37, var52[1].x, var52[1].y, var1);
               int var29 = capVertex(var8, var37, var52[2].x, var52[2].y, var1);
               double var30 = (var52[1].x - var52[0].x) * (var52[2].y - var52[0].y) - (var52[1].y - var52[0].y) * (var52[2].x - var52[0].x);
               capArea += triangleAreaXY(var8.verts.get(var55 - 1), var8.verts.get(var25 - 1), var8.verts.get(var26 - 1));
               if (var30 > 0.0) {
                  var8.roofFaceIndices.add(var8.faces.size());
                  var8.faces.add(new int[]{var55, var25, var26});
                  var8.bottomFaceIndices.add(var8.faces.size());
                  var8.faces.add(new int[]{var27, var29, var28});
               } else {
                  var8.roofFaceIndices.add(var8.faces.size());
                  var8.faces.add(new int[]{var55, var26, var25});
                  var8.bottomFaceIndices.add(var8.faces.size());
                  var8.faces.add(new int[]{var27, var28, var29});
               }
            }
         }
      }

      double footprintArea = var7.getArea();
      double areaTolerance = Math.max(1.0E-8, footprintArea * 1.0E-10);
      if (Math.abs(capArea - footprintArea) > areaTolerance) {
         throw new IllegalArgumentException(
            String.format(Locale.ROOT, "Roof cap coverage mismatch for %s: footprintArea=%.12f capArea=%.12f", var6, footprintArea, capArea)
         );
      }

      for (int var43 = 0; var43 < var11; var43++) {
         int var47 = (var43 + 1) % var11;
         int var50 = var12[var43];
         int var53 = var12[var47];
         int var56 = var13[var43];
         int var58 = var13[var47];
         var8.faces.add(new int[]{var50, var53, var58});
         var8.faces.add(new int[]{var50, var58, var56});
      }

      for (int var44 = 0; var44 < var35; var44++) {
         int var48 = var15[var44].length;

         for (int var51 = 0; var51 < var48; var51++) {
            int var54 = (var51 + 1) % var48;
            int var57 = var15[var44][var51];
            int var59 = var15[var44][var54];
            int var60 = var16[var44][var51];
            int var61 = var16[var44][var54];
            var8.faces.add(new int[]{var57, var61, var59});
            var8.faces.add(new int[]{var57, var60, var61});
         }
      }

      return var8;
   }

   private static double triangleAreaXY(double[] var0, double[] var1, double[] var2) {
      return Math.abs((var1[0] - var0[0]) * (var2[1] - var0[1]) - (var1[1] - var0[1]) * (var2[0] - var0[0])) * 0.5;
   }

   private static int capVertex(BuildingExtruder.Segment var0, HashMap<XYKey, Integer> var1, double var2, double var4, double var6) {
      XYKey var8 = XYKey.of(var2, var4);
      Integer var10 = var1.get(var8);
      if (var10 != null) {
         return var10;
      }

      var0.verts.add(new double[]{var2, var4, var6});
      int var11 = var0.verts.size();
      var1.put(var8, var11);
      return var11;
   }

   private record XYKey(long x, long y) {
      private static XYKey of(double x, double y) {
         return new XYKey(bits(x), bits(y));
      }

      private static long bits(double value) {
         return Double.doubleToLongBits(value == 0.0 ? 0.0 : value);
      }
   }

   private static double projectExtent(List<Polygon> var0, double var1, double var3) {
      double var5 = Double.POSITIVE_INFINITY;
      double var7 = Double.NEGATIVE_INFINITY;

      for (Polygon var10 : var0) {
         for (Coordinate var14 : var10.getCoordinates()) {
            double var15 = var14.x * var1 + var14.y * var3;
            if (var15 < var5) {
               var5 = var15;
            }

            if (var15 > var7) {
               var7 = var15;
            }
         }
      }

      return Double.isFinite(var5) && Double.isFinite(var7) ? var7 - var5 : 0.0;
   }

   private static double[] projectRangeFromOrigin(Polygon var0, Coordinate var1, double var2, double var4) {
      double var6 = Double.POSITIVE_INFINITY;
      double var8 = Double.NEGATIVE_INFINITY;

      for (Coordinate var13 : var0.getCoordinates()) {
         double var14 = (var13.x - var1.x) * var2 + (var13.y - var1.y) * var4;
         if (var14 < var6) {
            var6 = var14;
         }

         if (var14 > var8) {
            var8 = var14;
         }
      }

      return new double[]{var6, var8};
   }

   private static double randomRange(Random var0, double var1, double var3) {
      return var3 <= var1 ? var1 : var1 + var0.nextDouble() * (var3 - var1);
   }

   private static double clamp(double var0, double var2, double var4) {
      if (var0 < var2) {
         return var2;
      } else {
         return var0 > var4 ? var4 : var0;
      }
   }

   private static long shaftSeed(long var0, Polygon var2) {
      Coordinate var3 = var2.getCentroid().getCoordinate();
      long var4 = var0 * -7046029254386353131L;
      var4 ^= Math.round(var3.x * 100.0) * -4658895280553007687L;
      var4 ^= Math.round(var3.y * 100.0) * -7723592293110705685L;
      var4 ^= Math.round(var2.getArea() * 10.0);
      return mix64(var4);
   }

   private static long mix64(long var0) {
      var0 = (var0 ^ var0 >>> 30) * -4658895280553007687L;
      var0 = (var0 ^ var0 >>> 27) * -7723592293110705685L;
      return var0 ^ var0 >>> 31;
   }

   private static double[] principalAxis(Polygon var0) {
      Coordinate[] var1 = var0.getExteriorRing().getCoordinates();
      double var2 = 0.0;
      double var4 = 0.0;

      for (int var6 = 0; var6 + 1 < var1.length; var6++) {
         double var7 = var1[var6 + 1].x - var1[var6].x;
         double var9 = var1[var6 + 1].y - var1[var6].y;
         double var11 = Math.hypot(var7, var9);
         if (!(var11 < 1.0E-9)) {
            double var13 = 4.0 * Math.atan2(var9, var7);
            var2 += var11 * Math.cos(var13);
            var4 += var11 * Math.sin(var13);
         }
      }

      if (var2 == 0.0 && var4 == 0.0) {
         return new double[]{1.0, 0.0};
      }

      double var15 = Math.atan2(var4, var2) / 4.0;
      return new double[]{Math.cos(var15), Math.sin(var15)};
   }

   private static Polygon orientedRect(
      GeometryFactory var0, double var1, double var3, double var5, double var7, double var9, double var11, double var13, double var15
   ) {
      double var17 = var5 * 0.5;
      double var19 = var7 * 0.5;
      Coordinate[] var21 = new Coordinate[]{
         new Coordinate(var1 + var9 * var17 + var13 * var19, var3 + var11 * var17 + var15 * var19),
         new Coordinate(var1 - var9 * var17 + var13 * var19, var3 - var11 * var17 + var15 * var19),
         new Coordinate(var1 - var9 * var17 - var13 * var19, var3 - var11 * var17 - var15 * var19),
         new Coordinate(var1 + var9 * var17 - var13 * var19, var3 + var11 * var17 - var15 * var19),
         null
      };
      var21[4] = var21[0];
      return var0.createPolygon(var21);
   }

   private BuildingExtruder() {
   }

   public static final class Profile {
      public int parapetMinLc = 5;
      public double parapetMinHeight = 0.8;
      public double parapetMaxHeight = 1.4;
      public double parapetPerFloor = 0.04;
      public double parapetThickness = 0.4;
      public int shaftMinLc = 9;
      public double shaftInset = 1.5;
      public double shaftClearance = 2.0;
      public double shaftMaxLen = 4.0;
      public double shaftMaxWid = 3.0;
      public double shaftMaxHeight = 3.0;
      public double shaftCenterBoxRatio = 0.4;

      public static BuildingExtruder.Profile defaults() {
         return new BuildingExtruder.Profile();
      }

      public BuildingExtruder.Profile copy() {
         BuildingExtruder.Profile var1 = new BuildingExtruder.Profile();
         var1.parapetMinLc = this.parapetMinLc;
         var1.parapetMinHeight = this.parapetMinHeight;
         var1.parapetMaxHeight = this.parapetMaxHeight;
         var1.parapetPerFloor = this.parapetPerFloor;
         var1.parapetThickness = this.parapetThickness;
         var1.shaftMinLc = this.shaftMinLc;
         var1.shaftInset = this.shaftInset;
         var1.shaftClearance = this.shaftClearance;
         var1.shaftMaxLen = this.shaftMaxLen;
         var1.shaftMaxWid = this.shaftMaxWid;
         var1.shaftMaxHeight = this.shaftMaxHeight;
         var1.shaftCenterBoxRatio = this.shaftCenterBoxRatio;
         return var1;
      }
   }

   public static final class Segment {
      public final List<double[]> verts = new ArrayList<>();
      public final List<int[]> faces = new ArrayList<>();
      public final Set<Integer> roofFaceIndices = new HashSet<>();
      public final Set<Integer> bottomFaceIndices = new HashSet<>();
      public final String material;
      public final String groupName;

      Segment(String var1, String var2) {
         this.material = var1;
         this.groupName = var2;
      }

      public boolean isRoofFace(int faceIndex) {
         return this.roofFaceIndices.contains(faceIndex);
      }

      public boolean isCapFace(int faceIndex) {
         return this.isRoofFace(faceIndex) || this.bottomFaceIndices.contains(faceIndex);
      }
   }

   private static final class ShaftFit {
      final Polygon roofArea;
      final double len;
      final double wid;
      final double rise;

      ShaftFit(Polygon var1, double var2, double var4, double var6) {
         this.roofArea = var1;
         this.len = var2;
         this.wid = var4;
         this.rise = var6;
      }
   }
}
