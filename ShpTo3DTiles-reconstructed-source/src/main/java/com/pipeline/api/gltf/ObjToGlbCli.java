package com.pipeline.api.gltf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ObjToGlbCli {
   private ObjToGlbCli() {
   }

   public static void main(String[] var0) throws IOException {
      if (var0.length < 2) {
         System.err
            .println(
               "usage: ObjToGlbCli <input.obj> <output.glb> [--inputUpAxis X|Y|Z] [--outputUpAxis X|Y|Z] [--unlit true|false] [--forceFlat true|false] [--outline true|false] [--outlineAngle <deg>] [--mtl <path>]"
            );
         System.exit(2);
      }

      Path var1 = Paths.get(var0[0]);
      Path var2 = Paths.get(var0[1]);
      ObjToGlb.Options var3 = new ObjToGlb.Options();

      for (int var4 = 2; var4 < var0.length; var4++) {
         switch (var0[var4]) {
            case "--inputUpAxis":
               var3.inputAxis = parseAxis(var0[++var4]);
               break;
            case "--outputUpAxis":
               var3.outputAxis = parseAxis(var0[++var4]);
               break;
            case "--unlit":
               var3.unlit = Boolean.parseBoolean(var0[++var4]);
               break;
            case "--forceFlat":
               var3.forceFlat = Boolean.parseBoolean(var0[++var4]);
               break;
            case "--outline":
               var3.outlineEdges = Boolean.parseBoolean(var0[++var4]);
               break;
            case "--outlineAngle":
               var3.outlineAngleDeg = Double.parseDouble(var0[++var4]);
               break;
            case "--mtl":
               var3.mtlOverride = Paths.get(var0[++var4]);
               break;
            default:
               System.err.println("warn: ignoring unknown flag " + var0[var4]);
         }
      }

      if (!Files.exists(var1)) {
         System.err.println("input OBJ not found: " + var1);
         System.exit(1);
      }

      long var10 = System.nanoTime();
      ObjToGlb.convert(var1, var2, var3);
      long var11 = (System.nanoTime() - var10) / 1000000L;
      long var8 = Files.size(var2);
      System.out.printf("[ObjToGlbCli] %s -> %s  (%d bytes, %d ms)%n", var1.getFileName(), var2.getFileName(), var8, var11);
   }

   private static ObjToGlb.Axis parseAxis(String var0) {
      return switch (var0.toUpperCase()) {
         case "X" -> ObjToGlb.Axis.X;
         case "Y" -> ObjToGlb.Axis.Y;
         case "Z" -> ObjToGlb.Axis.Z;
         default -> throw new IllegalArgumentException("axis must be X|Y|Z, got " + var0);
      };
   }
}
