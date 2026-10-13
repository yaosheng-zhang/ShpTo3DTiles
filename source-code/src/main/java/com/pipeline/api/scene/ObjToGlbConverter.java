package com.pipeline.api.scene;

import com.pipeline.api.gltf.ObjToGlb;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * In-process OBJ → GLB converter.
 *
 * Replaces the original Node-spawn implementation that shelled out to
 * obj2gltf.cmd. The Java equivalent lives in com.pipeline.api.gltf and was
 * verified against obj2gltf 3.2.0 on building / road / multi-material
 * fixtures: same triangle count, same per-material primitive grouping,
 * same baseColor, same world-space bounding box. File size is a touch
 * larger because we don't dedup vertices (flat-shading per face) — that's
 * a non-issue at our model sizes (≤ a few KB per b3dm).
 *
 * Why this rewrite exists:
 *  - eliminates ~300 ms Node cold-start per building (we spawned one
 *    process per OBJ in the old loop)
 *  - kills the external Node + obj2gltf dependency tree
 *  - thread-safe: no shared mutable state, safe to invoke from the
 *    convert-executor pool in SceneService.
 *
 * The signature (and bean name) is unchanged so SceneService and the
 * dependency-injection wiring keep working as-is.
 */
@Component
@Slf4j
public class ObjToGlbConverter {

    /** Pipeline-wide options. Mirrors the obj2gltf flags we used:
     *    --inputUpAxis Z --outputUpAxis Y --unlit
     *  Two flavours that differ only in outlining: buildings get
     *  CESIUM_primitive_outline so their棱角 (hard feature edges) are drawn as
     *  outline lines on top of the flat unlit white; roads do not (their 3ds
     *  Max surfaces would tessellate into noisy edge sets). Both are immutable
     *  after construction, so they're safe to share across the convert pool. */
    private final ObjToGlb.Options BUILDING_OPTS = makeOpts(false); //开关轮廓线
    private final ObjToGlb.Options ROAD_OPTS     = makeOpts(false);

    private static ObjToGlb.Options makeOpts(boolean outline) {
        ObjToGlb.Options o = new ObjToGlb.Options();
        o.inputAxis  = ObjToGlb.Axis.Z;
        o.outputAxis = ObjToGlb.Axis.Y;
        o.unlit      = false;
        o.generateNormalsIfMissing = true;
        o.outlineEdges    = outline;   // CESIUM_primitive_outline hard edges
        o.outlineAngleDeg = 60.0;
        o.generator  = "pipeline_v2 JavaGlb";
        return o;
    }

    /**
     * Convert {@code objIn} into a binary glTF (GLB) at {@code glbOut}.
     * Default path = building white model (hard-edge outlines on). Signature
     * preserved from the original Node-spawning version.
     */
    public void convert(Path objIn, Path glbOut) throws IOException {
        ObjToGlb.convert(objIn, glbOut, BUILDING_OPTS);
    }

    /**
     * Convert with explicit control over edge outlining. Roads pass
     * {@code outlineEdges=false} so only buildings carry CESIUM_primitive_outline.
     */
    public void convert(Path objIn, Path glbOut, boolean outlineEdges) throws IOException {
        ObjToGlb.convert(objIn, glbOut, outlineEdges ? BUILDING_OPTS : ROAD_OPTS);
    }

    /**
     * Convert a building white-model OBJ into a GLB with an embedded per-type
     * façade texture. UVs are generated so the texture tiles once per floor —
     * the two "分层" (layered) textures then render floor-division bands; the
     * solid textures look the same regardless of tiling.
     *
     * <p>A fresh {@link ObjToGlb.Options} is built per call because the texture
     * bytes and base elevation differ per building, so the shared immutable
     * {@code BUILDING_OPTS} singleton cannot be reused. When {@code textureJpeg}
     * is null/empty this falls back to the plain (un-textured) conversion.
     *
     * @param textureJpeg per-type texture bytes (see BuildingTextureSelector)
     * @param baseZ       building base elevation — original z, which equals the
     *                    rotated up (Y) value GlbWriter measures V against
     * @param floorHeight metres of height per vertical texture tile (one floor)
     * @param tileWidth   metres per horizontal texture tile (cosmetic)
     */
    public void convert(Path objIn, Path glbOut, byte[] textureJpeg,
                        double baseZ, double floorHeight, double tileWidth) throws IOException {
        if (textureJpeg == null || textureJpeg.length == 0) {
            convert(objIn, glbOut);   // no texture → plain white-model building
            return;
        }
        ObjToGlb.Options o = makeOpts(false);   // fresh per-call instance
        o.baseColorTextureJpeg = textureJpeg;
        o.generateUv    = true;
        o.uvUpMin       = baseZ;
        o.uvFloorHeight = floorHeight > 0 ? floorHeight : 3.0;
        o.uvTileWidth   = tileWidth   > 0 ? tileWidth   : 4.0;
        ObjToGlb.convert(objIn, glbOut, o);
    }

    /** Convert with a shared external atlas image and a normalized atlas cell. */
    public void convertAtlas(Path objIn, Path glbOut, String textureUri,
                             double baseZ, double floorHeight, double tileWidth,
                             double scaleU, double scaleV,
                             double offsetU, double offsetV) throws IOException {
        ObjToGlb.Options o = makeOpts(false);
        o.baseColorTextureUri = textureUri;
        o.generateUv = true;
        o.uvUpMin = baseZ;
        o.uvFloorHeight = floorHeight > 0 ? floorHeight : 3.0;
        o.uvTileWidth = tileWidth > 0 ? tileWidth : 4.0;
        o.uvScaleU = scaleU;
        o.uvScaleV = scaleV;
        o.uvOffsetU = offsetU;
        o.uvOffsetV = offsetV;
        o.uvFractional = true;
        ObjToGlb.convert(objIn, glbOut, o);
    }
}
