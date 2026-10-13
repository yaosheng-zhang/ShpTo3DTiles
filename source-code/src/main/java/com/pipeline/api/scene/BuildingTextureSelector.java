package com.pipeline.api.scene;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Picks the façade texture for a building from its usage + storey count, and
 * loads (and caches) the JPEG bytes from the classpath.
 *
 * <p>Mapping rules come from 建筑纹理/说明.docx. The texture file name IS the
 * "model name" in that doc; each maps to a building category. "分层" (layered)
 * means more than 10 storeys ("10层以上…+建筑分层"):
 *
 * <pre>
 *   category            不分层 (lc<=10)      分层 (lc>10)
 *   ------------------  ------------------  ------------------
 *   商业 (commercial)    dise_G1_5           dise_G1_dx_01
 *   工业 (industrial)    dise_G1_dx_06       dise_G1_dx_06
 *   公共 (public)        dise_G1_dx_05       dise_G1_dx_05
 *   居住/其他 (other)     db_sumo_bui_001     dise_G1_dx_02
 *   null / 未知 (fallback) db_sumo_bui_001    db_sumo_bui_001  (白模纹理)
 * </pre>
 *
 * <p>Category is decided by keyword on {@code bld_usage}, checked in order:
 * 商业 → 工业 → 公共 → else. Note "公用设施" contains 公用 (not 公共) so it
 * correctly falls through to the 居住/其他 bucket, matching the doc's
 * "居住及排除上面类型".
 *
 * <p>The six JPEGs live under {@code src/main/resources/textures/}; they are
 * power-of-two, a few tens of KB each. Bytes are read once and cached, so the
 * convert pool shares the same immutable byte[] across all buildings.
 */
public final class BuildingTextureSelector {

    private static final Logger log = LoggerFactory.getLogger(BuildingTextureSelector.class);

    /** Storey count above which a building counts as 分层 (layered). */
    private static final int LAYERED_MIN_LC = 10;   // lc > 10  ⇒ layered

    // Texture resource stems (without .jpg) — exactly the file names shipped.
    private static final String TEX_COMMERCIAL          = "dise_G1_5";
    private static final String TEX_COMMERCIAL_LAYERED  = "dise_G1_dx_01";
    private static final String TEX_INDUSTRIAL          = "dise_G1_dx_06";
    private static final String TEX_PUBLIC              = "dise_G1_dx_05";
    private static final String TEX_OTHER               = "db_sumo_bui_001";
    private static final String TEX_OTHER_LAYERED       = "dise_G1_dx_02";

    private static final ConcurrentHashMap<String, byte[]> CACHE = new ConcurrentHashMap<>();

    private BuildingTextureSelector() {}

    /**
     * Resolve the texture stem (file name without extension) for a building.
     * Package-private so it can be unit-tested without touching the classpath.
     */
    public static String selectStem(String bldUsage, Integer lc) {
        boolean layered = lc != null && lc > LAYERED_MIN_LC;
        String u = bldUsage == null ? "" : bldUsage;

        if (u.contains("商业")) {
            return layered ? TEX_COMMERCIAL_LAYERED : TEX_COMMERCIAL;
        }
        if (u.contains("工业")) {
            return TEX_INDUSTRIAL;
        }
        if (u.contains("公共")) {
            return TEX_PUBLIC;
        }
        // 居住建筑 / 其他建筑 / 公用设施 / 交通设施 / 物流仓储 / null → 居住及排除上面类型
        return layered ? TEX_OTHER_LAYERED : TEX_OTHER;
    }

    /**
     * JPEG bytes of the texture chosen for {@code (bldUsage, lc)}. Returns the
     * fallback white texture's bytes on any classpath miss, and {@code null}
     * only if even the fallback is unreadable (in which case the caller should
     * fall back to the un-textured white model).
     */
    public static byte[] selectJpeg(String bldUsage, Integer lc) {
        String stem = selectStem(bldUsage, lc);
        byte[] bytes = load(stem);
        if (bytes == null && !TEX_OTHER.equals(stem)) {
            bytes = load(TEX_OTHER);   // last-ditch: the white texture
        }
        return bytes;
    }

    private static byte[] load(String stem) {
        return CACHE.computeIfAbsent(stem, BuildingTextureSelector::readResource);
    }

    /** Read /textures/<stem>.jpg from the classpath. Returns null on failure. */
    private static byte[] readResource(String stem) {
        String path = "/textures/" + stem + ".jpg";
        try (InputStream in = BuildingTextureSelector.class.getResourceAsStream(path)) {
            if (in == null) {
                log.warn("building texture not found on classpath: {}", path);
                return null;
            }
            return in.readAllBytes();
        } catch (IOException ex) {
            log.warn("failed to read building texture {}: {}", path, ex.toString());
            return null;
        }
    }
}
