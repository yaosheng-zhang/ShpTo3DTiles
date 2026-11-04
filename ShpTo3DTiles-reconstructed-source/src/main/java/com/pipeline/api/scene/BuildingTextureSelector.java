package com.pipeline.api.scene;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BuildingTextureSelector {
   private static final Logger log = LoggerFactory.getLogger(BuildingTextureSelector.class);
   private static final int LAYERED_MIN_LC = 10;
   private static final String TEX_COMMERCIAL = "dise_G1_5";
   private static final String TEX_COMMERCIAL_LAYERED = "dise_G1_dx_01";
   private static final String TEX_INDUSTRIAL = "dise_G1_dx_06";
   private static final String TEX_PUBLIC = "dise_G1_dx_05";
   private static final String TEX_OTHER = "db_sumo_bui_001";
   private static final String TEX_OTHER_LAYERED = "dise_G1_dx_02";
   private static final ConcurrentHashMap<String, byte[]> CACHE = new ConcurrentHashMap<>();

   private BuildingTextureSelector() {
   }

   public static String selectStem(String var0, Integer var1) {
      boolean var2 = false;
      String var3 = var0 == null ? "" : var0;
      if (var3.contains("商业")) {
         return var2 ? "dise_G1_dx_01" : "dise_G1_5";
      } else if (var3.contains("工业")) {
         return "dise_G1_dx_06";
      } else if (var3.contains("公共")) {
         return "dise_G1_dx_05";
      } else {
         return var2 ? "dise_G1_dx_02" : "db_sumo_bui_001";
      }
   }

   public static byte[] selectJpeg(String var0, Integer var1) {
      String var2 = selectStem(var0, var1);
      byte[] var3 = load(var2);
      if (var3 == null && !"db_sumo_bui_001".equals(var2)) {
         var3 = load("db_sumo_bui_001");
      }

      return var3;
   }

   public static byte[] load(String var0) {
      if (Set.of("db_sumo_bui_001", "dise_G1_dx_02", "dise_G1_dx_01", "dise_G1_5", "dise_G1_dx_05", "dise_G1_dx_06").contains(var0)) {
         return CACHE.computeIfAbsent(var0, BuildingTextureSelector::readResource);
      }

      try {
         byte[] var1 = Files.readAllBytes(Path.of(var0));
         boolean var2 = var1.length > 8 && var1[0] == -119 && var1[1] == 80 && var1[2] == 78 && var1[3] == 71;
         boolean var3 = var1.length > 3 && var1[0] == -1 && var1[1] == -40;
         if ((var2 || var3) && ImageIO.read(new ByteArrayInputStream(var1)) != null) {
            return var1;
         } else {
            throw new IOException("Expected a readable JPEG or PNG image");
         }
      } catch (IOException | InvalidPathException var4) {
         throw new IllegalArgumentException("Cannot read texture file '" + var0 + "': " + var4.getMessage(), var4);
      }
   }

   public static String outputName(String var0) {
      byte[] var1 = load(var0);
      if (!var0.contains("/") && !var0.contains("\\") && !var0.contains(".")) {
         return var0 + ".jpg";
      }

      try {
         String var2 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(var1));
         return "texture_" + var2 + (var1[0] == -119 ? ".png" : ".jpg");
      } catch (NoSuchAlgorithmException var3) {
         throw new IllegalStateException(var3);
      }
   }

   public static double boundaryOffset(String var0) {
      byte[] var1 = load(var0);
      if (var1 == null) {
         throw new IllegalArgumentException("Missing texture: " + var0);
      }

      try {
         BufferedImage var2 = ImageIO.read(new ByteArrayInputStream(var1));
         double var3 = rowBrightness(var2, var2.getHeight() / 2);
         int var5 = 0;

         while (var5 < var2.getHeight() / 4 && rowBrightness(var2, var5) < var3 * 0.75) {
            var5++;
         }

         return var5 / (2.0 * var2.getHeight());
      } catch (IOException var6) {
         throw new IllegalArgumentException("Unreadable texture: " + var0, var6);
      }
   }

   private static double rowBrightness(BufferedImage var0, int var1) {
      double var2 = 0.0;

      for (int var4 = 0; var4 < var0.getWidth(); var4++) {
         int var5 = var0.getRGB(var4, var1);
         var2 += (var5 >> 16 & 0xFF) + (var5 >> 8 & 0xFF) + (var5 & 0xFF);
      }

      return var2 / (3 * var0.getWidth());
   }

   private static byte[] readResource(String var0) {
      String var1 = "/textures/" + var0 + ".jpg";

      try (InputStream var2 = BuildingTextureSelector.class.getResourceAsStream(var1)) {
         if (var2 == null) {
            log.warn("building texture not found on classpath: {}", var1);
            return null;
         } else {
            return var2.readAllBytes();
         }
      } catch (IOException var7) {
         log.warn("failed to read building texture {}: {}", var1, var7.toString());
         return null;
      }
   }
}
