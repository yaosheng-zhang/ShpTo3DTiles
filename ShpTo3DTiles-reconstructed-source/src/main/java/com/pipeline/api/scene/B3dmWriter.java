package com.pipeline.api.scene;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class B3dmWriter {
   private B3dmWriter() {
   }

   public static byte[] wrap(byte[] var0) {
      return wrap(var0, List.of());
   }

   public static byte[] wrap(byte[] var0, List<Map<String, Object>> var1) {
      byte[] var2 = ("{\"BATCH_LENGTH\":" + var1.size() + "}").getBytes(StandardCharsets.UTF_8);
      LinkedHashMap<String, List<Object>> var3 = new LinkedHashMap<>();

      for (Map<String, Object> var5 : var1) {
         for (String var7 : var5.keySet()) {
            if (!var3.containsKey(var7)) {
                ArrayList<Object> var8 = new ArrayList<>(var1.size());

                for (Map<String, Object> var10 : var1) {
                  var8.add(var10.get(var7));
               }

               var3.put(var7, var8);
            }
         }
      }

      byte[] var16;
      try {
         var16 = var3.isEmpty() ? new byte[0] : new ObjectMapper().writeValueAsBytes(var3);
      } catch (IOException var15) {
         throw new IllegalArgumentException("Cannot serialize building properties", var15);
      }

      byte[] var17 = Arrays.copyOf(var16, align8(var16.length));
      Arrays.fill(var17, var16.length, var17.length, (byte)32);
      byte var18 = 28;
      int var19 = align8(var18 + var2.length) - var18;
      byte[] var20 = new byte[var19];
      System.arraycopy(var2, 0, var20, 0, var2.length);

      for (int var21 = var2.length; var21 < var19; var21++) {
         var20[var21] = 32;
      }

      int var22 = var18 + var19 + var17.length;
      int var23 = var22 + var0.length;
      int var11 = align8(var23);
      int var12 = var11 - var23;
      ByteBuffer var13 = ByteBuffer.allocate(var11).order(ByteOrder.LITTLE_ENDIAN);
      var13.put((byte)98).put((byte)51).put((byte)100).put((byte)109);
      var13.putInt(1);
      var13.putInt(var11);
      var13.putInt(var19);
      var13.putInt(0);
      var13.putInt(var17.length);
      var13.putInt(0);
      var13.put(var20);
      var13.put(var17);
      var13.put(var0);
      if (var12 > 0) {
         byte[] var14 = new byte[var12];
         var13.put(var14);
      }

      return var13.array();
   }

   private static int align8(int var0) {
      int var1 = var0 & 7;
      return var1 == 0 ? var0 : var0 + (8 - var1);
   }
}
