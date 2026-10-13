package com.pipeline.api.gltf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Top-level orchestrator. Equivalent to the `obj2gltf` CLI for our use case:
 *
 *   ObjToGlb.convert(objIn, glbOut, new Options())
 *     ≡ obj2gltf -i objIn -o glbOut -b
 *                --inputUpAxis Z --outputUpAxis Y --unlit
 *
 * Pipeline:
 *   1. ObjReader.read(obj)           — get raw mesh + mtllib reference
 *   2. MtlReader.read(resolvedMtl)   — get material colors (defaults to white
 *                                       if mtl missing or material unknown)
 *   3. axis rotation                 — vertices/normals from input-axis to
 *                                       output-axis (default Z-up → Y-up)
 *   4. GlbWriter.write(...)          — flatten + emit GLB
 *
 * The whole pipeline is in-process Java; no Node, no subprocess. Each call
 * takes O(verts + faces) memory, no shared mutable state, safe to call from
 * a thread pool.
 */
public final class ObjToGlb {

    public enum Axis { X, Y, Z }

    public static final class Options {
        public Axis    inputAxis  = Axis.Z;
        public Axis    outputAxis = Axis.Y;
        public boolean unlit      = false;
        public boolean generateNormalsIfMissing = true;
        /** Force flat (face) normals — see {@link GlbWriter.Options#forceFlat}. */
        public boolean forceFlat  = false;
        /** Emit CESIUM_primitive_outline hard edges — see
         *  {@link GlbWriter.Options#outlineEdges}. */
        public boolean outlineEdges = false;
        /** Dihedral threshold for outlining — see
         *  {@link GlbWriter.Options#outlineAngleDeg}. */
        public double  outlineAngleDeg = 30.0;
        public String  generator  = "pipeline_v2 JavaGlb";
        /** If non-null, used as the mtl file regardless of OBJ's mtllib line.
         *  Useful when the mtllib reference is corrupted (encoding garbage
         *  in road OBJ exports) but the mtl is known to live next to the OBJ. */
        public Path    mtlOverride;

        // ---- façade texturing (buildings) — pass-through to GlbWriter ----
        /** JPEG bytes of the per-type façade texture to embed (null = none). */
        public byte[]  baseColorTextureJpeg;
        public String   baseColorTextureUri;
        /** Emit TEXCOORD_0 from vertex position (needed for the texture). */
        public boolean generateUv = false;
        /** Building base elevation (original-z). After the Z-up→Y-up rotation
         *  this equals the rotated up (Y) value, which is what GlbWriter reads. */
        public double  uvUpMin = 0.0;
        /** Metres of height per vertical texture tile (one floor band). */
        public double  uvFloorHeight = 3.0;
        /** Metres per horizontal texture tile (cosmetic). */
        public double  uvTileWidth = 4.0;
        public double  uvScaleU = 1.0, uvScaleV = 1.0;
        public double  uvOffsetU = 0.0, uvOffsetV = 0.0;
        public boolean uvFractional = false;
    }

    private ObjToGlb() {}

    public static void convert(Path objIn, Path glbOut) throws IOException {
        convert(objIn, glbOut, new Options());
    }

    public static void convert(Path objIn, Path glbOut, Options opts) throws IOException {
        if (opts == null) opts = new Options();

        Mesh mesh = ObjReader.read(objIn);

        // Resolve mtl: explicit override > OBJ's mtllib (relative to OBJ dir).
        Map<String, Material> materials = Map.of();
        Path mtlPath = opts.mtlOverride;
        if (mtlPath == null && mesh.mtllib != null && !mesh.mtllib.isBlank()) {
            Path candidate = objIn.toAbsolutePath().getParent().resolve(mesh.mtllib);
            if (Files.exists(candidate)) {
                mtlPath = candidate;
            }
        }
        if (mtlPath != null && Files.exists(mtlPath)) {
            materials = MtlReader.read(mtlPath);
        }

        // Axis rotation — applied in place to positions and normals.
        if (opts.inputAxis != opts.outputAxis) {
            applyAxisRotation(mesh.positions, opts.inputAxis, opts.outputAxis);
            applyAxisRotation(mesh.normals,   opts.inputAxis, opts.outputAxis);
        }

        GlbWriter.Options w = new GlbWriter.Options();
        w.unlit                     = opts.unlit;
        w.generateNormalsIfMissing  = opts.generateNormalsIfMissing;
        w.forceFlat                 = opts.forceFlat;
        w.outlineEdges              = opts.outlineEdges;
        w.outlineAngleDeg           = opts.outlineAngleDeg;
        w.generator                 = opts.generator;
        // Texture / UV pass-through. uvUpAxisIndex is derived from outputAxis
        // (not a free option) so it can never drift out of sync with the
        // rotation applied above.
        w.baseColorTextureJpeg      = opts.baseColorTextureJpeg;
        w.baseColorTextureUri       = opts.baseColorTextureUri;
        w.generateUv                = opts.generateUv;
        w.uvUpMin                   = opts.uvUpMin;
        w.uvFloorHeight             = opts.uvFloorHeight;
        w.uvTileWidth               = opts.uvTileWidth;
        w.uvScaleU                  = opts.uvScaleU;
        w.uvScaleV                  = opts.uvScaleV;
        w.uvOffsetU                 = opts.uvOffsetU;
        w.uvOffsetV                 = opts.uvOffsetV;
        w.uvFractional               = opts.uvFractional;
        w.uvUpAxisIndex             = axisIndex(opts.outputAxis);
        GlbWriter.write(mesh, materials, glbOut, w);
    }

    /** Map an Axis to its position-component index (X=0, Y=1, Z=2). */
    private static int axisIndex(Axis a) {
        return switch (a) {
            case X -> 0;
            case Y -> 1;
            case Z -> 2;
        };
    }

    /* ============== axis transforms ============== */

    /**
     * Rotate every entry in {@code data} from one canonical up-axis to another.
     * Only the six combinations that matter for our pipeline are implemented
     * — same-axis is a no-op handled by the caller, and the obj2gltf-style
     * rotations are listed below explicitly so the matrix can be eyeballed.
     *
     * All rotations are proper (det = +1) so triangle winding order is
     * preserved and we never need to flip face indices.
     *
     * Z-up → Y-up :   (x, y, z) → (x,  z, -y)   (rotate -90° around X)
     * Y-up → Z-up :   (x, y, z) → (x, -z,  y)   (rotate +90° around X)
     * Z-up → X-up :   (x, y, z) → (z,  y, -x)
     * X-up → Z-up :   (x, y, z) → (-z, y,  x)
     * Y-up → X-up :   (x, y, z) → (y, -x,  z)
     * X-up → Y-up :   (x, y, z) → (-y, x,  z)
     */
    private static void applyAxisRotation(List<float[]> data, Axis in, Axis out) {
        if (in == out) return;
        for (float[] v : data) {
            float x = v[0], y = v[1], z = v[2];
            switch (in) {
                case Z -> {
                    switch (out) {
                        case Y -> { v[0] = x;  v[1] = z;  v[2] = -y; }
                        case X -> { v[0] = z;  v[1] = y;  v[2] = -x; }
                        default -> { /* same axis, unreachable */ }
                    }
                }
                case Y -> {
                    switch (out) {
                        case Z -> { v[0] = x;  v[1] = -z; v[2] = y;  }
                        case X -> { v[0] = y;  v[1] = -x; v[2] = z;  }
                        default -> { /* same axis */ }
                    }
                }
                case X -> {
                    switch (out) {
                        case Z -> { v[0] = -z; v[1] = y;  v[2] = x;  }
                        case Y -> { v[0] = -y; v[1] = x;  v[2] = z;  }
                        default -> { /* same axis */ }
                    }
                }
            }
        }
    }
}
