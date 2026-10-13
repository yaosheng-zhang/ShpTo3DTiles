package com.pipeline.api.gltf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Stand-alone driver for the Java OBJ→GLB converter, so you can sanity-check
 * the output against obj2gltf without having to spin up Spring.
 *
 *   Usage:
 *     java -cp target/shp-to-3dtiles-tool-1.1.0.jar com.pipeline.api.gltf.ObjToGlbCli \
 *         -Dexec.args="input.obj output.glb [--inputUpAxis Z] [--outputUpAxis Y] [--unlit] [--mtl path.mtl]"
 *
 * Or just run from your IDE — the args parser is intentionally lenient.
 */
public final class ObjToGlbCli {

    private ObjToGlbCli() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println(
                "usage: ObjToGlbCli <input.obj> <output.glb> "
              + "[--inputUpAxis X|Y|Z] [--outputUpAxis X|Y|Z] "
              + "[--unlit true|false] [--forceFlat true|false] "
              + "[--outline true|false] [--outlineAngle <deg>] [--mtl <path>]");
            System.exit(2);
        }
        Path obj = Paths.get(args[0]);
        Path glb = Paths.get(args[1]);

        ObjToGlb.Options opts = new ObjToGlb.Options();
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--inputUpAxis"  -> opts.inputAxis  = parseAxis(args[++i]);
                case "--outputUpAxis" -> opts.outputAxis = parseAxis(args[++i]);
                case "--unlit"        -> opts.unlit      = Boolean.parseBoolean(args[++i]);
                case "--forceFlat"    -> opts.forceFlat  = Boolean.parseBoolean(args[++i]);
                case "--outline"      -> opts.outlineEdges = Boolean.parseBoolean(args[++i]);
                case "--outlineAngle" -> opts.outlineAngleDeg = Double.parseDouble(args[++i]);
                case "--mtl"          -> opts.mtlOverride = Paths.get(args[++i]);
                default -> System.err.println("warn: ignoring unknown flag " + args[i]);
            }
        }

        if (!Files.exists(obj)) {
            System.err.println("input OBJ not found: " + obj);
            System.exit(1);
        }

        long t0 = System.nanoTime();
        ObjToGlb.convert(obj, glb, opts);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        long bytes = Files.size(glb);
        System.out.printf("[ObjToGlbCli] %s -> %s  (%d bytes, %d ms)%n",
                obj.getFileName(), glb.getFileName(), bytes, ms);
    }

    private static ObjToGlb.Axis parseAxis(String s) {
        return switch (s.toUpperCase()) {
            case "X" -> ObjToGlb.Axis.X;
            case "Y" -> ObjToGlb.Axis.Y;
            case "Z" -> ObjToGlb.Axis.Z;
            default  -> throw new IllegalArgumentException("axis must be X|Y|Z, got " + s);
        };

    }
}
