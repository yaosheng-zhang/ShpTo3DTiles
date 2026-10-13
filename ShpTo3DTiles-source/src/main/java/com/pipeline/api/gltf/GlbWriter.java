package com.pipeline.api.gltf;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

public final class GlbWriter {
   private static final int GLB_MAGIC = 1179937895;
   private static final int GLB_VERSION = 2;
   private static final int CHUNK_TYPE_JSON = 1313821514;
   private static final int CHUNK_TYPE_BIN = 5130562;
   private static final int CT_F32 = 5126;
   private static final int CT_U32 = 5125;
   private static final int CT_U16 = 5123;
   private static final int TARGET_ARRAY_BUFFER = 34962;
   private static final int TARGET_ELEMENT_ARRAY_BUFFER = 34963;

   private GlbWriter() {
   }

   public static void write(Mesh var0, Map<String, Material> var1, Path var2, GlbWriter.Options var3) throws IOException {
      try (OutputStream var4 = Files.newOutputStream(var2)) {
         writeTo(var0, var1, var4, var3);
      }
   }

   public static byte[] toBytes(Mesh var0, Map<String, Material> var1, GlbWriter.Options var2) throws IOException {
      ByteArrayOutputStream var3 = new ByteArrayOutputStream();
      writeTo(var0, var1, var3, var2);
      return var3.toByteArray();
   }

   private static void writeTo(Mesh var0, Map<String, Material> var1, OutputStream var2, GlbWriter.Options var3) throws IOException {
      if (var3 == null) {
         var3 = new GlbWriter.Options();
      }

      ArrayList<GlbWriter.PrimBlob> var4 = new ArrayList<>(var0.groups.size());
      ArrayList var5 = new ArrayList(var0.groups.size());

      for (Entry var7 : var0.groups.entrySet()) {
         String var8 = (String)var7.getKey();
         List var9 = (List)var7.getValue();
         if (!var9.isEmpty()) {
            var4.add(var3.indexVertices && !var3.outlineEdges && !var3.uvFaceLocal ? buildIndexedBlob(var0, var9, var3) : buildBlob(var0, var9, var3));
            var5.add(var8);
         }
      }

      if (var4.isEmpty()) {
         writeEmpty(var2, var3);
      } else {
         int var22 = 0;

         for (GlbWriter.PrimBlob var30 : var4) {
            var30.binOffsetPositions = var22;
            var22 += var30.positions.length * 4;
            var30.binOffsetNormals = var22;
            var22 += var30.normals.length * 4;
            if (var30.uvs != null) {
               var30.binOffsetUv = var22;
               var22 += var30.uvs.length * 4;
            }

            var30.binOffsetIndices = var22;
            var22 += var30.indices.length * var30.indexByteWidth;
            if (var30.outlineIndices != null && var30.outlineIndices.length > 0) {
               var22 = align(var22, 4);
               var30.binOffsetOutline = var22;
               var22 += var30.outlineIndices.length * 4;
            }

            var22 = align(var22, 4);
         }

         int var29 = -1;
         byte[] var31 = var3.baseColorTextureJpeg;
         boolean var32 = var31 != null && var31.length > 0;
         if (var32) {
            var22 = align(var22, 4);
            var29 = var22;
            var22 += var31.length;
         }

         ByteBuffer var10 = ByteBuffer.allocate(var22).order(ByteOrder.LITTLE_ENDIAN);

         for (GlbWriter.PrimBlob var12 : var4) {
            var10.position(var12.binOffsetPositions);

            for (float var16 : var12.positions) {
               var10.putFloat(var16);
            }

            var10.position(var12.binOffsetNormals);

            for (float var53 : var12.normals) {
               var10.putFloat(var53);
            }

            if (var12.uvs != null) {
               var10.position(var12.binOffsetUv);

               for (float var54 : var12.uvs) {
                  var10.putFloat(var54);
               }
            }

            var10.position(var12.binOffsetIndices);
            if (var12.indexByteWidth == 2) {
               for (int var56 : var12.indices) {
                  var10.putShort((short)(var56 & 65535));
               }
            } else {
               for (int var55 : var12.indices) {
                  var10.putInt(var55);
               }
            }

            if (var12.outlineIndices != null && var12.outlineIndices.length > 0) {
               var10.position(var12.binOffsetOutline);

               for (int var57 : var12.outlineIndices) {
                  var10.putInt(var57);
               }
            }
         }

         if (var32) {
            var10.position(var29);
            var10.put(var31);
         }

         byte[] var33 = var10.array();
         Map var34 = buildGltf(var4, var5, var1, var33.length, var29, var3);
         ObjectMapper var40 = new ObjectMapper();
         byte[] var46 = var40.writeValueAsBytes(var34);
         int var52 = 4 - (var46.length & 3) & 3;
         int var58 = 4 - (var33.length & 3) & 3;
         int var17 = 20 + var46.length + var52 + 8 + var33.length + var58;
         ByteBuffer var18 = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
         var18.putInt(1179937895).putInt(2).putInt(var17);
         var2.write(var18.array());
         ByteBuffer var19 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
         var19.putInt(var46.length + var52).putInt(1313821514);
         var2.write(var19.array());
         var2.write(var46);

         for (int var20 = 0; var20 < var52; var20++) {
            var2.write(32);
         }

         ByteBuffer var59 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
         var59.putInt(var33.length + var58).putInt(5130562);
         var2.write(var59.array());
         var2.write(var33);

         for (int var21 = 0; var21 < var58; var21++) {
            var2.write(0);
         }
      }
   }

   private static GlbWriter.PrimBlob buildIndexedBlob(Mesh var0, List<Mesh.Tri> var1, GlbWriter.Options var2) {
      int var3 = Math.max(3, var1.size() * 3);
      GlbWriter.PrimBlob var4 = new GlbWriter.PrimBlob();
      var4.positions = new float[var3 * 3];
      var4.normals = new float[var3 * 3];
      var4.indices = new int[var3];
      if (var2.generateUv) {
         var4.uvs = new float[var3 * 2];
      }

      HashMap var5 = new HashMap(var3 * 2);
      int var6 = 0;
      int var7 = 0;

      for (Mesh.Tri var9 : var1) {
         float[] var10 = safeGet(var0.positions, var9.v0);
         float[] var11 = safeGet(var0.positions, var9.v1);
         float[] var12 = safeGet(var0.positions, var9.v2);
         if (var10 != null && var11 != null && var12 != null) {
            float[] var13 = faceNormal(var10, var11, var12);
            float[][] var14 = new float[][]{var10, var11, var12};
            int[] var15 = new int[]{var9.n0, var9.n1, var9.n2};
            int[] var16 = new int[3];

            for (int var17 = 0; var17 < 3; var17++) {
               int var19 = var15[var17];
               float[] var18;
               if (!var2.forceFlat && var19 >= 0 && var19 < var0.normals.size()) {
                  var18 = normalize(var0.normals.get(var19));
               } else if (!var2.forceFlat && !var2.generateNormalsIfMissing) {
                  var18 = new float[]{0.0F, 0.0F, 1.0F};
               } else {
                  var18 = var13;
               }

               float[] var20 = var14[var17];
               String var21 = vertexKey(var20, var18);
               Integer var22 = (Integer)var5.get(var21);
               if (var22 == null) {
                  if (var6 == var3) {
                     var3 *= 2;
                     var4.positions = Arrays.copyOf(var4.positions, var3 * 3);
                     var4.normals = Arrays.copyOf(var4.normals, var3 * 3);
                     var4.indices = Arrays.copyOf(var4.indices, var3 * 3);
                     if (var4.uvs != null) {
                        var4.uvs = Arrays.copyOf(var4.uvs, var3 * 2);
                     }
                  }

                  var22 = var6;
                  var5.put(var21, var22);
                  writeVertex(var4, var6, var20, var18);
                  if (var4.uvs != null) {
                     fillUv(var4, var6, var20, var2);
                  }

                  var6++;
               }

               var16[var17] = var22;
            }

            for (int var26 : var16) {
               var4.indices[var7++] = var26;
            }
         }
      }

      var4.positions = trim(var4.positions, var6 * 3);
      var4.normals = trim(var4.normals, var6 * 3);
      var4.indices = trim(var4.indices, var7);
      if (var4.uvs != null) {
         var4.uvs = trim(var4.uvs, var6 * 2);
      }

      var4.vertexCount = var6;
      var4.indexByteWidth = var6 <= 65535 ? 2 : 4;
      return var4;
   }

   private static String vertexKey(float[] var0, float[] var1) {
      return Float.floatToIntBits(var0[0])
         + ":"
         + Float.floatToIntBits(var0[1])
         + ":"
         + Float.floatToIntBits(var0[2])
         + ":"
         + Float.floatToIntBits(var1[0])
         + ":"
         + Float.floatToIntBits(var1[1])
         + ":"
         + Float.floatToIntBits(var1[2]);
   }

   private static GlbWriter.PrimBlob buildBlob(Mesh var0, List<Mesh.Tri> var1, GlbWriter.Options var2) {
      int var3 = var1.size();
      int var4 = var3 * 3;
      GlbWriter.PrimBlob var5 = new GlbWriter.PrimBlob();
      var5.positions = new float[var4 * 3];
      var5.normals = new float[var4 * 3];
      var5.indices = new int[var4];
      var5.vertexCount = var4;
      if (var2.generateUv) {
         var5.uvs = new float[var4 * 2];
      }

      boolean var6 = var2.outlineEdges;
      double var7 = !(var2.outlineAngleDeg <= 0.0) && !(var2.outlineAngleDeg >= 180.0) ? var2.outlineAngleDeg : 30.0;
      double var9 = Math.cos(Math.toRadians(var7));
      HashMap var11 = var6 ? new HashMap(var4 * 2) : null;
      HashMap var12 = var6 ? new HashMap(var4 * 3) : null;
      int var13 = 0;

      for (Mesh.Tri var15 : var1) {
         float[] var16 = safeGet(var0.positions, var15.v0);
         float[] var17 = safeGet(var0.positions, var15.v1);
         float[] var18 = safeGet(var0.positions, var15.v2);
         if (var16 != null && var17 != null && var18 != null) {
            float[] var19 = faceNormal(var16, var17, var18);
            float[] var20;
            float[] var21;
            float[] var22;
            if (!var2.forceFlat
               && var15.n0 >= 0
               && var15.n1 >= 0
               && var15.n2 >= 0
               && var15.n0 < var0.normals.size()
               && var15.n1 < var0.normals.size()
               && var15.n2 < var0.normals.size()) {
               var20 = var0.normals.get(var15.n0);
               var21 = var0.normals.get(var15.n1);
               var22 = var0.normals.get(var15.n2);
            } else if (!var2.forceFlat && !var2.generateNormalsIfMissing) {
               var20 = var21 = var22 = new float[]{0.0F, 0.0F, 1.0F};
            } else {
               var22 = var19;
               var21 = var19;
               var20 = var19;
            }

            var20 = normalize(var20);
            var21 = normalize(var21);
            var22 = normalize(var22);
            int var23 = var13;
            int var24 = var13 + 1;
            int var25 = var13 + 2;
            writeVertex(var5, var13++, var16, var20);
            writeVertex(var5, var13++, var17, var21);
            writeVertex(var5, var13++, var18, var22);
            if (var5.uvs != null) {
               if (var2.uvFaceLocal) {
                  fillFaceUvs(var5, var23, var16, var24, var17, var25, var18, var19, var2);
               } else {
                  fillUv(var5, var23, var16, var2);
                  fillUv(var5, var24, var17, var2);
                  fillUv(var5, var25, var18, var2);
               }
            }

            if (var6) {
               int var26 = spatialIdOf(var11, var16);
               int var27 = spatialIdOf(var11, var17);
               int var28 = spatialIdOf(var11, var18);
               accumEdge(var12, var26, var27, var23, var24, var19);
               accumEdge(var12, var27, var28, var24, var25, var19);
               accumEdge(var12, var28, var26, var25, var23, var19);
            }
         }
      }

      if (var13 != var4) {
         var5.positions = trim(var5.positions, var13 * 3);
         var5.normals = trim(var5.normals, var13 * 3);
         var5.indices = trim(var5.indices, var13);
         if (var5.uvs != null) {
            var5.uvs = trim(var5.uvs, var13 * 2);
         }

         var5.vertexCount = var13;
      }

      var5.indexByteWidth = var5.vertexCount <= 65535 ? 2 : 4;
      int var31 = 0;

      while (var31 < var5.vertexCount) {
         var5.indices[var31] = var31++;
      }

      if (var6) {
         var5.outlineIndices = collectOutline(var12, var9);
      }

      return var5;
   }

   private static float[] normalize(float[] var0) {
      float var1 = (float)Math.sqrt(var0[0] * var0[0] + var0[1] * var0[1] + var0[2] * var0[2]);
      return var1 == 0.0F ? new float[]{0.0F, 0.0F, 1.0F} : new float[]{var0[0] / var1, var0[1] / var1, var0[2] / var1};
   }

   private static void fillUv(GlbWriter.PrimBlob var0, int var1, float[] var2, GlbWriter.Options var3) {
      int var4 = var3.uvUpAxisIndex;
      float var5 = (float)((var2[var4] - var3.uvUpMin) / var3.uvFloorHeight);
      float var6 = 0.0F;

      for (int var7 = 0; var7 < 3; var7++) {
         if (var7 != var4) {
            var6 += var2[var7];
         }
      }

      var6 = (float)(var6 / var3.uvTileWidth);
      if (var3.uvFractional) {
         var6 = (float)(var6 - Math.floor(var6));
         var5 = (float)(var5 - Math.floor(var5));
      }

      var0.uvs[var1 * 2] = (float)(var6 * var3.uvScaleU + var3.uvOffsetU);
      var0.uvs[var1 * 2 + 1] = (float)(var5 * var3.uvScaleV + var3.uvOffsetV);
   }

   private static void fillFaceUvs(
      GlbWriter.PrimBlob var0, int var1, float[] var2, int var3, float[] var4, int var5, float[] var6, float[] var7, GlbWriter.Options var8
   ) {
      int var9 = var8.uvUpAxisIndex;
      int var10 = (var9 + 1) % 3;
      int var11 = (var9 + 2) % 3;
      int var12 = Math.abs(var7[var10]) <= Math.abs(var7[var11]) ? var10 : var11;
      float var13 = var2[var12];
      float var14 = var4[var12];
      float var15 = var6[var12];
      float var16 = Math.min(var13, Math.min(var14, var15));
      float var17 = Math.max(var13, Math.max(var14, var15));
      float var18 = var17 - var16;
      if (var18 < 1.0E-6F) {
         var12 = var12 == var10 ? var11 : var10;
         var13 = var2[var12];
         var14 = var4[var12];
         var15 = var6[var12];
         var16 = Math.min(var13, Math.min(var14, var15));
         var17 = Math.max(var13, Math.max(var14, var15));
         var18 = var17 - var16;
      }

      if (var18 < 1.0E-6F) {
         var18 = 1.0F;
      }

      writeFaceUv(var0, var1, (var2[var12] - var16) / var18, var2[var9], var8);
      writeFaceUv(var0, var3, (var4[var12] - var16) / var18, var4[var9], var8);
      writeFaceUv(var0, var5, (var6[var12] - var16) / var18, var6[var9], var8);
   }

   private static void writeFaceUv(GlbWriter.PrimBlob var0, int var1, float var2, float var3, GlbWriter.Options var4) {
      double var5 = var4.uvFloorHeight > 0.0 ? var4.uvFloorHeight : 1.0;
      float var7 = (float)((var3 - var4.uvUpMin) / var5);
      var2 = Math.max(0.0F, Math.min(1.0F, var2));
      var7 = Math.max(0.0F, Math.min(1.0F, var7));
      var0.uvs[var1 * 2] = (float)(var2 * var4.uvScaleU + var4.uvOffsetU);
      var0.uvs[var1 * 2 + 1] = (float)(var7 * var4.uvScaleV + var4.uvOffsetV);
   }

   private static int spatialIdOf(HashMap<String, Integer> var0, float[] var1) {
      long var2 = Math.round(var1[0] * 10000.0);
      long var4 = Math.round(var1[1] * 10000.0);
      long var6 = Math.round(var1[2] * 10000.0);
      String var8 = var2 + "," + var4 + "," + var6;
      Integer var9 = (Integer)var0.get(var8);
      if (var9 == null) {
         var9 = var0.size();
         var0.put(var8, var9);
      }

      return var9;
   }

   private static void accumEdge(HashMap<Long, GlbWriter.EdgeAccum> var0, int var1, int var2, int var3, int var4, float[] var5) {
      if (var1 != var2) {
         int var6 = Math.min(var1, var2);
         int var7 = Math.max(var1, var2);
         long var8 = (long)var6 << 32 | var7 & 4294967295L;
         GlbWriter.EdgeAccum var10 = (GlbWriter.EdgeAccum)var0.get(var8);
         if (var10 == null) {
            var10 = new GlbWriter.EdgeAccum();
            var0.put(var8, var10);
         }

         var10.add(var3, var4, var5);
      }
   }

   private static int[] collectOutline(HashMap<Long, GlbWriter.EdgeAccum> var0, double var1) {
      ArrayList<int[]> var3 = new ArrayList<>();

      for (GlbWriter.EdgeAccum var5 : var0.values()) {
         if (var5.isFeature(var1)) {
            var3.add(new int[]{var5.ia0, var5.ib0});
            if (var5.count >= 2) {
               var3.add(new int[]{var5.ia1, var5.ib1});
            }
         }
      }

      int[] var8 = new int[var3.size() * 2];
      int var9 = 0;

      for (int[] var7 : var3) {
         var8[var9++] = var7[0];
         var8[var9++] = var7[1];
      }

      return var8;
   }

   private static void writeVertex(GlbWriter.PrimBlob var0, int var1, float[] var2, float[] var3) {
      int var4 = var1 * 3;
      var0.positions[var4] = var2[0];
      var0.positions[var4 + 1] = var2[1];
      var0.positions[var4 + 2] = var2[2];
      var0.normals[var4] = var3[0];
      var0.normals[var4 + 1] = var3[1];
      var0.normals[var4 + 2] = var3[2];
      if (var2[0] < var0.posMin[0]) {
         var0.posMin[0] = var2[0];
      }

      if (var2[1] < var0.posMin[1]) {
         var0.posMin[1] = var2[1];
      }

      if (var2[2] < var0.posMin[2]) {
         var0.posMin[2] = var2[2];
      }

      if (var2[0] > var0.posMax[0]) {
         var0.posMax[0] = var2[0];
      }

      if (var2[1] > var0.posMax[1]) {
         var0.posMax[1] = var2[1];
      }

      if (var2[2] > var0.posMax[2]) {
         var0.posMax[2] = var2[2];
      }
   }

   private static float[] faceNormal(float[] var0, float[] var1, float[] var2) {
      float var3 = var1[0] - var0[0];
      float var4 = var1[1] - var0[1];
      float var5 = var1[2] - var0[2];
      float var6 = var2[0] - var0[0];
      float var7 = var2[1] - var0[1];
      float var8 = var2[2] - var0[2];
      float var9 = var4 * var8 - var5 * var7;
      float var10 = var5 * var6 - var3 * var8;
      float var11 = var3 * var7 - var4 * var6;
      float var12 = (float)Math.sqrt(var9 * var9 + var10 * var10 + var11 * var11);
      return var12 < 1.0E-12F ? new float[]{0.0F, 0.0F, 1.0F} : new float[]{var9 / var12, var10 / var12, var11 / var12};
   }

   private static Map<String, Object> buildGltf(
      List<GlbWriter.PrimBlob> var0, List<String> var1, Map<String, Material> var2, int var3, int var4, GlbWriter.Options var5
   ) {
      LinkedHashMap var6 = new LinkedHashMap();
      var6.put("asset", Map.of("version", "2.0", "generator", var5.generator));
      var6.put("scene", 0);
      var6.put("scenes", List.of(Map.of("nodes", List.of(0))));
      var6.put("nodes", List.of(Map.of("mesh", 0)));
      boolean var7 = var5.baseColorTextureJpeg != null && var5.baseColorTextureJpeg.length > 0 && var4 >= 0
         || var5.baseColorTextureUri != null && !var5.baseColorTextureUri.isBlank();
      ArrayList var8 = new ArrayList(var1.size());

      for (String var10 : var1) {
         Material var11 = var2 != null ? (Material)var2.get(var10) : null;
         if (var11 == null) {
            var11 = Material.defaultWhite(var10 == null ? "default" : var10);
         }

         LinkedHashMap var12 = new LinkedHashMap();
         var12.put("name", var11.name);
         LinkedHashMap var13 = new LinkedHashMap();
         boolean var14 = var7 && (var5.texturedMaterials == null || var5.texturedMaterials.contains(var10));
         if (var14) {
            var13.put("baseColorFactor", new float[]{1.0F, 1.0F, 1.0F, 1.0F});
            var13.put("baseColorTexture", Map.of("index", 0, "texCoord", 0));
         } else {
            var13.put("baseColorFactor", new float[]{var11.r, var11.g, var11.b, var11.a});
         }

         var13.put("metallicFactor", 0.0);
         var13.put("roughnessFactor", 1.0);
         var12.put("pbrMetallicRoughness", var13);
         var12.put("doubleSided", var5.doubleSided);
         if (var5.unlit) {
            var12.put("extensions", Map.of("KHR_materials_unlit", Map.of()));
         }

         var8.add(var12);
      }

      var6.put("materials", var8);
      ArrayList var34 = new ArrayList(2);
      if (var5.unlit) {
         var34.add("KHR_materials_unlit");
      }

      boolean var35 = false;

      for (GlbWriter.PrimBlob var38 : var0) {
         if (var38.outlineIndices != null && var38.outlineIndices.length > 0) {
            var35 = true;
            break;
         }
      }

      if (var35) {
         var34.add("CESIUM_primitive_outline");
      }

      if (!var34.isEmpty()) {
         var6.put("extensionsUsed", var34);
      }

      ArrayList var37 = new ArrayList(var0.size() * 3);
      ArrayList var39 = new ArrayList(var0.size() * 3);
      ArrayList var40 = new ArrayList(var0.size());

      for (int var41 = 0; var41 < var0.size(); var41++) {
         GlbWriter.PrimBlob var15 = (GlbWriter.PrimBlob)var0.get(var41);
         LinkedHashMap var16 = new LinkedHashMap();
         var16.put("buffer", 0);
         var16.put("byteOffset", var15.binOffsetPositions);
         var16.put("byteLength", var15.positions.length * 4);
         var16.put("target", 34962);
         var39.add(var16);
         int var17 = var39.size() - 1;
         LinkedHashMap var18 = new LinkedHashMap();
         var18.put("buffer", 0);
         var18.put("byteOffset", var15.binOffsetNormals);
         var18.put("byteLength", var15.normals.length * 4);
         var18.put("target", 34962);
         var39.add(var18);
         int var19 = var39.size() - 1;
         LinkedHashMap var20 = new LinkedHashMap();
         var20.put("buffer", 0);
         var20.put("byteOffset", var15.binOffsetIndices);
         var20.put("byteLength", var15.indices.length * var15.indexByteWidth);
         var20.put("target", 34963);
         var39.add(var20);
         int var21 = var39.size() - 1;
         int var22 = -1;
         if (var15.uvs != null) {
            LinkedHashMap var23 = new LinkedHashMap();
            var23.put("buffer", 0);
            var23.put("byteOffset", var15.binOffsetUv);
            var23.put("byteLength", var15.uvs.length * 4);
            var23.put("target", 34962);
            var39.add(var23);
            var22 = var39.size() - 1;
         }

         LinkedHashMap var44 = new LinkedHashMap();
         var44.put("bufferView", var17);
         var44.put("componentType", 5126);
         var44.put("count", var15.vertexCount);
         var44.put("type", "VEC3");
         var44.put("min", new float[]{var15.posMin[0], var15.posMin[1], var15.posMin[2]});
         var44.put("max", new float[]{var15.posMax[0], var15.posMax[1], var15.posMax[2]});
         var37.add(var44);
         int var24 = var37.size() - 1;
         LinkedHashMap var25 = new LinkedHashMap();
         var25.put("bufferView", var19);
         var25.put("componentType", 5126);
         var25.put("count", var15.vertexCount);
         var25.put("type", "VEC3");
         var37.add(var25);
         int var26 = var37.size() - 1;
         LinkedHashMap var27 = new LinkedHashMap();
         var27.put("bufferView", var21);
         var27.put("componentType", var15.indexByteWidth == 2 ? 5123 : 5125);
         var27.put("count", var15.indices.length);
         var27.put("type", "SCALAR");
         var37.add(var27);
         int var28 = var37.size() - 1;
         int var29 = -1;
         if (var22 >= 0) {
            LinkedHashMap var30 = new LinkedHashMap();
            var30.put("bufferView", var22);
            var30.put("componentType", 5126);
            var30.put("count", var15.vertexCount);
            var30.put("type", "VEC2");
            var37.add(var30);
            var29 = var37.size() - 1;
         }

         int var45 = -1;
         if (var15.outlineIndices != null && var15.outlineIndices.length > 0) {
            LinkedHashMap var31 = new LinkedHashMap();
            var31.put("buffer", 0);
            var31.put("byteOffset", var15.binOffsetOutline);
            var31.put("byteLength", var15.outlineIndices.length * 4);
            var39.add(var31);
            int var32 = var39.size() - 1;
            LinkedHashMap var33 = new LinkedHashMap();
            var33.put("bufferView", var32);
            var33.put("componentType", 5125);
            var33.put("count", var15.outlineIndices.length);
            var33.put("type", "SCALAR");
            var37.add(var33);
            var45 = var37.size() - 1;
         }

         LinkedHashMap var46 = new LinkedHashMap();
         var46.put("POSITION", var24);
         var46.put("NORMAL", var26);
         if (var29 >= 0) {
            var46.put("TEXCOORD_0", var29);
         }

         LinkedHashMap var47 = new LinkedHashMap();
         var47.put("attributes", var46);
         var47.put("indices", var28);
         var47.put("material", var41);
         var47.put("mode", 4);
         if (var45 >= 0) {
            var47.put("extensions", Map.of("CESIUM_primitive_outline", Map.of("indices", var45)));
         }

         var40.add(var47);
      }

      if (var7) {
         if (var5.baseColorTextureUri != null && !var5.baseColorTextureUri.isBlank()) {
            var6.put("images", List.of(Map.of("uri", var5.baseColorTextureUri, "mimeType", "image/jpeg")));
         } else {
            LinkedHashMap var42 = new LinkedHashMap();
            var42.put("buffer", 0);
            var42.put("byteOffset", var4);
            var42.put("byteLength", var5.baseColorTextureJpeg.length);
            var39.add(var42);
            int var43 = var39.size() - 1;
            var6.put("images", List.of(Map.of("bufferView", var43, "mimeType", "image/jpeg")));
         }

         var6.put("samplers", List.of(Map.of("wrapS", 33071, "wrapT", 33071, "magFilter", 9729, "minFilter", 9729)));
         var6.put("textures", List.of(Map.of("source", 0, "sampler", 0)));
      }

      var6.put("accessors", var37);
      var6.put("bufferViews", var39);
      var6.put("meshes", List.of(Map.of("primitives", var40)));
      var6.put("buffers", List.of(Map.of("byteLength", var3)));
      return var6;
   }

   private static int align(int var0, int var1) {
      int var2 = var0 % var1;
      return var2 == 0 ? var0 : var0 + (var1 - var2);
   }

   private static float[] safeGet(List<float[]> var0, int var1) {
      return var1 >= 0 && var1 < var0.size() ? (float[])var0.get(var1) : null;
   }

   private static float[] trim(float[] var0, int var1) {
      if (var0.length == var1) {
         return var0;
      } else {
         float[] var2 = new float[var1];
         System.arraycopy(var0, 0, var2, 0, var1);
         return var2;
      }
   }

   private static int[] trim(int[] var0, int var1) {
      if (var0.length == var1) {
         return var0;
      } else {
         int[] var2 = new int[var1];
         System.arraycopy(var0, 0, var2, 0, var1);
         return var2;
      }
   }

   private static void writeEmpty(OutputStream var0, GlbWriter.Options var1) throws IOException {
      LinkedHashMap var2 = new LinkedHashMap();
      var2.put("asset", Map.of("version", "2.0", "generator", var1.generator));
      var2.put("scene", 0);
      var2.put("scenes", List.of(Map.of("nodes", List.of(0))));
      var2.put("nodes", List.of(Map.of()));
      ObjectMapper var3 = new ObjectMapper();
      byte[] var4 = var3.writeValueAsBytes(var2);
      int var5 = 4 - (var4.length & 3) & 3;
      int var6 = 20 + var4.length + var5;
      ByteBuffer var7 = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
      var7.putInt(1179937895).putInt(2).putInt(var6);
      var0.write(var7.array());
      ByteBuffer var8 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
      var8.putInt(var4.length + var5).putInt(1313821514);
      var0.write(var8.array());
      var0.write(var4);

      for (int var9 = 0; var9 < var5; var9++) {
         var0.write(32);
      }
   }

   private static final class EdgeAccum {
      int count = 0;
      int ia0;
      int ib0;
      int ia1;
      int ib1;
      float n0x;
      float n0y;
      float n0z;
      float n1x;
      float n1y;
      float n1z;
      boolean manifoldBreak = false;

      void add(int var1, int var2, float[] var3) {
         if (this.count == 0) {
            this.ia0 = var1;
            this.ib0 = var2;
            this.n0x = var3[0];
            this.n0y = var3[1];
            this.n0z = var3[2];
         } else if (this.count == 1) {
            this.ia1 = var1;
            this.ib1 = var2;
            this.n1x = var3[0];
            this.n1y = var3[1];
            this.n1z = var3[2];
         } else {
            this.manifoldBreak = true;
         }

         this.count++;
      }

      boolean isFeature(double var1) {
         if (this.count != 1 && !this.manifoldBreak) {
            double var3 = this.n0x * this.n1x + this.n0y * this.n1y + this.n0z * this.n1z;
            return var3 < var1;
         } else {
            return true;
         }
      }
   }

   public static final class Options {
      public boolean unlit = true;
      public boolean generateNormalsIfMissing = true;
      public boolean forceFlat = false;
      public boolean indexVertices = false;
      public boolean doubleSided = true;
      public boolean outlineEdges = false;
      public double outlineAngleDeg = 30.0;
      public String generator = "pipeline_v2 JavaGlb";
      public byte[] baseColorTextureJpeg = null;
      public String baseColorTextureUri = null;
      public boolean generateUv = false;
      public int uvUpAxisIndex = 1;
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
   }

   private static final class PrimBlob {
      float[] positions;
      float[] normals;
      int[] indices;
      float[] uvs;
      int[] outlineIndices;
      float[] posMin = new float[]{Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
      float[] posMax = new float[]{Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
      int vertexCount;
      int indexByteWidth = 4;
      int binOffsetPositions;
      int binOffsetNormals;
      int binOffsetUv;
      int binOffsetIndices;
      int binOffsetOutline;
   }
}
