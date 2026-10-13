package com.pipeline.api.gltf;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal MTL reader.
 *
 * Supports the OBJ-MTL fields the white-model pipeline uses:
 *   newmtl   — start a new material block
 *   Kd r g b — diffuse RGB (0..1)
 *   d        — opacity (0..1, 1=opaque)
 *   Tr       — transparency (0..1, alias 1-d)
 *
 * Everything else (Ka, Ks, Ns, illum, map_Kd, …) is silently ignored —
 * KHR_materials_unlit only needs baseColor, and our buildings don't have
 * textures anyway.
 *
 * Bytes are read as ISO-8859-1 so non-UTF-8 MTL files (3ds Max exports
 * occasionally embed GBK comments) don't blow up. Only ASCII `newmtl` /
 * `Kd` / `d` / `Tr` directives are parsed, and those are guaranteed ASCII.
 */
public final class MtlReader {

    private MtlReader() {}

    public static Map<String, Material> read(Path mtl) throws IOException {
        Map<String, Material> out = new LinkedHashMap<>();
        if (!Files.exists(mtl)) return out;

        String current = null;
        float r = 1, g = 1, b = 1, a = 1;

        try (BufferedReader br = Files.newBufferedReader(mtl, StandardCharsets.ISO_8859_1)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#') continue;

                // Tokenise on any whitespace; only first token is the directive.
                String[] tok = line.split("\\s+");
                String head = tok[0].toLowerCase(Locale.ROOT);

                switch (head) {
                    case "newmtl" -> {
                        if (current != null) {
                            out.put(current, new Material(current, r, g, b, a));
                        }
                        current = tok.length > 1 ? tok[1] : "default";
                        // reset to white opaque for the new block
                        r = 1; g = 1; b = 1; a = 1;
                    }
                    case "kd" -> {
                        if (tok.length >= 4) {
                            r = parseFloat(tok[1], r);
                            g = parseFloat(tok[2], g);
                            b = parseFloat(tok[3], b);
                        }
                    }
                    case "d" -> {
                        if (tok.length >= 2) a = parseFloat(tok[1], a);
                    }
                    case "tr" -> {
                        if (tok.length >= 2) a = 1.0f - parseFloat(tok[1], 0f);
                    }
                    default -> { /* skip everything else */ }
                }
            }
        }
        if (current != null) {
            out.put(current, new Material(current, r, g, b, a));
        }
        return out;
    }

    private static float parseFloat(String s, float fallback) {
        try { return Float.parseFloat(s); }
        catch (NumberFormatException ex) { return fallback; }
    }
}
