package com.pipeline.api.gltf;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a parsed {@link Mesh} + materials to a glTF 2.0 Binary (GLB) file.
 *
 * Layout produced:
 *   - 1 buffer  (binary chunk)
 *   - bufferViews per primitive: positions (vec3 f32), normals (vec3 f32),
 *                                indices (scalar u32), and — when
 *                                {@code Options.generateUv} — uvs (vec2 f32)
 *   - matching accessors per primitive
 *   - 1 primitive  per OBJ material group
 *   - 1 mesh, 1 node, 1 scene
 *   - optionally: a single embedded JPEG ({@code Options.baseColorTextureJpeg})
 *                 packed at the tail of the buffer, referenced by 1 image +
 *                 1 REPEAT sampler + 1 texture; every material then carries a
 *                 baseColorTexture (texture 0) with a white baseColorFactor.
 *
 * Each primitive owns its own positions/normals — vertices are NOT shared
 * across material groups. This costs ~30% extra in file size vs maximal
 * dedup, but the writer stays simple and the output is independent per
 * material (matches the way obj2gltf groups things).
 *
 * Normals: if the OBJ supplied per-vertex normals (face token v/vt/vn or
 * v//vn) those are honoured; otherwise a flat per-face normal is generated
 * from the triangle cross product. Either way each vertex copy carries its
 * own normal, so the writer always emits the NORMAL attribute.
 *
 * Axis convention: the input is treated as the OBJ's native axis system.
 * The caller (ObjToGlb) decides whether to rotate Z-up → Y-up to match the
 * glTF spec; this writer does NOT touch positions/normals itself.
 *
 * KHR_materials_unlit is added per material when {@code unlit==true} on
 * write; this is what Cesium / Mars3D needs for a "constant-color" look
 * without baking lighting into the geometry.
 */
public final class GlbWriter {

    private static final int GLB_MAGIC      = 0x46546C67;  // "glTF" LE
    private static final int GLB_VERSION    = 2;
    private static final int CHUNK_TYPE_JSON = 0x4E4F534A;  // "JSON" LE
    private static final int CHUNK_TYPE_BIN  = 0x004E4942;  // "BIN\0" LE

    // glTF accessor.componentType
    private static final int CT_F32 = 5126;
    private static final int CT_U32 = 5125;

    // glTF bufferView.target
    private static final int TARGET_ARRAY_BUFFER         = 34962;
    private static final int TARGET_ELEMENT_ARRAY_BUFFER = 34963;

    private GlbWriter() {}

    public static final class Options {
        public boolean unlit = true;
        public boolean generateNormalsIfMissing = true;
        /** Always compute face normals from the cross product, ignoring any
         *  per-vertex normals supplied by the OBJ.  Use this for architectural
         *  white models where the source OBJ carries smooth (averaged) normals
         *  that would otherwise hide building edges under PBR lighting. */
        public boolean forceFlat = false;
        /** Emit the CESIUM_primitive_outline extension so Cesium draws hard
         *  outline lines along the model's feature edges (building棱角). The
         *  edges are detected per primitive: every boundary edge plus every
         *  edge whose two adjacent faces meet at more than {@link #outlineAngleDeg}.
         *  Coplanar triangulation diagonals (flat caps, split wall quads) and
         *  near-tangent edges (rounded parapet insets) are left un-outlined, so
         *  the result is a clean architectural wireframe rather than a full
         *  triangle mesh. Independent of lighting / unlit. */
        public boolean outlineEdges = false;
        /** Dihedral angle (degrees) above which a shared edge counts as a hard
         *  feature edge to outline. 30° cleanly separates orthogonal building
         *  corners (90°) from faceted rounded surfaces (~11° per segment). */
        public double  outlineAngleDeg = 30.0;
        public String  generator = "pipeline_v2 JavaGlb";

        /** When non-null/non-empty, the JPEG bytes are embedded once in the
         *  binary buffer and every material's pbrMetallicRoughness gets a
         *  baseColorTexture pointing at it (texture 0), with baseColorFactor
         *  forced to white so the texture colour shows unmodulated. Requires
         *  {@link #generateUv}=true to be visible. */
        public byte[]  baseColorTextureJpeg = null;
        /** External image URI. When set, the image is referenced outside GLB. */
        public String   baseColorTextureUri = null;
        /** Emit a per-vertex TEXCOORD_0 attribute computed from position (see
         *  the uv* fields). Off for the un-textured white model / roads. */
        public boolean generateUv = false;
        /** Index of the vertical (up) axis in the POST-rotation positions this
         *  writer receives. ObjToGlb rotates Z-up→Y-up, so up = Y = 1. V is
         *  measured along this axis; the other two axes feed U. */
        public int     uvUpAxisIndex = 1;
        /** Up-axis value mapped to V=0 (the building base elevation). */
        public double  uvUpMin = 0.0;
        /** Metres of height per one vertical texture tile. With wrapT=REPEAT a
         *  layered texture's top band then repeats once per floor. */
        public double  uvFloorHeight = 3.0;
        /** Metres per horizontal texture tile (cosmetic — the textures are
         *  horizontally uniform, so the exact value only sets texel scale). */
        public double  uvTileWidth = 4.0;
        public double  uvScaleU = 1.0, uvScaleV = 1.0;
        public double  uvOffsetU = 0.0, uvOffsetV = 0.0;
        public boolean uvFractional = false;
    }

    public static void write(Mesh mesh, Map<String, Material> materials,
                             Path out, Options opts) throws IOException {
        if (opts == null) opts = new Options();

        // Step 1: build per-primitive blobs.
        List<PrimBlob> prims = new ArrayList<>(mesh.groups.size());
        List<String>   matNames = new ArrayList<>(mesh.groups.size());
        for (Map.Entry<String, List<Mesh.Tri>> e : mesh.groups.entrySet()) {
            String matName = e.getKey();
            List<Mesh.Tri> tris = e.getValue();
            if (tris.isEmpty()) continue;
            prims.add(buildBlob(mesh, tris, opts));
            matNames.add(matName);
        }
        if (prims.isEmpty()) {
            // Degenerate input — empty OBJ. Emit a valid but empty glb.
            writeEmpty(out, opts);
            return;
        }

        // Step 2: pack all blobs into one binary buffer; record per-prim offsets.
        // Layout per primitive (in this exact order, each chunk 4-byte aligned):
        //   positions  (vec3 f32) — count * 12 bytes
        //   normals    (vec3 f32) — count * 12 bytes
        //   indices    (u32)      — index count * 4 bytes
        //   outline    (u32)      — outline-index count * 4 bytes   (optional)
        // 4-byte padding between primitives so accessor offsets stay aligned.
        int totalBin = 0;
        for (PrimBlob b : prims) {
            b.binOffsetPositions = totalBin;
            totalBin += b.positions.length * 4;
            b.binOffsetNormals = totalBin;
            totalBin += b.normals.length * 4;
            if (b.uvs != null) {
                b.binOffsetUv = totalBin;
                totalBin += b.uvs.length * 4;
            }
            b.binOffsetIndices = totalBin;
            totalBin += b.indices.length * 4;
            if (b.outlineIndices != null && b.outlineIndices.length > 0) {
                b.binOffsetOutline = totalBin;
                totalBin += b.outlineIndices.length * 4;
            }
            totalBin = align(totalBin, 4);
        }
        // Texture image bytes (if any) go once after all geometry, 4-byte
        // aligned at the START. The JPEG tail is NOT padded here — step 4's
        // container-level binPad rounds the whole BIN chunk up to 4 bytes, so
        // buffers[0].byteLength stays exactly the last bufferView's end.
        int textureBinOffset = -1;
        byte[] textureJpeg = opts.baseColorTextureJpeg;
        boolean hasTexture = textureJpeg != null && textureJpeg.length > 0;
        if (hasTexture) {
            totalBin = align(totalBin, 4);
            textureBinOffset = totalBin;
            totalBin += textureJpeg.length;
        }

        ByteBuffer bin = ByteBuffer.allocate(totalBin).order(ByteOrder.LITTLE_ENDIAN);
        for (PrimBlob b : prims) {
            bin.position(b.binOffsetPositions);
            for (float v : b.positions) bin.putFloat(v);
            bin.position(b.binOffsetNormals);
            for (float v : b.normals) bin.putFloat(v);
            if (b.uvs != null) {
                bin.position(b.binOffsetUv);
                for (float v : b.uvs) bin.putFloat(v);
            }
            bin.position(b.binOffsetIndices);
            for (int  v : b.indices)  bin.putInt(v);
            if (b.outlineIndices != null && b.outlineIndices.length > 0) {
                bin.position(b.binOffsetOutline);
                for (int v : b.outlineIndices) bin.putInt(v);
            }
        }
        if (hasTexture) {
            bin.position(textureBinOffset);
            bin.put(textureJpeg);
        }
        byte[] binBytes = bin.array();

        // Step 3: build the glTF JSON.
        Map<String, Object> gltf = buildGltf(prims, matNames, materials,
                binBytes.length, textureBinOffset, opts);

        ObjectMapper om = new ObjectMapper();
        byte[] jsonBytes = om.writeValueAsBytes(gltf);

        // Step 4: assemble the GLB container.
        // GLB header (12 B) + JSON chunk (8 B header + padded JSON) + BIN chunk (8 B header + padded BIN)
        int jsonPad = (4 - (jsonBytes.length & 3)) & 3;
        int binPad  = (4 - (binBytes.length  & 3)) & 3;
        int totalLen = 12
                + 8 + jsonBytes.length + jsonPad
                + 8 + binBytes.length  + binPad;

        try (OutputStream os = Files.newOutputStream(out)) {
            ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(GLB_MAGIC).putInt(GLB_VERSION).putInt(totalLen);
            os.write(header.array());

            // JSON chunk (padded with 0x20 spaces per spec)
            ByteBuffer jsonHdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            jsonHdr.putInt(jsonBytes.length + jsonPad).putInt(CHUNK_TYPE_JSON);
            os.write(jsonHdr.array());
            os.write(jsonBytes);
            for (int i = 0; i < jsonPad; i++) os.write(0x20);

            // BIN chunk (padded with 0x00 per spec)
            ByteBuffer binHdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            binHdr.putInt(binBytes.length + binPad).putInt(CHUNK_TYPE_BIN);
            os.write(binHdr.array());
            os.write(binBytes);
            for (int i = 0; i < binPad; i++) os.write(0x00);
        }
    }

    /* ============== blob builder: triangles → packed arrays ============== */

    private static final class PrimBlob {
        float[] positions;
        float[] normals;
        int[]   indices;
        /** Per-vertex UVs (u,v interleaved), length == vertexCount*2. Null when
         *  Options.generateUv is false. */
        float[] uvs;
        /** Feature-edge index pairs for CESIUM_primitive_outline; null/empty
         *  when outlining is off or the primitive has no hard edges. */
        int[]   outlineIndices;
        float[] posMin = new float[]{Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        float[] posMax = new float[]{Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        int vertexCount;
        int binOffsetPositions;
        int binOffsetNormals;
        int binOffsetUv;
        int binOffsetIndices;
        int binOffsetOutline;
    }

    /** Exact packed attribute identity. UVs are position-derived by this writer,
     * so matching position + normal also guarantees a matching UV. */
    private record VertexKey(int px, int py, int pz, int nx, int ny, int nz) {
        static VertexKey of(float[] p, float[] n) {
            return new VertexKey(Float.floatToIntBits(p[0]), Float.floatToIntBits(p[1]),
                    Float.floatToIntBits(p[2]), Float.floatToIntBits(n[0]),
                    Float.floatToIntBits(n[1]), Float.floatToIntBits(n[2]));
        }
    }

    /**
     * Expand a triangle list into packed positions/normals/indices. For ordinary
     * meshes, identical position/normal tuples are shared; this preserves flat
     * shading while avoiding three full vertex records per triangle. The outline
     * path keeps per-face vertices because its edge extension refers to both sides.
     *
     * When {@code opts.outlineEdges} is set, feature edges are detected in the
     * same pass and returned as {@link PrimBlob#outlineIndices} (vertex-index
     * pairs) for the CESIUM_primitive_outline extension. Because vertices are
     * duplicated per face, edges are matched by quantised position (a "spatial
     * id") so the two copies of a shared edge are recognised as one physical
     * edge; both copies' index pairs are emitted so the crease is outlined on
     * both adjacent faces.
     */
    private static PrimBlob buildBlob(Mesh mesh, List<Mesh.Tri> tris, Options opts) {
        int triCount = tris.size();
        int vCount   = triCount * 3;
        PrimBlob b = new PrimBlob();
        b.positions = new float[vCount * 3];
        b.normals   = new float[vCount * 3];
        b.indices   = new int[vCount];
        b.vertexCount = vCount;
        if (opts.generateUv) b.uvs = new float[vCount * 2];

        final boolean outlining = opts.outlineEdges;
        if (!outlining) b.vertexCount = 0;
        double angle = (opts.outlineAngleDeg <= 0 || opts.outlineAngleDeg >= 180)
                     ? 30.0 : opts.outlineAngleDeg;
        final double cosThresh = Math.cos(Math.toRadians(angle));
        HashMap<String, Integer>   spatialId = outlining ? new HashMap<>(vCount * 2) : null;
        HashMap<Long, EdgeAccum>   edges     = outlining ? new HashMap<>(vCount * 3) : null;

        int vi = 0;
        int ii = 0;
        Map<VertexKey, Integer> vertexIds = outlining ? null
                : new HashMap<>(Math.max(16, Math.min(vCount, 65_536)));
        for (Mesh.Tri t : tris) {
            float[] p0 = safeGet(mesh.positions, t.v0);
            float[] p1 = safeGet(mesh.positions, t.v1);
            float[] p2 = safeGet(mesh.positions, t.v2);
            if (p0 == null || p1 == null || p2 == null) {
                // Out-of-range vertex index — skip degenerate triangle.
                continue;
            }

            // Geometric face normal — used for flat shading AND (always, when
            // outlining) for the dihedral feature-edge test, independent of any
            // smooth normals the OBJ may carry.
            float[] fn = faceNormal(p0, p1, p2);

            // Decide per-vertex normals: OBJ-supplied if present, else flat.
            float[] n0, n1, n2;
            if (!opts.forceFlat && t.n0 >= 0 && t.n1 >= 0 && t.n2 >= 0
                    && t.n0 < mesh.normals.size()
                    && t.n1 < mesh.normals.size()
                    && t.n2 < mesh.normals.size()) {
                n0 = mesh.normals.get(t.n0);
                n1 = mesh.normals.get(t.n1);
                n2 = mesh.normals.get(t.n2);
            } else if (opts.forceFlat || opts.generateNormalsIfMissing) {
                n0 = n1 = n2 = fn;
            } else {
                n0 = n1 = n2 = new float[]{0, 0, 1};
            }

            n0 = normalize(n0);
            n1 = normalize(n1);
            n2 = normalize(n2);

            int i0, i1, i2;
            if (outlining) {
                i0 = vi; writeVertex(b, vi, p0, n0); if (b.uvs != null) fillUv(b, vi, p0, opts); vi++;
                i1 = vi; writeVertex(b, vi, p1, n1); if (b.uvs != null) fillUv(b, vi, p1, opts); vi++;
                i2 = vi; writeVertex(b, vi, p2, n2); if (b.uvs != null) fillUv(b, vi, p2, opts); vi++;
            } else {
                i0 = vertexIndex(vertexIds, b, p0, n0, opts);
                i1 = vertexIndex(vertexIds, b, p1, n1, opts);
                i2 = vertexIndex(vertexIds, b, p2, n2, opts);
            }
            b.indices[ii++] = i0; b.indices[ii++] = i1; b.indices[ii++] = i2;

            if (outlining) {
                int s0 = spatialIdOf(spatialId, p0);
                int s1 = spatialIdOf(spatialId, p1);
                int s2 = spatialIdOf(spatialId, p2);
                accumEdge(edges, s0, s1, i0, i1, fn);
                accumEdge(edges, s1, s2, i1, i2, fn);
                accumEdge(edges, s2, s0, i2, i0, fn);
            }
        }
        if (outlining) b.vertexCount = vi;
        // If we skipped triangles above the trailing slots are junk — trim.
        if (b.vertexCount != vCount || ii != vCount) {
            b.positions = trim(b.positions, b.vertexCount * 3);
            b.normals   = trim(b.normals,   b.vertexCount * 3);
            b.indices   = trim(b.indices,   ii);
            if (b.uvs != null) b.uvs = trim(b.uvs, b.vertexCount * 2);
        }

        if (outlining) {
            b.outlineIndices = collectOutline(edges, cosThresh);
        }
        return b;
    }

    private static int vertexIndex(Map<VertexKey, Integer> ids, PrimBlob b,
                                   float[] position, float[] normal, Options opts) {
        VertexKey key = VertexKey.of(position, normal);
        Integer found = ids.get(key);
        if (found != null) return found;
        int index = b.vertexCount++;
        ids.put(key, index);
        writeVertex(b, index, position, normal);
        if (b.uvs != null) fillUv(b, index, position, opts);
        return index;
    }
    private static float[] normalize(float[] v) {
        float len = (float)Math.sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
        if (len == 0) return new float[]{0, 0, 1};
        return new float[]{v[0]/len, v[1]/len, v[2]/len};
    }

    /**
     * Compute and store the UV of one (already-written) vertex from its
     * position. V runs along the up axis in floor-height units so a layered
     * texture's top band repeats once per storey under wrapT=REPEAT; U is the
     * sum of the two horizontal axes (the textures are horizontally uniform,
     * so U only sets texel scale, never appearance).
     */
    private static void fillUv(PrimBlob b, int vi, float[] p, Options opts) {
        int up = opts.uvUpAxisIndex;
        float v = (float) ((p[up] - opts.uvUpMin) / opts.uvFloorHeight);
        float u = 0f;
        for (int a = 0; a < 3; a++) if (a != up) u += p[a];
        u = (float) (u / opts.uvTileWidth);
        if (opts.uvFractional) {
            u -= Math.floor(u);
            v -= Math.floor(v);
        }
        b.uvs[vi * 2]     = (float) (u * opts.uvScaleU + opts.uvOffsetU);
        b.uvs[vi * 2 + 1] = (float) (v * opts.uvScaleV + opts.uvOffsetV);
    }
    /* ============== feature-edge detection (CESIUM_primitive_outline) ============== */

    /**
     * Accumulator for one physical edge: which faces touch it (up to two are
     * tracked for the dihedral test), the index pair to emit for each touching
     * face, and whether more than two faces share it (non-manifold).
     */
    private static final class EdgeAccum {
        int count = 0;
        int ia0, ib0, ia1, ib1;                 // emitted index pair per face
        float n0x, n0y, n0z, n1x, n1y, n1z;     // first two face normals
        boolean manifoldBreak = false;          // > 2 faces share this edge

        void add(int a, int b, float[] n) {
            if (count == 0) {
                ia0 = a; ib0 = b; n0x = n[0]; n0y = n[1]; n0z = n[2];
            } else if (count == 1) {
                ia1 = a; ib1 = b; n1x = n[0]; n1y = n[1]; n1z = n[2];
            } else {
                manifoldBreak = true;
            }
            count++;
        }

        boolean isFeature(double cosThresh) {
            if (count == 1 || manifoldBreak) return true;   // boundary / non-manifold
            double dot = n0x * n1x + n0y * n1y + n0z * n1z; // count == 2
            return dot < cosThresh;                         // angle > threshold
        }
    }

    /** Quantise a position to 0.1 mm and assign it a stable per-primitive id,
     *  so coincident corners from neighbouring faces collapse to one id. */
    private static int spatialIdOf(HashMap<String, Integer> map, float[] p) {
        long qx = Math.round(p[0] * 10000.0);
        long qy = Math.round(p[1] * 10000.0);
        long qz = Math.round(p[2] * 10000.0);
        String key = qx + "," + qy + "," + qz;
        Integer id = map.get(key);
        if (id == null) { id = map.size(); map.put(key, id); }
        return id;
    }

    /** Record one triangle edge against its spatial (position-keyed) edge. */
    private static void accumEdge(HashMap<Long, EdgeAccum> edges,
                                  int sa, int sb, int ia, int ib, float[] n) {
        if (sa == sb) return;                       // zero-length / degenerate
        int lo = Math.min(sa, sb), hi = Math.max(sa, sb);
        long key = ((long) lo << 32) | (hi & 0xFFFFFFFFL);
        EdgeAccum e = edges.get(key);
        if (e == null) { e = new EdgeAccum(); edges.put(key, e); }
        e.add(ia, ib, n);
    }

    /** Flatten the feature edges to a u32 index-pair array. */
    private static int[] collectOutline(HashMap<Long, EdgeAccum> edges, double cosThresh) {
        ArrayList<int[]> pairs = new ArrayList<>();
        for (EdgeAccum e : edges.values()) {
            if (!e.isFeature(cosThresh)) continue;
            pairs.add(new int[]{e.ia0, e.ib0});         // outline on first face
            if (e.count >= 2) pairs.add(new int[]{e.ia1, e.ib1}); // and the other
        }
        int[] arr = new int[pairs.size() * 2];
        int j = 0;
        for (int[] p : pairs) { arr[j++] = p[0]; arr[j++] = p[1]; }
        return arr;
    }

    private static void writeVertex(PrimBlob b, int vi, float[] p, float[] n) {
        int pi = vi * 3;
        b.positions[pi]     = p[0];
        b.positions[pi + 1] = p[1];
        b.positions[pi + 2] = p[2];
        b.normals  [pi]     = n[0];
        b.normals  [pi + 1] = n[1];
        b.normals  [pi + 2] = n[2];
        if (p[0] < b.posMin[0]) b.posMin[0] = p[0];
        if (p[1] < b.posMin[1]) b.posMin[1] = p[1];
        if (p[2] < b.posMin[2]) b.posMin[2] = p[2];
        if (p[0] > b.posMax[0]) b.posMax[0] = p[0];
        if (p[1] > b.posMax[1]) b.posMax[1] = p[1];
        if (p[2] > b.posMax[2]) b.posMax[2] = p[2];
    }

    private static float[] faceNormal(float[] p0, float[] p1, float[] p2) {
        float ax = p1[0] - p0[0],  ay = p1[1] - p0[1],  az = p1[2] - p0[2];
        float bx = p2[0] - p0[0],  by = p2[1] - p0[1],  bz = p2[2] - p0[2];
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-12f) return new float[]{0, 0, 1};
        return new float[]{nx / len, ny / len, nz / len};
    }

    /* ============== glTF JSON tree ============== */

    private static Map<String, Object> buildGltf(List<PrimBlob> prims,
                                                 List<String>   matNames,
                                                 Map<String, Material> materials,
                                                 int binByteLength,
                                                 int textureBinOffset,
                                                 Options opts) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("asset", Map.of("version", "2.0", "generator", opts.generator));
        root.put("scene", 0);
        root.put("scenes", List.of(Map.of("nodes", List.of(0))));
        root.put("nodes",  List.of(Map.of("mesh",  0)));

        boolean textured = (opts.baseColorTextureJpeg != null
                && opts.baseColorTextureJpeg.length > 0 && textureBinOffset >= 0)
                || (opts.baseColorTextureUri != null && !opts.baseColorTextureUri.isBlank());

        // Materials — one per primitive, defaulting to white when name is unknown.
        List<Map<String, Object>> mats = new ArrayList<>(matNames.size());
        for (String name : matNames) {
            Material m = materials != null ? materials.get(name) : null;
            if (m == null) m = Material.defaultWhite(name == null ? "default" : name);
            Map<String, Object> mat = new LinkedHashMap<>();
            mat.put("name", m.name);
            Map<String, Object> pbr = new LinkedHashMap<>();
            if (textured) {
                // White factor so the texture's own colour shows unmodulated;
                // every segment of the building shares texture 0.
                pbr.put("baseColorFactor", new float[]{1f, 1f, 1f, 1f});
                pbr.put("baseColorTexture", Map.of("index", 0, "texCoord", 0));
            } else {
                pbr.put("baseColorFactor", new float[]{m.r, m.g, m.b, m.a});
            }
            pbr.put("metallicFactor",  0.0);
            pbr.put("roughnessFactor", 1.0);
            mat.put("pbrMetallicRoughness", pbr);
            mat.put("doubleSided", true);
            if (opts.unlit) {
                mat.put("extensions", Map.of("KHR_materials_unlit", Map.of()));
            }
            mats.add(mat);
        }
        root.put("materials", mats);
        List<String> extensionsUsed = new ArrayList<>(2);
        if (opts.unlit) extensionsUsed.add("KHR_materials_unlit");
        boolean anyOutline = false;
        for (PrimBlob b : prims) {
            if (b.outlineIndices != null && b.outlineIndices.length > 0) { anyOutline = true; break; }
        }
        if (anyOutline) extensionsUsed.add("CESIUM_primitive_outline");
        if (!extensionsUsed.isEmpty()) {
            root.put("extensionsUsed", extensionsUsed);
        }

        // Accessors & bufferViews — 3 per primitive.
        List<Map<String, Object>> accessors   = new ArrayList<>(prims.size() * 3);
        List<Map<String, Object>> bufferViews = new ArrayList<>(prims.size() * 3);
        List<Map<String, Object>> primitives  = new ArrayList<>(prims.size());

        for (int i = 0; i < prims.size(); i++) {
            PrimBlob b = prims.get(i);

            // bufferView for positions
            Map<String, Object> bvPos = new LinkedHashMap<>();
            bvPos.put("buffer", 0);
            bvPos.put("byteOffset", b.binOffsetPositions);
            bvPos.put("byteLength", b.positions.length * 4);
            bvPos.put("target", TARGET_ARRAY_BUFFER);
            bufferViews.add(bvPos);
            int posBvIdx = bufferViews.size() - 1;

            // bufferView for normals
            Map<String, Object> bvNrm = new LinkedHashMap<>();
            bvNrm.put("buffer", 0);
            bvNrm.put("byteOffset", b.binOffsetNormals);
            bvNrm.put("byteLength", b.normals.length * 4);
            bvNrm.put("target", TARGET_ARRAY_BUFFER);
            bufferViews.add(bvNrm);
            int nrmBvIdx = bufferViews.size() - 1;

            // bufferView for indices
            Map<String, Object> bvIdx = new LinkedHashMap<>();
            bvIdx.put("buffer", 0);
            bvIdx.put("byteOffset", b.binOffsetIndices);
            bvIdx.put("byteLength", b.indices.length * 4);
            bvIdx.put("target", TARGET_ELEMENT_ARRAY_BUFFER);
            bufferViews.add(bvIdx);
            int idxBvIdx = bufferViews.size() - 1;

            // bufferView for uvs (optional)
            int uvBvIdx = -1;
            if (b.uvs != null) {
                Map<String, Object> bvUv = new LinkedHashMap<>();
                bvUv.put("buffer", 0);
                bvUv.put("byteOffset", b.binOffsetUv);
                bvUv.put("byteLength", b.uvs.length * 4);
                bvUv.put("target", TARGET_ARRAY_BUFFER);
                bufferViews.add(bvUv);
                uvBvIdx = bufferViews.size() - 1;
            }

            // accessor for positions
            Map<String, Object> aPos = new LinkedHashMap<>();
            aPos.put("bufferView", posBvIdx);
            aPos.put("componentType", CT_F32);
            aPos.put("count", b.vertexCount);
            aPos.put("type", "VEC3");
            aPos.put("min", new float[]{b.posMin[0], b.posMin[1], b.posMin[2]});
            aPos.put("max", new float[]{b.posMax[0], b.posMax[1], b.posMax[2]});
            accessors.add(aPos);
            int posAccIdx = accessors.size() - 1;

            // accessor for normals
            Map<String, Object> aNrm = new LinkedHashMap<>();
            aNrm.put("bufferView", nrmBvIdx);
            aNrm.put("componentType", CT_F32);
            aNrm.put("count", b.vertexCount);
            aNrm.put("type", "VEC3");
            accessors.add(aNrm);
            int nrmAccIdx = accessors.size() - 1;

            // accessor for indices
            Map<String, Object> aIdx = new LinkedHashMap<>();
            aIdx.put("bufferView", idxBvIdx);
            aIdx.put("componentType", CT_U32);
            aIdx.put("count", b.indices.length);
            aIdx.put("type", "SCALAR");
            accessors.add(aIdx);
            int idxAccIdx = accessors.size() - 1;

            // accessor for uvs (optional)
            int uvAccIdx = -1;
            if (uvBvIdx >= 0) {
                Map<String, Object> aUv = new LinkedHashMap<>();
                aUv.put("bufferView", uvBvIdx);
                aUv.put("componentType", CT_F32);
                aUv.put("count", b.vertexCount);
                aUv.put("type", "VEC2");
                accessors.add(aUv);
                uvAccIdx = accessors.size() - 1;
            }

            // optional bufferView + accessor for CESIUM_primitive_outline edges
            int outlineAccIdx = -1;
            if (b.outlineIndices != null && b.outlineIndices.length > 0) {
                Map<String, Object> bvOut = new LinkedHashMap<>();
                bvOut.put("buffer", 0);
                bvOut.put("byteOffset", b.binOffsetOutline);
                bvOut.put("byteLength", b.outlineIndices.length * 4);
                // No target: Cesium loads these as a CPU typed array for edge
                // matching, not as a GPU element/array buffer binding.
                bufferViews.add(bvOut);
                int outBvIdx = bufferViews.size() - 1;

                Map<String, Object> aOut = new LinkedHashMap<>();
                aOut.put("bufferView", outBvIdx);
                aOut.put("componentType", CT_U32);
                aOut.put("count", b.outlineIndices.length);
                aOut.put("type", "SCALAR");
                accessors.add(aOut);
                outlineAccIdx = accessors.size() - 1;
            }

            // primitive
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("POSITION", posAccIdx);
            attrs.put("NORMAL",   nrmAccIdx);
            if (uvAccIdx >= 0) attrs.put("TEXCOORD_0", uvAccIdx);
            Map<String, Object> prim = new LinkedHashMap<>();
            prim.put("attributes", attrs);
            prim.put("indices",  idxAccIdx);
            prim.put("material", i);
            prim.put("mode",     4);  // TRIANGLES
            if (outlineAccIdx >= 0) {
                prim.put("extensions", Map.of(
                        "CESIUM_primitive_outline", Map.of("indices", outlineAccIdx)));
            }
            primitives.add(prim);
        }
        // Embedded base-color texture: one image bufferView (raw JPEG blob),
        // one REPEAT sampler (vertical tiling = floor bands), one texture.
        // Shared by every primitive's material via texture index 0.
        if (textured) {
            if (opts.baseColorTextureUri != null && !opts.baseColorTextureUri.isBlank()) {
                root.put("images", List.of(Map.of("uri", opts.baseColorTextureUri,
                        "mimeType", "image/jpeg")));
            } else {
            Map<String, Object> bvImg = new LinkedHashMap<>();
            bvImg.put("buffer", 0);
            bvImg.put("byteOffset", textureBinOffset);
            bvImg.put("byteLength", opts.baseColorTextureJpeg.length);
            // No target / no byteStride — an image bufferView is a CPU blob.
            bufferViews.add(bvImg);
            int imgBvIdx = bufferViews.size() - 1;

            root.put("images",   List.of(Map.of(
                    "bufferView", imgBvIdx, "mimeType", "image/jpeg")));
            }
            root.put("samplers", List.of(Map.of(
                    "wrapS", 33071, "wrapT", 33071,   // CLAMP_TO_EDGE for atlas cells
                    "magFilter", 9729,                // LINEAR
                    "minFilter", 9729)));             // LINEAR, no mip seam
            root.put("textures", List.of(Map.of("source", 0, "sampler", 0)));
        }

        root.put("accessors",   accessors);
        root.put("bufferViews", bufferViews);
        root.put("meshes",      List.of(Map.of("primitives", primitives)));
        root.put("buffers",     List.of(Map.of("byteLength", binByteLength)));
        return root;
    }

    /* ============== utilities ============== */

    private static int align(int n, int boundary) {
        int rem = n % boundary;
        return rem == 0 ? n : n + (boundary - rem);
    }

    private static float[] safeGet(List<float[]> list, int i) {
        if (i < 0 || i >= list.size()) return null;
        return list.get(i);
    }

    private static float[] trim(float[] a, int len) {
        if (a.length == len) return a;
        float[] r = new float[len];
        System.arraycopy(a, 0, r, 0, len);
        return r;
    }

    private static int[] trim(int[] a, int len) {
        if (a.length == len) return a;
        int[] r = new int[len];
        System.arraycopy(a, 0, r, 0, len);
        return r;
    }

    /** Write a valid glb that contains no geometry — used when the OBJ
     *  parsed without any triangles. Cesium tolerates an empty mesh array. */
    private static void writeEmpty(Path out, Options opts) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("asset", Map.of("version", "2.0", "generator", opts.generator));
        root.put("scene", 0);
        root.put("scenes", List.of(Map.of("nodes", List.of(0))));
        root.put("nodes",  List.of(Map.of()));
        ObjectMapper om = new ObjectMapper();
        byte[] jsonBytes = om.writeValueAsBytes(root);
        int jsonPad = (4 - (jsonBytes.length & 3)) & 3;
        int totalLen = 12 + 8 + jsonBytes.length + jsonPad;
        try (OutputStream os = Files.newOutputStream(out)) {
            ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(GLB_MAGIC).putInt(GLB_VERSION).putInt(totalLen);
            os.write(header.array());
            ByteBuffer jsonHdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            jsonHdr.putInt(jsonBytes.length + jsonPad).putInt(CHUNK_TYPE_JSON);
            os.write(jsonHdr.array());
            os.write(jsonBytes);
            for (int i = 0; i < jsonPad; i++) os.write(0x20);
        }
    }
}
