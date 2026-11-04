package com.pipeline.api.scene;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TilesetWriter {
   private static final double WGS84_A = 6378137.0;
   private static final double WGS84_E2 = 0.00669437999014;
   private static final double ROOT_GEOM_ERR = 500.0;

   private TilesetWriter() {
   }

   public static void write(Path var0, double var1, double var3, double var5, List<TilesetWriter.Tile> var7) throws IOException {
      write(var0, var1, var3, var5, var7, TilesetWriter.Options.defaults());
   }

   public static void write(Path var0, double var1, double var3, double var5, List<TilesetWriter.Tile> var7, TilesetWriter.Options var8) throws IOException {
      double[] var9 = eastNorthUpToFixedFrame(var1, var3, var5);
      double[] var10 = new double[]{0.0, 0.0, 0.0};
      double[] var11 = new double[]{0.0, 0.0, 0.0};
      if (!var7.isEmpty()) {
         unionBoxes(var7, var10, var11);
      }

      double var12 = var7.isEmpty() ? 500.0 : Math.max(500.0, maxDimension(var11) * var8.errorScale());
      LinkedHashMap var14 = new LinkedHashMap();
      var14.put("asset", Map.of("version", "1.1"));
      var14.put("geometricError", var12);
      LinkedHashMap var15 = new LinkedHashMap();
      var15.put("transform", boxedArray(var9));
      var15.put("boundingVolume", Map.of("box", boxArray(var10, var11)));
      var15.put("geometricError", var12);
      var15.put("refine", "REPLACE");
      if (!var7.isEmpty()) {
         var15.put("children", buildChildren(var0, var0.resolve("tileset.json"), var7, var10, var11, 0, var8));
      }

      var14.put("root", var15);
      ObjectMapper var16 = new ObjectMapper();
      var16.enable(SerializationFeature.INDENT_OUTPUT);
      Files.writeString(var0.resolve("tileset.json"), var16.writeValueAsString(var14));
      System.out
         .printf(
            Locale.ROOT, "[tileset] wrote contents=%d rootError=%.1f maxLeaf=%d maxDepth=%d%n", var7.size(), var12, var8.maxLeafContents(), var8.maxDepth()
         );
   }

   private static List<Map<String, Object>> buildChildren(
      Path var0, Path var1, List<TilesetWriter.Tile> var2, double[] var3, double[] var4, int var5, TilesetWriter.Options var6
   ) throws IOException {
      if (var2.size() > var6.maxLeafContents() && var5 < var6.maxDepth()) {
         double var7 = var3[0];
         double var9 = var3[1];
         List[] var11 = new List[]{new ArrayList(), new ArrayList(), new ArrayList(), new ArrayList()};

         for (TilesetWriter.Tile var13 : var2) {
            double[] var14 = var13.localBoxCenter();
            int var15 = (var14[0] >= var7 ? 1 : 0) | (var14[1] >= var9 ? 2 : 0);
            var11[var15].add(var13);
         }

         int var23 = 0;

         for (List var16 : var11) {
            if (!var16.isEmpty()) {
               var23++;
            }
         }

         if (var23 <= 1) {
            return contentLeaves(var2, var1, var0);
         }

         ArrayList var25 = new ArrayList(var23);

         for (List var17 : var11) {
            if (!var17.isEmpty()) {
               double[] var18 = new double[3];
               double[] var19 = new double[3];
               unionBoxes(var17, var18, var19);
               LinkedHashMap var20 = new LinkedHashMap();
               var20.put("boundingVolume", Map.of("box", boxArray(var18, var19)));
               var20.put("geometricError", Math.max(1.0, maxDimension(var19)));
               var20.put("refine", "REPLACE");
               if (var5 + 1 >= var6.externalizeDepth() && var5 + 1 < var6.maxDepth()) {
                  String var21 = "tileset_" + var5 + "_" + var25.size() + "_" + Math.abs(((TilesetWriter.Tile)var17.get(0)).uri().hashCode()) + ".json";
                  Path var22 = var0.resolve(var21);
                  writeSubtree(var22, var17, var18, var19, var5 + 1, var6);
                  var20.put("content", Map.of("uri", var21));
               } else {
                  var20.put("children", buildChildren(var0, var1, var17, var18, var19, var5 + 1, var6));
               }

               var25.add(var20);
            }
         }

         return var25;
      } else {
         return contentLeaves(var2, var1, var0);
      }
   }

   private static void writeSubtree(Path var0, List<TilesetWriter.Tile> var1, double[] var2, double[] var3, int var4, TilesetWriter.Options var5) throws IOException {
      LinkedHashMap var6 = new LinkedHashMap();
      var6.put("boundingVolume", Map.of("box", boxArray(var2, var3)));
      var6.put("geometricError", Math.max(1.0, maxDimension(var3)));
      var6.put("refine", "REPLACE");
      if (var1.size() > var5.maxLeafContents() && var4 < var5.maxDepth()) {
         var6.put("children", buildChildren(var0.getParent(), var0, var1, var2, var3, var4, var5));
      } else {
         var6.put("children", contentLeaves(var1, var0, var0.getParent()));
      }

      LinkedHashMap var7 = new LinkedHashMap();
      var7.put("asset", Map.of("version", "1.1"));
      var7.put("geometricError", Math.max(1.0, maxDimension(var3)));
      var7.put("root", var6);
      ObjectMapper var8 = new ObjectMapper();
      var8.enable(SerializationFeature.INDENT_OUTPUT);
      Files.writeString(var0, var8.writeValueAsString(var7));
   }

   private static List<Map<String, Object>> contentLeaves(List<TilesetWriter.Tile> var0, Path var1, Path var2) {
       ArrayList<Map<String, Object>> var3 = new ArrayList<>(var0.size());

      for (TilesetWriter.Tile var5 : var0) {
          LinkedHashMap<String, Object> var6 = new LinkedHashMap<>();
         var6.put("boundingVolume", Map.of("box", boxArray(var5.localBoxCenter(), var5.localBoxHalf())));
         if (var5.lods().isEmpty()) {
            var6.put("content", Map.of("uri", relativeTileUri(var1, var2, var5.uri())));
            var6.put("geometricError", 0);
         } else {
            LinkedHashMap<String, Object> var7 = var6;
            List<TilesetWriter.LodContent> var8 = var5.lods();

            for (int var9 = 0; var9 < var8.size(); var9++) {
               TilesetWriter.LodContent var10 = (TilesetWriter.LodContent)var8.get(var9);
               var7.put("content", Map.of("uri", relativeTileUri(var1, var2, var10.uri())));
               var7.put("geometricError", var10.geometricError());
               var7.put("refine", "REPLACE");
               if (var9 + 1 < var8.size()) {
                  LinkedHashMap<String, Object> var11 = new LinkedHashMap<>();
                  var11.put("boundingVolume", Map.of("box", boxArray(var5.localBoxCenter(), var5.localBoxHalf())));
                  var7.put("children", List.of(var11));
                  var7 = var11;
               }
            }
         }

         if (!var5.children().isEmpty()) {
            var6.put("refine", "REPLACE");
            var6.put("children", contentLeaves(var5.children(), var1, var2));
         }

         var3.add(var6);
      }

      return var3;
   }

   private static String relativeTileUri(Path var0, Path var1, String var2) {
      Path var3 = var0.getParent() == null ? var1 : var0.getParent();
      Path var4 = var1.resolve(var2.replace('/', File.separatorChar));
      return var3.relativize(var4).toString().replace('\\', '/');
   }

   private static double maxDimension(double[] var0) {
      return Math.max(var0[0], Math.max(var0[1], var0[2]));
   }

   public static double[] eastNorthUpToFixedFrame(double var0, double var2, double var4) {
      double var6 = Math.toRadians(var0);
      double var8 = Math.toRadians(var2);
      double var10 = Math.sin(var8);
      double var12 = Math.cos(var8);
      double var14 = Math.sin(var6);
      double var16 = Math.cos(var6);
      double var18 = 6378137.0 / Math.sqrt(1.0 - 0.00669437999014 * var10 * var10);
      double var20 = (var18 + var4) * var12 * var16;
      double var22 = (var18 + var4) * var12 * var14;
      double var24 = (var18 * 0.99330562000986 + var4) * var10;
      double var26 = -var14;
      double var28 = var16;
      double var30 = 0.0;
      double var32 = -var10 * var16;
      double var34 = -var10 * var14;
      double var36 = var12;
      double var38 = var12 * var16;
      double var40 = var12 * var14;
      double var42 = var10;
      return new double[]{var26, var28, var30, 0.0, var32, var34, var36, 0.0, var38, var40, var42, 0.0, var20, var22, var24, 1.0};
   }

   private static double[] boxArray(double[] var0, double[] var1) {
      return new double[]{var0[0], var0[1], var0[2], var1[0], 0.0, 0.0, 0.0, var1[1], 0.0, 0.0, 0.0, var1[2]};
   }

   private static Double[] boxedArray(double[] var0) {
      Double[] var1 = new Double[var0.length];

      for (int var2 = 0; var2 < var0.length; var2++) {
         var1[var2] = var0[var2];
      }

      return var1;
   }

   private static void unionBoxes(List<TilesetWriter.Tile> var0, double[] var1, double[] var2) {
      double var3 = Double.POSITIVE_INFINITY;
      double var5 = Double.POSITIVE_INFINITY;
      double var7 = Double.POSITIVE_INFINITY;
      double var9 = Double.NEGATIVE_INFINITY;
      double var11 = Double.NEGATIVE_INFINITY;
      double var13 = Double.NEGATIVE_INFINITY;

      for (TilesetWriter.Tile var16 : var0) {
         double[] var17 = var16.localBoxCenter();
         double[] var18 = var16.localBoxHalf();
         var3 = Math.min(var3, var17[0] - var18[0]);
         var9 = Math.max(var9, var17[0] + var18[0]);
         var5 = Math.min(var5, var17[1] - var18[1]);
         var11 = Math.max(var11, var17[1] + var18[1]);
         var7 = Math.min(var7, var17[2] - var18[2]);
         var13 = Math.max(var13, var17[2] + var18[2]);
      }

      var1[0] = (var3 + var9) / 2.0;
      var1[1] = (var5 + var11) / 2.0;
      var1[2] = (var7 + var13) / 2.0;
      var2[0] = Math.max(1.0, (var9 - var3) / 2.0);
      var2[1] = Math.max(1.0, (var11 - var5) / 2.0);
      var2[2] = Math.max(1.0, (var13 - var7) / 2.0);
   }

   public record LodContent(String uri, double geometricError) {
   }

   public record Options(int maxLeafContents, int maxDepth, double errorScale, int externalizeDepth) {
      public Options(int var1, int var2, double var3) {
         this(var1, var2, var3, 2);
      }

      public Options(int maxLeafContents, int maxDepth, double errorScale, int externalizeDepth) {
         if (maxLeafContents < 1) {
            throw new IllegalArgumentException("maxLeafContents must be > 0");
         }

         if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be > 0");
         }

         if (!(errorScale > 0.0)) {
            throw new IllegalArgumentException("errorScale must be > 0");
         }

         if (externalizeDepth < 1) {
            throw new IllegalArgumentException("externalizeDepth must be > 0");
         }

         this.maxLeafContents = maxLeafContents;
         this.maxDepth = maxDepth;
         this.errorScale = errorScale;
         this.externalizeDepth = externalizeDepth;
      }

      public static TilesetWriter.Options defaults() {
         return new TilesetWriter.Options(64, 12, 1.0);
      }
   }

   public record Tile(String uri, double[] localBoxCenter, double[] localBoxHalf, List<TilesetWriter.LodContent> lods, List<TilesetWriter.Tile> children) {
      public Tile(String var1, double[] var2, double[] var3) {
         this(var1, var2, var3, List.of(), List.of());
      }

      public Tile(String var1, double[] var2, double[] var3, List<TilesetWriter.LodContent> var4) {
         this(var1, var2, var3, var4, List.of());
      }

      public Tile(String uri, double[] localBoxCenter, double[] localBoxHalf, List<TilesetWriter.LodContent> lods, List<TilesetWriter.Tile> children) {
         lods = lods == null ? List.of() : List.copyOf(lods);
         children = children == null ? List.of() : List.copyOf(children);
         this.uri = uri;
         this.localBoxCenter = localBoxCenter;
         this.localBoxHalf = localBoxHalf;
         this.lods = lods;
         this.children = children;
      }
   }
}
