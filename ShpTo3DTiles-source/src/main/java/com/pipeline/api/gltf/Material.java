package com.pipeline.api.gltf;

/**
 * RGBA color material parsed from an OBJ-style .mtl file.
 *
 * Only the diffuse channel (Kd) and alpha (d / Tr) are kept — that's all
 * the white-model rendering pipeline cares about. With KHR_materials_unlit
 * the glTF baseColorFactor is rendered as-is, so the linear Kd values
 * survive into the final scene without any tonemap / gamma surprises.
 */
public final class Material {
    public final String name;
    public final float r;
    public final float g;
    public final float b;
    public final float a;

    public Material(String name, float r, float g, float b, float a) {
        this.name = name;
        this.r = r;  this.g = g;  this.b = b;  this.a = a;
    }

    /** Plain white, full alpha — used when the OBJ references an unknown
     *  material name or the .mtl file can't be loaded. */
    public static Material defaultWhite(String name) {
        return new Material(name, 1.0f, 1.0f, 1.0f, 1.0f);
    }
}
