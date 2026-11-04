package com.pipeline.api.scene;

import com.pipeline.api.gltf.ObjToGlb;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ObjToGlbConverter {
   private final ObjToGlb.Options BUILDING_OPTS = makeOpts(false);
   private final ObjToGlb.Options ROAD_OPTS = makeOpts(false);

   private static ObjToGlb.Options makeOpts(boolean var0) {
      ObjToGlb.Options var1 = new ObjToGlb.Options();
      var1.defaultBatchId = 0;
      var1.inputAxis = ObjToGlb.Axis.Z;
      var1.outputAxis = ObjToGlb.Axis.Y;
      var1.unlit = false;
      var1.generateNormalsIfMissing = true;
      var1.outlineEdges = var0;
      var1.outlineAngleDeg = 60.0;
      var1.generator = "pipeline_v2 JavaGlb";
      return var1;
   }

   public void convert(Path var1, Path var2) throws IOException {
      ObjToGlb.convert(var1, var2, this.BUILDING_OPTS);
   }

   public void convert(Path var1, Path var2, boolean var3) throws IOException {
      ObjToGlb.convert(var1, var2, var3 ? this.BUILDING_OPTS : this.ROAD_OPTS);
   }

   public void convert(Path var1, Path var2, byte[] var3, double var4, double var6, double var8) throws IOException {
      this.convert(var1, var2, var3, var4, var6, var8, List.of());
   }

   public void convert(Path var1, Path var2, byte[] var3, double var4, double var6, double var8, List<Map<String, Object>> var10) throws IOException {
      if (var3 != null && var3.length != 0) {
         ObjToGlb.Options var11 = makeOpts(false);
         var11.baseColorTextureJpeg = var3;
         var11.texturedMaterials = Set.of("wall");
         var11.generateUv = true;
         var11.uvUpMin = var4;
         var11.uvFloorHeight = var6 > 0.0 ? var6 : 3.0;
         var11.uvTileWidth = var8 > 0.0 ? var8 : 4.0;
         ObjToGlb.convert(var1, var2, var11);
      } else {
         this.convert(var1, var2);
      }
   }

   public void convertRepeating(Path var1, Path var2, String var3, double var4, double var6, double var8) throws IOException {
      ObjToGlb.Options var10 = makeOpts(false);
      var10.baseColorTextureUri = var3;
      var10.texturedMaterials = Set.of("wall");
      var10.generateUv = true;
      var10.uvUpMin = var4;
      var10.uvFloorHeight = var6;
      var10.uvTileWidth = 4.0;
      var10.uvOffsetV = var8;
      ObjToGlb.convert(var1, var2, var10);
   }

   public void convertWithProperties(Path var1, Path var2, byte[] var3, double var4, double var6, double var8, List<Map<String, Object>> var10) throws IOException {
      ObjToGlb.Options var11 = makeOpts(false);
      var11.featureProperties = var10 == null ? List.of() : var10;
      if (var3 != null && var3.length > 0) {
         var11.baseColorTextureJpeg = var3;
         var11.texturedMaterials = Set.of("wall");
         var11.generateUv = true;
         var11.uvUpMin = var4;
         var11.uvFloorHeight = var6 > 0.0 ? var6 : 3.0;
         var11.uvTileWidth = var8 > 0.0 ? var8 : 4.0;
      }

      ObjToGlb.convert(var1, var2, var11);
   }

   public void convertRepeatingWithProperties(Path var1, Path var2, String var3, double var4, double var6, double var8, List<Map<String, Object>> var10) throws IOException {
      ObjToGlb.Options var11 = makeOpts(false);
      var11.baseColorTextureUri = var3;
      var11.texturedMaterials = Set.of("wall");
      var11.generateUv = true;
      var11.uvUpMin = var4;
      var11.uvFloorHeight = var6;
      var11.uvTileWidth = 4.0;
      var11.uvOffsetV = var8;
      var11.featureProperties = var10 == null ? List.of() : var10;
      ObjToGlb.convert(var1, var2, var11);
   }

   public void convertAtlas(Path var1, Path var2, String var3, double var4, double var6, double var8, double var10, double var12, double var14, double var16) throws IOException {
      ObjToGlb.Options var18 = makeOpts(false);
      var18.baseColorTextureUri = var3;
      var18.texturedMaterials = Set.of("wall");
      var18.generateUv = true;
      var18.uvUpMin = var4;
      var18.uvFloorHeight = var6 > 0.0 ? var6 : 3.0;
      var18.uvTileWidth = var8 > 0.0 ? var8 : 4.0;
      var18.uvScaleU = var10;
      var18.uvScaleV = var12;
      var18.uvOffsetU = var14;
      var18.uvOffsetV = var16;
      var18.uvFractional = false;
      var18.uvFaceLocal = true;
      ObjToGlb.convert(var1, var2, var18);
   }
}
