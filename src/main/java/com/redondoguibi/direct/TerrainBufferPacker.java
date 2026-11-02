package com.redondoguibi.direct;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Packs Minecraft block-format mesh buffers into Direct's compact vertex/index layout.
 */
final class TerrainBufferPacker {
    private static final int OUTPUT_VERTEX_SIZE = 16;
    private static final float POSITION_SCALE = 1024.0f;
    private static final float POSITION_ORIGIN = -16.0f;

    private TerrainBufferPacker() {
    }

    static void packVertices(ByteBuffer destination, ByteBuffer source, int vertexCount, int stride,
            int positionOffset, int colorOffset, int uvOffset) {
        packVertices(destination, source, vertexCount, stride, positionOffset, colorOffset, uvOffset, -1);
    }

    static void packVertices(ByteBuffer destination, ByteBuffer source, int vertexCount, int stride,
            int positionOffset, int colorOffset, int uvOffset, int lightOffset) {
        long sourceBytes = (long) vertexCount * stride;
        long destinationBytes = (long) vertexCount * OUTPUT_VERTEX_SIZE;
        if (vertexCount < 0 || stride <= 0 || positionOffset < 0 || colorOffset < 0 || uvOffset < 0
                || (long) positionOffset + 12 > stride || (long) colorOffset + 4 > stride
                || (long) uvOffset + 8 > stride || lightOffset < -1
                || (lightOffset >= 0 && (long) lightOffset + 4 > stride)
                || sourceBytes > source.remaining() || destinationBytes > destination.remaining()) {
            throw new IllegalArgumentException("Terrain vertex pack would exceed a buffer's remaining range.");
        }

        // Use bounded NIO reads/writes. Each layer advances the shared destination exactly once.
        ByteBuffer sourceVertices = source.duplicate().order(ByteOrder.nativeOrder());
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int offset = sourceVertices.position() + vertex * stride;
            destination.putShort(packPosition(sourceVertices.getFloat(offset + positionOffset)));
            destination.putShort(packPosition(sourceVertices.getFloat(offset + positionOffset + Float.BYTES)));
            destination.putShort(packPosition(sourceVertices.getFloat(offset + positionOffset + 2 * Float.BYTES)));
            int lightU = lightOffset < 0 ? 0 : Math.clamp(sourceVertices.getShort(offset + lightOffset), 0, 255);
            int lightV = lightOffset < 0 ? 0 : Math.clamp(sourceVertices.getShort(offset + lightOffset + 2), 0, 255);
            destination.putShort((short) (lightU | lightV << 8));
            destination.putInt(sourceVertices.getInt(offset + colorOffset));
            destination.putShort(packUnorm16(sourceVertices.getFloat(offset + uvOffset)));
            destination.putShort(packUnorm16(sourceVertices.getFloat(offset + uvOffset + Float.BYTES)));
        }
    }

    static void packIndices(ByteBuffer destination, ByteBuffer source, int indexCount, int indexSize, int vertexBase) {
        long sourceBytes = (long) indexCount * indexSize;
        long destinationBytes = (long) indexCount * Integer.BYTES;
        if (indexCount < 0 || (indexSize != Short.BYTES && indexSize != Integer.BYTES)
                || sourceBytes > source.remaining() || destinationBytes > destination.remaining()) {
            throw new IllegalArgumentException("Terrain index pack would exceed a buffer's remaining range.");
        }

        ByteBuffer sourceIndices = source.duplicate().order(ByteOrder.nativeOrder());
        for (int index = 0; index < indexCount; index++) {
            int sourceIndex = sourceIndices.position() + index * indexSize;
            int value = indexSize == Short.BYTES
                    ? Short.toUnsignedInt(sourceIndices.getShort(sourceIndex))
                    : sourceIndices.getInt(sourceIndex);
            destination.putInt(value + vertexBase);
        }
    }

    private static short packPosition(float value) {
        int packed = Math.round((value - POSITION_ORIGIN) * POSITION_SCALE);
        return (short) Math.max(0, Math.min(0xFFFF, packed));
    }

    private static short packUnorm16(float value) {
        return (short) Math.round(Math.max(0.0f, Math.min(1.0f, value)) * 0xFFFF);
    }
}
