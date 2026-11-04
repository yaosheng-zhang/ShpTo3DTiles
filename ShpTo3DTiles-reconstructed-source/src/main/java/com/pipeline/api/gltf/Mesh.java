package com.pipeline.api.gltf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Mesh {
   public final List<float[]> positions = new ArrayList<>();
   public final List<float[]> normals = new ArrayList<>();
   public final List<Map<String, Object>> featureProperties = new ArrayList<>();
   public final Map<String, List<Mesh.Tri>> groups = new LinkedHashMap<>();
   public String mtllib;

   public List<Mesh.Tri> groupFor(String var1) {
      return this.groups.computeIfAbsent(var1, var0 -> new ArrayList<>());
   }

   public int triangleCount() {
      int var1 = 0;

      for (List var3 : this.groups.values()) {
         var1 += var3.size();
      }

      return var1;
   }

   public static final class Tri {
      public final int v0;
      public final int v1;
      public final int v2;
      public final int n0;
      public final int n1;
      public final int n2;
      public final int batchId;
      public final int textureCell;
      public final double textureBaseZ;
      public final double textureFloorHeight;

      public Tri(int var1, int var2, int var3, int var4, int var5, int var6) {
         this(var1, var2, var3, var4, var5, var6, -1, -1, 0.0, 3.0);
      }

      public Tri(int var1, int var2, int var3, int var4, int var5, int var6, int var7) {
         this(var1, var2, var3, var4, var5, var6, var7, -1, 0.0, 3.0);
      }

      public Tri(int var1, int var2, int var3, int var4, int var5, int var6, int var7, int var8) {
         this(var1, var2, var3, var4, var5, var6, var7, var8, 0.0, 3.0);
      }

      public Tri(int var1, int var2, int var3, int var4, int var5, int var6, int var7, int var8, double var9, double var11) {
         this.v0 = var1;
         this.v1 = var2;
         this.v2 = var3;
         this.n0 = var4;
         this.n1 = var5;
         this.n2 = var6;
         this.batchId = var7;
         this.textureCell = var8;
         this.textureBaseZ = var9;
         this.textureFloorHeight = var11;
      }
   }
}
