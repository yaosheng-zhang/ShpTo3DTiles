package com.pipeline.api.gltf;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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
       ArrayList<String> var5 = new ArrayList<>(var0.groups.size());

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
         int var23 = 0;

         for (GlbWriter.PrimBlob var33 : var4) {
            var33.binOffsetPositions = var23;
            var23 += var33.positions.length * 4;
            var33.binOffsetNormals = var23;
            int var25 = var23 + var33.normals.length * 4;
            if (var33.uvs != null) {
               var33.binOffsetUv = var25;
               var25 += var33.uvs.length * 4;
            }

            var33.binOffsetIndices = var25;
            if (var33.batchIds != null) {
               var33.binOffsetBatchIds = var25;
               var25 += var33.batchIds.length * 4;
               var33.binOffsetIndices = var25;
            }

            int var26 = var25 + var33.indices.length * var33.indexByteWidth;
            if (var33.outlineIndices != null && var33.outlineIndices.length > 0) {
               var23 = align(var26, 4);
               var33.binOffsetOutline = var23;
               var26 = var23 + var33.outlineIndices.length * 4;
            }

            var23 = align(var26, 4);
         }

         int var32 = -1;
         byte[] var34 = var3.baseColorTextureJpeg;
         boolean var35 = var34 != null && var34.length > 0;
         if (var35) {
            var23 = align(var23, 4);
            var32 = var23;
            var23 += var34.length;
         }

         ByteBuffer var10 = ByteBuffer.allocate(var23).order(ByteOrder.LITTLE_ENDIAN);

         for (GlbWriter.PrimBlob var12 : var4) {
            var10.position(var12.binOffsetPositions);

            for (float var16 : var12.positions) {
               var10.putFloat(var16);
            }

            var10.position(var12.binOffsetNormals);

            for (float var75 : var12.normals) {
               var10.putFloat(var75);
            }

            if (var12.uvs != null) {
               var10.position(var12.binOffsetUv);

               for (float var76 : var12.uvs) {
                  var10.putFloat(var76);
               }
            }

            var10.position(var12.binOffsetIndices);
            if (var12.batchIds != null) {
               var10.position(var12.binOffsetBatchIds);

               for (int var77 : var12.batchIds) {
                  var10.putInt(var77);
               }

               var10.position(var12.binOffsetIndices);
            }

            if (var12.indexByteWidth == 2) {
               for (int var79 : var12.indices) {
                  var10.putShort((short)(var79 & 65535));
               }
            } else {
               for (int var78 : var12.indices) {
                  var10.putInt(var78);
               }
            }

            if (var12.outlineIndices != null && var12.outlineIndices.length > 0) {
               var10.position(var12.binOffsetOutline);

               for (int var80 : var12.outlineIndices) {
                  var10.putInt(var80);
               }
            }
         }

         if (var35) {
            var10.position(var32);
            var10.put(var34);
         }

         GlbWriter.MetadataBlob var36 = buildMetadata(var3.featureProperties);
         if (var36 != null) {
            var23 = align(var23, 4);
            var36.binOffset = var23;
            var23 += var36.bytes.length;
            var10 = ByteBuffer.allocate(var23).order(ByteOrder.LITTLE_ENDIAN);

            for (GlbWriter.PrimBlob var45 : var4) {
               var10.position(var45.binOffsetPositions);

               for (float var17 : var45.positions) {
                  var10.putFloat(var17);
               }

               var10.position(var45.binOffsetNormals);

               for (float var89 : var45.normals) {
                  var10.putFloat(var89);
               }

               if (var45.uvs != null) {
                  var10.position(var45.binOffsetUv);

                  for (float var90 : var45.uvs) {
                     var10.putFloat(var90);
                  }
               }

               if (var45.batchIds != null) {
                  var10.position(var45.binOffsetBatchIds);

                  for (int var91 : var45.batchIds) {
                     var10.putInt(var91);
                  }
               }

               var10.position(var45.binOffsetIndices);
               if (var45.indexByteWidth == 2) {
                  for (int var93 : var45.indices) {
                     var10.putShort((short)(var93 & 65535));
                  }
               } else {
                  for (int var92 : var45.indices) {
                     var10.putInt(var92);
                  }
               }

               if (var45.outlineIndices != null && var45.outlineIndices.length > 0) {
                  var10.position(var45.binOffsetOutline);

                  for (int var94 : var45.outlineIndices) {
                     var10.putInt(var94);
                  }
               }
            }

            if (var35) {
               var10.position(var32);
               var10.put(var34);
            }

            var10.position(var36.binOffset);
            var10.put(var36.bytes);
         }

         byte[] var38 = var10.array();
         Map var46 = buildGltf(var4, var5, var1, var38.length, var32, var3, var36);
         ObjectMapper var60 = new ObjectMapper();
         byte[] var74 = var60.writeValueAsBytes(var46);
         int var88 = 4 - (var74.length & 3) & 3;
         int var95 = 4 - (var38.length & 3) & 3;
         int var18 = 20 + var74.length + var88 + 8 + var38.length + var95;
         ByteBuffer var19 = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
         var19.putInt(1179937895).putInt(2).putInt(var18);
         var2.write(var19.array());
         ByteBuffer var20 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
         var20.putInt(var74.length + var88).putInt(1313821514);
         var2.write(var20.array());
         var2.write(var74);

         for (int var21 = 0; var21 < var88; var21++) {
            var2.write(32);
         }

         ByteBuffer var96 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
         var96.putInt(var38.length + var95).putInt(5130562);
         var2.write(var96.array());
         var2.write(var38);

         for (int var22 = 0; var22 < var95; var22++) {
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
      if (var2.defaultBatchId >= 0 || var1.stream().anyMatch(var0x -> var0x.batchId >= 0)) {
         var4.batchIds = new int[var3];
      }

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
               int var18 = var15[var17];
               float[] var19;
               if (!var2.forceFlat && var18 >= 0 && var18 < var0.normals.size()) {
                  var19 = normalize(var0.normals.get(var18));
               } else if (!var2.forceFlat && !var2.generateNormalsIfMissing) {
                  var19 = new float[]{0.0F, 0.0F, 1.0F};
               } else {
                  var19 = var13;
               }

               float[] var20 = var14[var17];
               int var21 = var9.batchId >= 0 ? var9.batchId : var2.defaultBatchId;
               String var22 = vertexKey(var20, var19) + ":" + var21;
               Integer var23 = (Integer)var5.get(var22);
               if (var23 == null) {
                  if (var6 == var3) {
                     var3 *= 2;
                     var4.positions = Arrays.copyOf(var4.positions, var3 * 3);
                     var4.normals = Arrays.copyOf(var4.normals, var3 * 3);
                     var4.indices = Arrays.copyOf(var4.indices, var3 * 3);
                     if (var4.batchIds != null) {
                        var4.batchIds = Arrays.copyOf(var4.batchIds, var3);
                     }

                     if (var4.uvs != null) {
                        var4.uvs = Arrays.copyOf(var4.uvs, var3 * 2);
                     }
                  }

                  var23 = var6;
                  var5.put(var22, var23);
                  writeVertex(var4, var6, var20, var19);
                  if (var4.batchIds != null) {
                     var4.batchIds[var6] = var21;
                  }

                  if (var4.uvs != null) {
                     fillUv(var4, var6, var20, var2);
                  }

                  var6++;
               }

               var16[var17] = var23;
            }

            for (int var27 : var16) {
               var4.indices[var7++] = var27;
            }
         }
      }

      var4.positions = trim(var4.positions, var6 * 3);
      var4.normals = trim(var4.normals, var6 * 3);
      var4.indices = trim(var4.indices, var7);
      if (var4.batchIds != null) {
         var4.batchIds = trim(var4.batchIds, var6);
      }

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
      if (var2.defaultBatchId >= 0 || var1.stream().anyMatch(var0x -> var0x.batchId >= 0)) {
         var5.batchIds = new int[var4];
      }

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

            float[] var32 = normalize(var20);
            float[] var33 = normalize(var21);
            float[] var34 = normalize(var22);
            int var23 = var13;
            int var24 = var13 + 1;
            int var25 = var13 + 2;
            if (var5.batchIds != null) {
               int var26 = var15.batchId >= 0 ? var15.batchId : var2.defaultBatchId;
               var5.batchIds[var23] = var5.batchIds[var24] = var5.batchIds[var25] = var26;
            }

            writeVertex(var5, var13++, var16, var32);
            writeVertex(var5, var13++, var17, var33);
            writeVertex(var5, var13++, var18, var34);
            if (var5.uvs != null) {
               if (var15.textureCell >= 0) {
                  fillAtlasUv(var5, var23, var16, var15, var2);
                  fillAtlasUv(var5, var24, var17, var15, var2);
                  fillAtlasUv(var5, var25, var18, var15, var2);
               } else if (var2.uvFaceLocal) {
                  fillFaceUvs(var5, var23, var16, var24, var17, var25, var18, var19, var2);
               } else {
                  fillUv(var5, var23, var16, var2);
                  fillUv(var5, var24, var17, var2);
                  fillUv(var5, var25, var18, var2);
               }
            }

            if (var6) {
               int var35 = spatialIdOf(var11, var16);
               int var27 = spatialIdOf(var11, var17);
               int var28 = spatialIdOf(var11, var18);
               accumEdge(var12, var35, var27, var23, var24, var19);
               accumEdge(var12, var27, var28, var24, var25, var19);
               accumEdge(var12, var28, var35, var25, var23, var19);
            }
         }
      }

      if (var13 != var4) {
         var5.positions = trim(var5.positions, var13 * 3);
         var5.normals = trim(var5.normals, var13 * 3);
         var5.indices = trim(var5.indices, var13);
         if (var5.batchIds != null) {
            var5.batchIds = trim(var5.batchIds, var13);
         }

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

      float var8 = (float)(var6 / var3.uvTileWidth);
      if (var3.uvFractional) {
         var8 = (float)(var8 - Math.floor(var8));
         var5 = (float)(var5 - Math.floor(var5));
      }

      var0.uvs[var1 * 2] = (float)(var8 * var3.uvScaleU + var3.uvOffsetU);
      var0.uvs[var1 * 2 + 1] = (float)(var5 * var3.uvScaleV + var3.uvOffsetV);
   }

   private static void fillAtlasUv(GlbWriter.PrimBlob var0, int var1, float[] var2, Mesh.Tri var3, GlbWriter.Options var4) {
      if (var3.textureCell >= 0 && var4.atlasColumns > 0 && var4.atlasRows > 0) {
         int var5 = var3.textureCell % var4.atlasColumns;
         int var6 = var3.textureCell / var4.atlasColumns;
         double var7 = 1.0 / var4.atlasColumns;
         double var9 = 1.0 / var4.atlasRows;
         double var11 = Math.min((double)var4.atlasGutter / var4.atlasWidth, var7 * 0.25);
         double var13 = Math.min((double)var4.atlasGutter / var4.atlasHeight, var9 * 0.25);
         int var15 = var4.uvUpAxisIndex;
         int var16 = (var15 + 1) % 3;
         int var17 = (var15 + 2) % 3;
         double var18 = (var2[var16] + var2[var17]) / (2.0 * var4.uvTileWidth);
         double var20 = var3.textureFloorHeight > 0.0 ? var3.textureFloorHeight : var4.uvFloorHeight;
         double var22 = (var2[var15] - var3.textureBaseZ) / var20;
         if (var4.atlasColumns == 1 && var4.atlasRows == 1 && var4.atlasWidth == 1 && var4.atlasHeight == 1) {
            var0.uvs[var1 * 2] = (float)var18;
            var0.uvs[var1 * 2 + 1] = (float)var22;
         } else {
            var18 -= Math.floor(var18);
            var22 -= Math.floor(var22);
            var0.uvs[var1 * 2] = (float)(var5 * var7 + var11 + var18 * (var7 - 2.0 * var11));
            double var24 = 1.0 - (var6 + 1) * var9;
            var0.uvs[var1 * 2 + 1] = (float)(var24 + var13 + var22 * (var9 - 2.0 * var13));
         }
      } else {
         fillUv(var0, var1, var2, var4);
      }
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
      List<GlbWriter.PrimBlob> var0, List<String> var1, Map<String, Material> var2, int var3, int var4, GlbWriter.Options var5, GlbWriter.MetadataBlob var6
   ) {
      LinkedHashMap var7 = new LinkedHashMap();
      var7.put("asset", Map.of("version", "2.0", "generator", var5.generator));
      var7.put("scene", 0);
      var7.put("scenes", List.of(Map.of("nodes", List.of(0))));
      var7.put("nodes", List.of(Map.of("mesh", 0)));
      boolean var8 = var5.baseColorTextureJpeg != null && var5.baseColorTextureJpeg.length > 0 && var4 >= 0
         || var5.baseColorTextureUri != null && !var5.baseColorTextureUri.isBlank()
         || var5.textureUriByMaterial != null && !var5.textureUriByMaterial.isEmpty();
      ArrayList var9 = new ArrayList(var1.size());

      for (String var11 : var1) {
         Material var12 = var2 != null ? (Material)var2.get(var11) : null;
         if (var12 == null) {
            var12 = Material.defaultWhite(var11 == null ? "default" : var11);
         }

         LinkedHashMap var13 = new LinkedHashMap();
         var13.put("name", var12.name);
         LinkedHashMap var14 = new LinkedHashMap();
         String var15 = var5.textureUriByMaterial == null ? null : var5.textureUriByMaterial.get(var11);
         boolean var16 = var8 && var15 != null && !var15.isBlank()
            || var8 && var15 == null && (var5.texturedMaterials == null || var5.texturedMaterials.contains(var11));
         if (var16) {
            var14.put("baseColorFactor", new float[]{1.0F, 1.0F, 1.0F, 1.0F});
            var14.put("baseColorTexture", Map.of("index", textureIndexFor(var5, var15), "texCoord", 0));
         } else {
            var14.put("baseColorFactor", new float[]{var12.r, var12.g, var12.b, var12.a});
         }

         var14.put("metallicFactor", 0.0);
         var14.put("roughnessFactor", 1.0);
         var13.put("pbrMetallicRoughness", var14);
         var13.put("doubleSided", var5.doubleSided);
         if (var5.unlit || var5.unlitMaterials != null && var5.unlitMaterials.contains(var11)) {
            var13.put("extensions", Map.of("KHR_materials_unlit", Map.of()));
         }

         var9.add(var13);
      }

      var7.put("materials", var9);
      ArrayList var35 = new ArrayList(2);
      if (var5.unlit || var5.unlitMaterials != null && !var5.unlitMaterials.isEmpty()) {
         var35.add("KHR_materials_unlit");
      }

      boolean var36 = false;

      for (GlbWriter.PrimBlob var39 : var0) {
         if (var39.outlineIndices != null && var39.outlineIndices.length > 0) {
            var36 = true;
            break;
         }
      }

      if (var36) {
         var35.add("CESIUM_primitive_outline");
      }

      if (var6 != null) {
         var35.add("EXT_mesh_features");
         var35.add("EXT_structural_metadata");
      }

      if (!var35.isEmpty()) {
         var7.put("extensionsUsed", var35);
      }

      ArrayList var38 = new ArrayList(var0.size() * 3);
      ArrayList var40 = new ArrayList(var0.size() * 3);
      ArrayList var41 = new ArrayList(var0.size());

      for (int var42 = 0; var42 < var0.size(); var42++) {
         GlbWriter.PrimBlob var47 = (GlbWriter.PrimBlob)var0.get(var42);
         LinkedHashMap var17 = new LinkedHashMap();
         var17.put("buffer", 0);
         var17.put("byteOffset", var47.binOffsetPositions);
         var17.put("byteLength", var47.positions.length * 4);
         var17.put("target", 34962);
         var40.add(var17);
         int var18 = var40.size() - 1;
         LinkedHashMap var19 = new LinkedHashMap();
         var19.put("buffer", 0);
         var19.put("byteOffset", var47.binOffsetNormals);
         var19.put("byteLength", var47.normals.length * 4);
         var19.put("target", 34962);
         var40.add(var19);
         int var20 = var40.size() - 1;
         LinkedHashMap var21 = new LinkedHashMap();
         var21.put("buffer", 0);
         var21.put("byteOffset", var47.binOffsetIndices);
         var21.put("byteLength", var47.indices.length * var47.indexByteWidth);
         var21.put("target", 34963);
         var40.add(var21);
         int var22 = var40.size() - 1;
         int var23 = -1;
         if (var47.uvs != null) {
            LinkedHashMap var24 = new LinkedHashMap();
            var24.put("buffer", 0);
            var24.put("byteOffset", var47.binOffsetUv);
            var24.put("byteLength", var47.uvs.length * 4);
            var24.put("target", 34962);
            var40.add(var24);
            var23 = var40.size() - 1;
         }

         LinkedHashMap var60 = new LinkedHashMap();
         var60.put("bufferView", var18);
         var60.put("componentType", 5126);
         var60.put("count", var47.vertexCount);
         var60.put("type", "VEC3");
         var60.put("min", new float[]{var47.posMin[0], var47.posMin[1], var47.posMin[2]});
         var60.put("max", new float[]{var47.posMax[0], var47.posMax[1], var47.posMax[2]});
         var38.add(var60);
         int var25 = var38.size() - 1;
         LinkedHashMap var26 = new LinkedHashMap();
         var26.put("bufferView", var20);
         var26.put("componentType", 5126);
         var26.put("count", var47.vertexCount);
         var26.put("type", "VEC3");
         var38.add(var26);
         int var27 = var38.size() - 1;
         LinkedHashMap var28 = new LinkedHashMap();
         var28.put("bufferView", var22);
         var28.put("componentType", var47.indexByteWidth == 2 ? 5123 : 5125);
         var28.put("count", var47.indices.length);
         var28.put("type", "SCALAR");
         var38.add(var28);
         int var29 = var38.size() - 1;
         int var30 = -1;
         if (var23 >= 0) {
            LinkedHashMap var31 = new LinkedHashMap();
            var31.put("bufferView", var23);
            var31.put("componentType", 5126);
            var31.put("count", var47.vertexCount);
            var31.put("type", "VEC2");
            var38.add(var31);
            var30 = var38.size() - 1;
         }

         int var61 = -1;
         if (var47.outlineIndices != null && var47.outlineIndices.length > 0) {
            LinkedHashMap var32 = new LinkedHashMap();
            var32.put("buffer", 0);
            var32.put("byteOffset", var47.binOffsetOutline);
            var32.put("byteLength", var47.outlineIndices.length * 4);
            var40.add(var32);
            int var33 = var40.size() - 1;
            LinkedHashMap var34 = new LinkedHashMap();
            var34.put("bufferView", var33);
            var34.put("componentType", 5125);
            var34.put("count", var47.outlineIndices.length);
            var34.put("type", "SCALAR");
            var38.add(var34);
            var61 = var38.size() - 1;
         }

         LinkedHashMap var62 = new LinkedHashMap();
         var62.put("POSITION", var25);
         var62.put("NORMAL", var27);
         if (var47.batchIds != null) {
            var40.add(Map.of("buffer", 0, "byteOffset", var47.binOffsetBatchIds, "byteLength", var47.batchIds.length * 4, "target", 34962));
            var38.add(Map.of("bufferView", var40.size() - 1, "componentType", 5125, "count", var47.vertexCount, "type", "SCALAR"));
            var62.put("_BATCHID", var38.size() - 1);
            var62.put("_FEATURE_ID_0", var38.size() - 1);
         }

         if (var30 >= 0) {
            var62.put("TEXCOORD_0", var30);
         }

         LinkedHashMap var63 = new LinkedHashMap();
         var63.put("attributes", var62);
         var63.put("indices", var29);
         var63.put("material", var42);
         var63.put("mode", 4);
         LinkedHashMap var64 = new LinkedHashMap();
         if (var61 >= 0) {
            var64.put("CESIUM_primitive_outline", Map.of("indices", var61));
         }

         if (var6 != null && var47.batchIds != null) {
            var64.put("EXT_mesh_features", Map.of("featureIds", List.of(Map.of("attribute", 0, "propertyTable", 0, "featureCount", var6.count))));
         }

         if (!var64.isEmpty()) {
            var63.put("extensions", var64);
         }

         var41.add(var63);
      }

      if (var8) {
         if (var5.baseColorTextureUri != null && !var5.baseColorTextureUri.isBlank()) {
            ArrayList var44 = new ArrayList();

            for (String var52 : externalTextureIndices(var5).keySet()) {
               var44.add(Map.of("uri", var52, "mimeType", var52.endsWith(".png") ? "image/png" : "image/jpeg"));
            }

            var7.put("images", var44);
         } else {
            LinkedHashMap var43 = new LinkedHashMap();
            var43.put("buffer", 0);
            var43.put("byteOffset", var4);
            var43.put("byteLength", var5.baseColorTextureJpeg.length);
            var40.add(var43);
            int var48 = var40.size() - 1;
            var7.put("images", List.of(Map.of("bufferView", var48, "mimeType", "image/jpeg")));
         }

         int var45 = var5.uvFaceLocal ? 33071 : 10497;
         var7.put("samplers", List.of(Map.of("wrapS", var45, "wrapT", var45, "magFilter", 9729, "minFilter", 9987)));
         if (var5.baseColorTextureUri != null && !var5.baseColorTextureUri.isBlank()) {
            ArrayList var50 = new ArrayList();

            for (int var53 = 0; var53 < externalTextureIndices(var5).size(); var53++) {
               var50.add(Map.of("source", var53, "sampler", 0));
            }

            var7.put("textures", var50);
         } else {
            var7.put("textures", List.of(Map.of("source", 0, "sampler", 0)));
         }
      }

      if (var6 != null) {
          LinkedHashMap<String, Object> var46 = new LinkedHashMap<>();
          LinkedHashMap<String, Object> var51 = new LinkedHashMap<>();

         for (GlbWriter.MetadataColumn var56 : var6.columns) {
            var46.put(var56.name, Map.of("type", "STRING"));
            int var58 = var40.size();
            var40.add(Map.of("buffer", 0, "byteOffset", var6.binOffset + var56.valuesOffset, "byteLength", var56.valuesLength));
            int var59 = var40.size();
            var40.add(Map.of("buffer", 0, "byteOffset", var6.binOffset + var56.offsetsOffset, "byteLength", var56.offsetsLength));
            var51.put(var56.name, Map.of("values", var58, "stringOffsets", var59));
         }

          Map<String, Object> var55 = Map.of("id", "shp_to_3dtiles", "classes", Map.<String, Object>of("building", Map.of("properties", var46)));
          Map<String, Object> var57 = Map.of(
             "schema", var55, "propertyTables", List.<Map<String, Object>>of(Map.of("class", "building", "count", var6.count, "properties", var51))
          );
         var7.put("extensions", Map.of("EXT_structural_metadata", var57));
      }

      var7.put("accessors", var38);
      var7.put("bufferViews", var40);
      var7.put("meshes", List.of(Map.of("primitives", var41)));
      var7.put("buffers", List.of(Map.of("byteLength", var3)));
      return var7;
   }

   private static int textureIndexFor(GlbWriter.Options var0, String var1) {
      if (var0.baseColorTextureUri != null && !var0.baseColorTextureUri.isBlank()) {
         String var2 = var1 == null ? var0.baseColorTextureUri : var1;
         return externalTextureIndices(var0).getOrDefault(var2, 0);
      } else {
         return 0;
      }
   }

   private static LinkedHashMap<String, Integer> externalTextureIndices(GlbWriter.Options var0) {
       LinkedHashMap<String, Integer> var1 = new LinkedHashMap<>();
      if (var0.baseColorTextureUri != null && !var0.baseColorTextureUri.isBlank()) {
         var1.put(var0.baseColorTextureUri, 0);
      }

      if (var0.textureUriByMaterial != null) {
         for (String var3 : var0.textureUriByMaterial.values()) {
            if (var3 != null && !var3.isBlank()) {
               var1.putIfAbsent(var3, var1.size());
            }
         }
      }

      return var1;
   }

   private static int align(int var0, int var1) {
      int var2 = var0 % var1;
      return var2 == 0 ? var0 : var0 + (var1 - var2);
   }

   private static GlbWriter.MetadataBlob buildMetadata(List<Map<String, Object>> var0) {
      if (var0 != null && !var0.isEmpty()) {
          LinkedHashMap<String, List<String>> var1 = new LinkedHashMap<>();

          for (Map<String, Object> var3 : var0) {
            if (var3 != null) {
               for (String var5 : var3.keySet()) {
                  var1.computeIfAbsent(var5, var0x -> new ArrayList());
               }
            }
         }

         if (var1.isEmpty()) {
            return null;
         }

         for (List var18 : var1.values()) {
            while (var18.size() < var0.size()) {
               var18.add("");
            }
         }

         ByteArrayOutputStream var17 = new ByteArrayOutputStream();
          ArrayList<GlbWriter.MetadataColumn> var19 = new ArrayList<>();

         for (Entry var21 : var1.entrySet()) {
            int var6 = var17.size();
            int[] var7 = new int[var0.size() + 1];
            int var8 = 0;

            for (int var9 = 0; var9 < var0.size(); var9++) {
               Map var10 = (Map)var0.get(var9);
               Object var11 = var10 == null ? null : var10.get(var21.getKey());
               String var12 = var11 == null ? "" : String.valueOf(var11);
               byte[] var13 = var12.getBytes(StandardCharsets.UTF_8);
               var17.write(var13, 0, var13.length);
               var8 += var13.length;
               var7[var9 + 1] = var8;
            }

            int var22 = var17.size() - var6;
            int var23 = align(var17.size(), 4);

            while (var17.size() < var23) {
               var17.write(0);
            }

            ByteBuffer var24 = ByteBuffer.allocate(var7.length * 4).order(ByteOrder.LITTLE_ENDIAN);

            for (int var15 : var7) {
               var24.putInt(var15);
            }

            byte[] var26 = var24.array();
            var17.write(var26, 0, var26.length);
            var19.add(new GlbWriter.MetadataColumn((String)var21.getKey(), var6, var22, var23, var26.length));
         }

         return new GlbWriter.MetadataBlob(var17.toByteArray(), var0.size(), var19);
      } else {
         return null;
      }
   }

   private static float[] safeGet(List<float[]> var0, int var1) {
      return var1 >= 0 && var1 < var0.size() ? (float[])var0.get(var1) : null;
   }

   private static float[] trim(float[] var0, int var1) {
      if (var0.length == var1) {
         return var0;
      }

      float[] var2 = new float[var1];
      System.arraycopy(var0, 0, var2, 0, var1);
      return var2;
   }

   private static int[] trim(int[] var0, int var1) {
      if (var0.length == var1) {
         return var0;
      }

      int[] var2 = new int[var1];
      System.arraycopy(var0, 0, var2, 0, var1);
      return var2;
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

   private static final class MetadataBlob {
      final byte[] bytes;
      final int count;
      final List<GlbWriter.MetadataColumn> columns;
      int binOffset;

      MetadataBlob(byte[] var1, int var2, List<GlbWriter.MetadataColumn> var3) {
         this.bytes = var1;
         this.count = var2;
         this.columns = var3;
      }
   }

   private record MetadataColumn(String name, int valuesOffset, int valuesLength, int offsetsOffset, int offsetsLength) {
   }

   public static final class Options {
      public int defaultBatchId = -1;
      public boolean unlit = true;
      public Set<String> unlitMaterials = null;
      public boolean generateNormalsIfMissing = true;
      public boolean forceFlat = false;
      public boolean indexVertices = false;
      public boolean doubleSided = true;
      public boolean outlineEdges = false;
      public double outlineAngleDeg = 30.0;
      public String generator = "pipeline_v2 JavaGlb";
      public byte[] baseColorTextureJpeg = null;
      public String baseColorTextureUri = null;
      public Map<String, String> textureUriByMaterial = null;
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
      public int atlasColumns = 0;
      public int atlasRows = 0;
      public int atlasWidth = 0;
      public int atlasHeight = 0;
      public int atlasGutter = 0;
      public List<Map<String, Object>> featureProperties = List.of();
   }

   private static final class PrimBlob {
      float[] positions;
      float[] normals;
      int[] indices;
      float[] uvs;
      int[] batchIds;
      int binOffsetBatchIds;
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
