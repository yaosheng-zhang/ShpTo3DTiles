package com.pipeline.api.scene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Batched 3D Model (b3dm) v1.0 packager.
 *
 * b3dm layout (all little-endian):
 *   header (28 B):
 *     magic[4]                = "b3dm"
 *     version (uint32)        = 1
 *     byteLength (uint32)     = total file size
 *     ftJsonByteLength        = feature-table JSON length
 *     ftBinaryByteLength      = feature-table binary length
 *     btJsonByteLength        = batch-table  JSON length
 *     btBinaryByteLength      = batch-table  binary length
 *   feature-table JSON       (8-byte aligned end, padded with spaces 0x20)
 *   feature-table binary     (8-byte aligned end, padded with 0x00)  — empty here
 *   batch-table  JSON        — empty here
 *   batch-table  binary      — empty here
 *   glb payload              (8-byte aligned end, padded with 0x00)
 *
 * The minimal valid feature table is {"BATCH_LENGTH":0}. Cesium / Mars3d
 * tolerate empty batch tables. Total file size must be 8-byte aligned.
 */
public final class B3dmWriter {

    private B3dmWriter() {}

    public static byte[] wrap(byte[] glb) {
        byte[] ftJsonRaw = "{\"BATCH_LENGTH\":0}".getBytes(StandardCharsets.UTF_8);

        // Feature-table JSON must end on an 8-byte boundary measured from
        // the start of the file (header is 28 B, not aligned by itself).
        int afterHeader = 28;
        int ftJsonLen   = align8(afterHeader + ftJsonRaw.length) - afterHeader;
        byte[] ftJson   = new byte[ftJsonLen];
        System.arraycopy(ftJsonRaw, 0, ftJson, 0, ftJsonRaw.length);
        for (int i = ftJsonRaw.length; i < ftJsonLen; i++) ftJson[i] = 0x20;  // space

        // glb body — pad to align total file size to 8 bytes.
        int beforeGlb = afterHeader + ftJsonLen;
        int totalUnpad = beforeGlb + glb.length;
        int totalLen   = align8(totalUnpad);
        int glbPad     = totalLen - totalUnpad;

        ByteBuffer bb = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte)'b').put((byte)'3').put((byte)'d').put((byte)'m');
        bb.putInt(1);                 // version
        bb.putInt(totalLen);          // byteLength
        bb.putInt(ftJsonLen);         // featureTable JSON length
        bb.putInt(0);                 // featureTable binary length
        bb.putInt(0);                 // batchTable  JSON length
        bb.putInt(0);                 // batchTable  binary length
        bb.put(ftJson);
        bb.put(glb);
        if (glbPad > 0) {
            byte[] pad = new byte[glbPad];   // zero-initialised
            bb.put(pad);
        }
        return bb.array();
    }

    private static int align8(int n) {
        int rem = n & 7;
        return rem == 0 ? n : n + (8 - rem);
    }
}
