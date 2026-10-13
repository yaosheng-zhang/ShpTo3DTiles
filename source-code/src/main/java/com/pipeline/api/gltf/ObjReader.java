package com.pipeline.api.gltf;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Minimal OBJ reader sufficient for the white-model and road OBJs in this
 * project.
 *
 * Supported directives:
 *   v  x y z          — vertex position
 *   vn x y z          — vertex normal
 *   vt u v            — texcoord (parsed and discarded; index slot is preserved)
 *   f  v[/vt][/vn] …  — face (3+ vertices; quads / ngons are fan-triangulated
 *                      around vertex 0)
 *   o / g  name       — object / group (informational, ignored)
 *   usemtl  name      — switch the active material for following faces
 *   mtllib  path      — store relative mtl path (resolved by caller)
 *   #                 — comment (skipped)
 *
 * Unrecognised directives are silently skipped — many 3ds Max exports include
 * `s`, `g`, `l`, `mg`, … which would otherwise pollute logs.
 *
 * Bytes are read as ISO-8859-1 (byte-preserving) because road OBJs from 3ds
 * Max embed non-UTF-8 (GBK) bytes in comments and mtllib paths. Only the
 * numeric portion of v/vn/f lines is parsed, all of which is pure ASCII.
 *
 * OBJ uses 1-based indices including support for negative back-references
 * (`-1` = last vertex). Both are normalised to 0-based here.
 */
public final class ObjReader {

    private ObjReader() {}

    public static Mesh read(Path obj) throws IOException {
        Mesh m = new Mesh();
        String activeMaterial = "__default__";

        try (BufferedReader br = Files.newBufferedReader(obj, StandardCharsets.ISO_8859_1)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                char c0 = line.charAt(0);
                if (c0 == '#') continue;

                // Fast-path the hot directives first to avoid heavy tokenisation.
                if (c0 == 'v' && line.length() > 1) {
                    char c1 = line.charAt(1);
                    if (c1 == ' ' || c1 == '\t') {
                        parseV(line, m);
                        continue;
                    }
                    if (c1 == 'n' && line.length() > 2 && (line.charAt(2) == ' ' || line.charAt(2) == '\t')) {
                        parseVN(line, m);
                        continue;
                    }
                    // vt — skip, we don't care about texcoords for white models.
                    if (c1 == 't') continue;
                }
                if (c0 == 'f' && line.length() > 1 && (line.charAt(1) == ' ' || line.charAt(1) == '\t')) {
                    parseF(line, m, activeMaterial);
                    continue;
                }

                // Cold-path directives: tokenise on whitespace.
                String[] tok = line.trim().split("\\s+");
                if (tok.length == 0) continue;
                String head = tok[0].toLowerCase(Locale.ROOT);
                switch (head) {
                    case "usemtl" -> { if (tok.length > 1) activeMaterial = tok[1]; }
                    case "mtllib" -> {
                        // Join rest of line so paths containing spaces are kept whole.
                        if (tok.length > 1) {
                            int idx = line.indexOf(tok[0]) + tok[0].length();
                            m.mtllib = line.substring(idx).trim();
                        }
                    }
                    case "o", "g", "s", "l", "mg" -> { /* ignore */ }
                    default -> { /* unknown — ignore */ }
                }
            }
        }
        return m;
    }

    /* ============== line parsers ============== */

    private static void parseV(String line, Mesh m) {
        String[] t = split(line);
        if (t.length >= 4) {
            try {
                m.positions.add(new float[]{
                        Float.parseFloat(t[1]),
                        Float.parseFloat(t[2]),
                        Float.parseFloat(t[3]),
                });
            } catch (NumberFormatException ignored) { /* skip malformed */ }
        }
    }

    private static void parseVN(String line, Mesh m) {
        String[] t = split(line);
        if (t.length >= 4) {
            try {
                m.normals.add(new float[]{
                        Float.parseFloat(t[1]),
                        Float.parseFloat(t[2]),
                        Float.parseFloat(t[3]),
                });
            } catch (NumberFormatException ignored) { /* skip malformed */ }
        }
    }

    /**
     * Parse an `f` line. Vertices ≥ 3 supported; quads / ngons are fan-
     * triangulated around the first vertex.
     */
    private static void parseF(String line, Mesh m, String material) {
        String[] t = split(line);
        // tok[0]="f", verts at tok[1..]
        int nv = t.length - 1;
        if (nv < 3) return;

        int[] vi = new int[nv];
        int[] ni = new int[nv];
        int posCount = m.positions.size();
        int nrmCount = m.normals.size();
        for (int i = 0; i < nv; i++) {
            FaceVert fv = parseFaceVert(t[i + 1], posCount, nrmCount);
            if (fv == null) return;  // malformed; drop the whole face
            vi[i] = fv.v;
            ni[i] = fv.n;
        }

        List<Mesh.Tri> sink = m.groupFor(material);
        // Fan triangulation: (0,1,2), (0,2,3), (0,3,4)…
        for (int i = 1; i + 1 < nv; i++) {
            sink.add(new Mesh.Tri(vi[0], vi[i], vi[i + 1],
                                  ni[0], ni[i], ni[i + 1]));
        }
    }

    /** Parse one face vertex token of the form `v`, `v/vt`, `v//vn`, or `v/vt/vn`. */
    private static FaceVert parseFaceVert(String tok, int posCount, int nrmCount) {
        // Split on '/'. Empty middle slot is allowed (`v//vn`).
        int s1 = tok.indexOf('/');
        int s2 = s1 < 0 ? -1 : tok.indexOf('/', s1 + 1);
        String vS  = (s1 < 0) ? tok : tok.substring(0, s1);
        String vnS = (s2 < 0) ? null : tok.substring(s2 + 1);

        int v = parseObjIndex(vS, posCount);
        if (v < 0) return null;

        int n = -1;
        if (vnS != null && !vnS.isEmpty()) {
            n = parseObjIndex(vnS, nrmCount);
        }
        return new FaceVert(v, n);
    }

    /** Resolve a 1-based OBJ index, including negative back-references, to 0-based.
     *  Returns -1 on parse failure or out-of-range. */
    private static int parseObjIndex(String s, int count) {
        try {
            int raw = Integer.parseInt(s);
            int zero = (raw > 0) ? raw - 1 : count + raw;
            return (zero >= 0 && zero < count) ? zero : -1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /** Whitespace split that handles arbitrary tabs/spaces between tokens.
     *  String.split("\\s+") allocates a Pattern each call; this is the hot
     *  path of parsing so a tiny manual scanner is worth ~30% in benchmarks. */
    private static String[] split(String line) {
        int n = line.length();
        // Up to ~16 tokens per line; tune as needed.
        String[] tmp = new String[16];
        int count = 0;
        int i = 0;
        while (i < n) {
            // skip whitespace
            while (i < n && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
            if (i >= n) break;
            int start = i;
            while (i < n && line.charAt(i) != ' ' && line.charAt(i) != '\t') i++;
            if (count == tmp.length) {
                String[] grow = new String[tmp.length * 2];
                System.arraycopy(tmp, 0, grow, 0, tmp.length);
                tmp = grow;
            }
            tmp[count++] = line.substring(start, i);
        }
        String[] out = new String[count];
        System.arraycopy(tmp, 0, out, 0, count);
        return out;
    }

    private record FaceVert(int v, int n) {}
}
