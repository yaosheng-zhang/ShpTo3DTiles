package com.pipeline.api.gltf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ObjToGlb {
   private ObjToGlb() {
   }

   public static void convert(Path var0, Path var1) throws IOException {
      convert(var0, var1, new ObjToGlb.Options());
   }

   public static void convert(Path var0, Path var1, ObjToGlb.Options var2) throws IOException {
      if (var2 == null) {
         var2 = new ObjToGlb.Options();
      }

      Mesh var3 = ObjReader.read(var0);
      Map var4 = Map.of();
      Path var5 = var2.mtlOverride;
      if (var5 == null && var3.mtllib != null && !var3.mtllib.isBlank()) {
         Path var6 = var0.toAbsolutePath().getParent().resolve(var3.mtllib);
         if (Files.exists(var6)) {
            var5 = var6;
         }
      }

      if (var5 != null && Files.exists(var5)) {
         var4 = MtlReader.read(var5);
      }

      if (var2.inputAxis != var2.outputAxis) {
         applyAxisRotation(var3.positions, var2.inputAxis, var2.outputAxis);
         applyAxisRotation(var3.normals, var2.inputAxis, var2.outputAxis);
      }

      GlbWriter.Options var7 = new GlbWriter.Options();
      var7.defaultBatchId = var2.defaultBatchId;
      var7.unlit = var2.unlit;
      var7.unlitMaterials = var2.unlitMaterials;
      var7.generateNormalsIfMissing = var2.generateNormalsIfMissing;
      var7.forceFlat = var2.forceFlat;
      var7.indexVertices = var2.indexVertices;
      var7.doubleSided = var2.doubleSided;
      var7.outlineEdges = var2.outlineEdges;
      var7.outlineAngleDeg = var2.outlineAngleDeg;
      var7.generator = var2.generator;
      var7.baseColorTextureJpeg = var2.baseColorTextureJpeg;
      var7.baseColorTextureUri = var2.baseColorTextureUri;
      var7.textureUriByMaterial = var2.textureUriByMaterial;
      var7.generateUv = var2.generateUv;
      var7.uvUpMin = var2.uvUpMin;
      var7.uvFloorHeight = var2.uvFloorHeight;
      var7.uvTileWidth = var2.uvTileWidth;
      var7.uvScaleU = var2.uvScaleU;
      var7.uvScaleV = var2.uvScaleV;
      var7.uvOffsetU = var2.uvOffsetU;
      var7.uvOffsetV = var2.uvOffsetV;
      var7.uvFractional = var2.uvFractional;
      var7.uvFaceLocal = var2.uvFaceLocal;
      var7.texturedMaterials = var2.texturedMaterials;
      var7.atlasColumns = var2.atlasColumns;
      var7.atlasRows = var2.atlasRows;
      var7.atlasWidth = var2.atlasWidth;
      var7.atlasHeight = var2.atlasHeight;
      var7.atlasGutter = var2.atlasGutter;
      var7.featureProperties = var2.featureProperties != null && !var2.featureProperties.isEmpty() ? var2.featureProperties : var3.featureProperties;
      var7.uvUpAxisIndex = axisIndex(var2.outputAxis);
      GlbWriter.write(var3, var4, var1, var7);
   }

   public static byte[] convert(Mesh var0, ObjToGlb.Options var1) throws IOException {
      if (var0 == null) {
         throw new IllegalArgumentException("mesh must not be null");
      }

      if (var1 == null) {
         var1 = new ObjToGlb.Options();
      }

      if (var1.inputAxis != var1.outputAxis) {
         applyAxisRotation(var0.positions, var1.inputAxis, var1.outputAxis);
         applyAxisRotation(var0.normals, var1.inputAxis, var1.outputAxis);
      }

      GlbWriter.Options var2 = new GlbWriter.Options();
      var2.defaultBatchId = var1.defaultBatchId;
      var2.unlit = var1.unlit;
      var2.unlitMaterials = var1.unlitMaterials;
      var2.generateNormalsIfMissing = var1.generateNormalsIfMissing;
      var2.forceFlat = var1.forceFlat;
      var2.indexVertices = var1.indexVertices;
      var2.doubleSided = var1.doubleSided;
      var2.outlineEdges = var1.outlineEdges;
      var2.outlineAngleDeg = var1.outlineAngleDeg;
      var2.generator = var1.generator;
      var2.baseColorTextureJpeg = var1.baseColorTextureJpeg;
      var2.baseColorTextureUri = var1.baseColorTextureUri;
      var2.textureUriByMaterial = var1.textureUriByMaterial;
      var2.generateUv = var1.generateUv;
      var2.uvUpMin = var1.uvUpMin;
      var2.uvFloorHeight = var1.uvFloorHeight;
      var2.uvTileWidth = var1.uvTileWidth;
      var2.uvScaleU = var1.uvScaleU;
      var2.uvScaleV = var1.uvScaleV;
      var2.uvOffsetU = var1.uvOffsetU;
      var2.uvOffsetV = var1.uvOffsetV;
      var2.uvFractional = var1.uvFractional;
      var2.uvFaceLocal = var1.uvFaceLocal;
      var2.texturedMaterials = var1.texturedMaterials;
      var2.atlasColumns = var1.atlasColumns;
      var2.atlasRows = var1.atlasRows;
      var2.atlasWidth = var1.atlasWidth;
      var2.atlasHeight = var1.atlasHeight;
      var2.atlasGutter = var1.atlasGutter;
      var2.featureProperties = var1.featureProperties != null && !var1.featureProperties.isEmpty() ? var1.featureProperties : var0.featureProperties;
      var2.uvUpAxisIndex = axisIndex(var1.outputAxis);
      return GlbWriter.toBytes(var0, Map.of(), var2);
   }

   private static int axisIndex(ObjToGlb.Axis var0) {
      return switch (var0) {
         case X -> 0;
         case Y -> 1;
         case Z -> 2;
      };
   }

   private static void applyAxisRotation(List<float[]> var0, ObjToGlb.Axis var1, ObjToGlb.Axis var2) {
      if (var1 != var2) {
         for (float[] var4 : var0) {
            float var5 = var4[0];
            float var6 = var4[1];
            float var7 = var4[2];
            switch (var1) {
               case X:
                  switch (var2) {
                     case Y:
                        var4[0] = -var6;
                        var4[1] = var5;
                        var4[2] = var7;
                        continue;
                     case Z:
                        var4[0] = -var7;
                        var4[1] = var6;
                        var4[2] = var5;
                     default:
                        continue;
                  }
               case Y:
                  switch (var2) {
                     case X:
                        var4[0] = var6;
                        var4[1] = -var5;
                        var4[2] = var7;
                        continue;
                     case Z:
                        var4[0] = var5;
                        var4[1] = -var7;
                        var4[2] = var6;
                     default:
                        continue;
                  }
               case Z:
                  switch (var2) {
                     case X:
                        var4[0] = var7;
                        var4[1] = var6;
                        var4[2] = -var5;
                        break;
                     case Y:
                        var4[0] = var5;
                        var4[1] = var7;
                        var4[2] = -var6;
                  }
            }
         }
      }
   }

   public enum Axis {
      X,
      Y,
      Z;
   }

   public static final class Options {
      public int defaultBatchId = -1;
      public ObjToGlb.Axis inputAxis = ObjToGlb.Axis.Z;
      public ObjToGlb.Axis outputAxis = ObjToGlb.Axis.Y;
      public boolean unlit = false;
      public Set<String> unlitMaterials = null;
      public boolean generateNormalsIfMissing = true;
      public boolean forceFlat = false;
      public boolean indexVertices = false;
      public boolean doubleSided = true;
      public boolean outlineEdges = false;
      public double outlineAngleDeg = 30.0;
      public String generator = "pipeline_v2 JavaGlb";
      public Path mtlOverride;
      public byte[] baseColorTextureJpeg;
      public String baseColorTextureUri;
      public Map<String, String> textureUriByMaterial;
      public boolean generateUv = false;
      public double uvUpMin = 0.0;
      public double uvFloorHeight = 3.0;
      public double uvTileWidth = 4.0;
      public double uvScaleU = 1.0;
      public double uvScaleV = 1.0;
      public double uvOffsetU = 0.0;
      public double uvOffsetV = 0.0;
      public boolean uvFractional = false;
      public boolean uvFaceLocal = false;
      public Set<String> texturedMaterials = null;
      public int atlasColumns = 0;
      public int atlasRows = 0;
      public int atlasWidth = 0;
      public int atlasHeight = 0;
      public int atlasGutter = 0;
      public List<Map<String, Object>> featureProperties = List.of();
   }
}
