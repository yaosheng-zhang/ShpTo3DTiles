package com.pipeline.api.gltf;

public final class Material {
   public final String name;
   public final float r;
   public final float g;
   public final float b;
   public final float a;

   public Material(String var1, float var2, float var3, float var4, float var5) {
      this.name = var1;
      this.r = var2;
      this.g = var3;
      this.b = var4;
      this.a = var5;
   }

   public static Material defaultWhite(String var0) {
      return new Material(var0, 1.0F, 1.0F, 1.0F, 1.0F);
   }
}
