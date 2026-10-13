package com.pipeline.api.gltf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory representation of a parsed OBJ file.
 *
 *   positions / normals — raw vertex data exactly as it appears in the OBJ
 *                          (no axis flip, no shifting). Each entry is float[3].
 *   groups              — faces partitioned by usemtl name, preserving OBJ order.
 *
 * The OBJ index space is 1-based; we store indices 0-based here. A vertex
 * index of -1 means "no normal/texcoord supplied" (OBJ uses `f a//c` syntax).
 *
 * The GlbWriter consumes this and emits one glTF primitive per material group.
 */
public final class Mesh {

    public final List<float[]> positions = new ArrayList<>();
    public final List<float[]> normals   = new ArrayList<>();

    /** A single triangle. v[i], n[i] are 0-based indices into positions/normals;
     *  n[i] == -1 means "no normal in OBJ — caller should compute a face normal". */
    public static final class Tri {
        public final int v0, v1, v2;
        public final int n0, n1, n2;
        public Tri(int v0, int v1, int v2, int n0, int n1, int n2) {
            this.v0=v0; this.v1=v1; this.v2=v2;
            this.n0=n0; this.n1=n1; this.n2=n2;
        }
    }

    /** Material group keeps insertion order so the output primitive order
     *  matches the OBJ — useful when diffing against obj2gltf output. */
    public final Map<String, List<Tri>> groups = new LinkedHashMap<>();

    /** The mtllib reference declared in the OBJ (or null if none). */
    public String mtllib;

    public List<Tri> groupFor(String material) {
        return groups.computeIfAbsent(material, k -> new ArrayList<>());
    }

    public int triangleCount() {
        int n = 0;
        for (List<Tri> g : groups.values()) n += g.size();
        return n;
    }
}
