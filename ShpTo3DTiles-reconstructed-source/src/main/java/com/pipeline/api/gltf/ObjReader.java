package com.pipeline.api.gltf;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class ObjReader {
   private ObjReader() {
   }

   public static Mesh read(Path var0) throws IOException {
      Mesh var1 = new Mesh();
      String var2 = "__default__";

      String var4;
      try (BufferedReader var3 = Files.newBufferedReader(var0, StandardCharsets.ISO_8859_1)) {
         while ((var4 = var3.readLine()) != null) {
            if (!var4.isEmpty()) {
               char var5 = var4.charAt(0);
               if (var5 != '#') {
                  if (var5 == 'v' && var4.length() > 1) {
                     char var6 = var4.charAt(1);
                     if (var6 == ' ' || var6 == '\t') {
                        parseV(var4, var1);
                        continue;
                     }

                     if (var6 == 'n' && var4.length() > 2 && (var4.charAt(2) == ' ' || var4.charAt(2) == '\t')) {
                        parseVN(var4, var1);
                        continue;
                     }

                     if (var6 == 't') {
                        continue;
                     }
                  }

                  if (var5 != 'f' || var4.length() <= 1 || var4.charAt(1) != ' ' && var4.charAt(1) != '\t') {
                     String[] var13 = var4.trim().split("\\s+");
                     if (var13.length != 0) {
                        String var7 = var13[0].toLowerCase(Locale.ROOT);
                        switch (var7) {
                           case "usemtl":
                              if (var13.length > 1) {
                                 var2 = var13[1];
                              }
                              break;
                           case "mtllib":
                              if (var13.length > 1) {
                                 int var10 = var4.indexOf(var13[0]) + var13[0].length();
                                 var1.mtllib = var4.substring(var10).trim();
                              }
                           case "o":
                           case "g":
                           case "s":
                           case "l":
                           case "mg":
                        }
                     }
                  } else {
                     parseF(var4, var1, var2);
                  }
               }
            }
         }
      }

      return var1;
   }

   private static void parseV(String var0, Mesh var1) {
      String[] var2 = split(var0);
      if (var2.length >= 4) {
         try {
            var1.positions.add(new float[]{Float.parseFloat(var2[1]), Float.parseFloat(var2[2]), Float.parseFloat(var2[3])});
         } catch (NumberFormatException var4) {
         }
      }
   }

   private static void parseVN(String var0, Mesh var1) {
      String[] var2 = split(var0);
      if (var2.length >= 4) {
         try {
            var1.normals.add(new float[]{Float.parseFloat(var2[1]), Float.parseFloat(var2[2]), Float.parseFloat(var2[3])});
         } catch (NumberFormatException var4) {
         }
      }
   }

   private static void parseF(String var0, Mesh var1, String var2) {
      String[] var3 = split(var0);
      int var4 = var3.length - 1;
      if (var4 >= 3) {
         int[] var5 = new int[var4];
         int[] var6 = new int[var4];
         int var7 = var1.positions.size();
         int var8 = var1.normals.size();

         for (int var9 = 0; var9 < var4; var9++) {
            ObjReader.FaceVert var10 = parseFaceVert(var3[var9 + 1], var7, var8);
            if (var10 == null) {
               return;
            }

            var5[var9] = var10.v;
            var6[var9] = var10.n;
         }

         List var11 = var1.groupFor(var2);

         for (int var12 = 1; var12 + 1 < var4; var12++) {
            var11.add(new Mesh.Tri(var5[0], var5[var12], var5[var12 + 1], var6[0], var6[var12], var6[var12 + 1]));
         }
      }
   }

   private static ObjReader.FaceVert parseFaceVert(String var0, int var1, int var2) {
      int var3 = var0.indexOf(47);
      int var4 = var3 < 0 ? -1 : var0.indexOf(47, var3 + 1);
      String var5 = var3 < 0 ? var0 : var0.substring(0, var3);
      String var6 = var4 < 0 ? null : var0.substring(var4 + 1);
      int var7 = parseObjIndex(var5, var1);
      if (var7 < 0) {
         return null;
      }

      int var8 = -1;
      if (var6 != null && !var6.isEmpty()) {
         var8 = parseObjIndex(var6, var2);
      }

      return new ObjReader.FaceVert(var7, var8);
   }

   private static int parseObjIndex(String var0, int var1) {
      try {
         int var2 = Integer.parseInt(var0);
         int var3 = var2 > 0 ? var2 - 1 : var1 + var2;
         return var3 >= 0 && var3 < var1 ? var3 : -1;
      } catch (NumberFormatException var4) {
         return -1;
      }
   }

   private static String[] split(String var0) {
      int var1 = var0.length();
      String[] var2 = new String[16];
      int var3 = 0;
      int var4 = 0;

      while (var4 < var1) {
         while (var4 < var1 && (var0.charAt(var4) == ' ' || var0.charAt(var4) == '\t')) {
            var4++;
         }

         if (var4 >= var1) {
            break;
         }

         int var5 = var4;

         while (var4 < var1 && var0.charAt(var4) != ' ' && var0.charAt(var4) != '\t') {
            var4++;
         }

         if (var3 == var2.length) {
            String[] var6 = new String[var2.length * 2];
            System.arraycopy(var2, 0, var6, 0, var2.length);
            var2 = var6;
         }

         var2[var3++] = var0.substring(var5, var4);
      }

      String[] var7 = new String[var3];
      System.arraycopy(var2, 0, var7, 0, var3);
      return var7;
   }

   private record FaceVert(int v, int n) {
   }
}
