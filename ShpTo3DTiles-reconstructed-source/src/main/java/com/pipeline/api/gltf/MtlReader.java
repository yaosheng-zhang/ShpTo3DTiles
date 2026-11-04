package com.pipeline.api.gltf;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class MtlReader {
   private MtlReader() {
   }

   public static Map<String, Material> read(Path var0) throws IOException {
      LinkedHashMap var1 = new LinkedHashMap();
      if (!Files.exists(var0)) {
         return var1;
      }

      String var2 = null;
      float var3 = 1.0F;
      float var4 = 1.0F;
      float var5 = 1.0F;
      float var6 = 1.0F;

      String var8;
      try (BufferedReader var7 = Files.newBufferedReader(var0, StandardCharsets.ISO_8859_1)) {
         while ((var8 = var7.readLine()) != null) {
            String var15 = var8.trim();
            if (!var15.isEmpty() && var15.charAt(0) != '#') {
               String[] var9 = var15.split("\\s+");
               String var10 = var9[0].toLowerCase(Locale.ROOT);
               switch (var10) {
                  case "newmtl":
                     if (var2 != null) {
                        var1.put(var2, new Material(var2, var3, var4, var5, var6));
                     }

                     var2 = var9.length > 1 ? var9[1] : "default";
                     var3 = 1.0F;
                     var4 = 1.0F;
                     var5 = 1.0F;
                     var6 = 1.0F;
                     break;
                  case "kd":
                     if (var9.length >= 4) {
                        var3 = parseFloat(var9[1], var3);
                        var4 = parseFloat(var9[2], var4);
                        var5 = parseFloat(var9[3], var5);
                     }
                     break;
                  case "d":
                     if (var9.length >= 2) {
                        var6 = parseFloat(var9[1], var6);
                     }
                     break;
                  case "tr":
                     if (var9.length >= 2) {
                        var6 = 1.0F - parseFloat(var9[1], 0.0F);
                     }
               }
            }
         }
      }

      if (var2 != null) {
         var1.put(var2, new Material(var2, var3, var4, var5, var6));
      }

      return var1;
   }

   private static float parseFloat(String var0, float var1) {
      try {
         return Float.parseFloat(var0);
      } catch (NumberFormatException var3) {
         return var1;
      }
   }
}
