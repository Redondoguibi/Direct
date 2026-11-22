package com.redondoguibi.direct;

import java.nio.ByteBuffer;

final class TerrainIndexPacker {
    private static final int[] QUAD_INDEX_PATTERN = {0, 1, 2, 2, 3, 0};

    private TerrainIndexPacker() {
    }

    static boolean hasValidSequentialQuadIndices(int vertexCount, int indexCount) {
        return vertexCount >= 0 && indexCount >= 0
                && indexCount % QUAD_INDEX_PATTERN.length == 0
                && (long) (indexCount / QUAD_INDEX_PATTERN.length) * 4L <= vertexCount;
    }

    static void packSequentialQuadIndices(ByteBuffer destination, int indexCount, int vertexBase) {
        if (indexCount < 0 || indexCount % QUAD_INDEX_PATTERN.length != 0) {
            throw new IllegalArgumentException(
                    "Sequential quad index count must be a non-negative multiple of six.");
        }
        if ((long) indexCount * Integer.BYTES > destination.remaining()) {
            throw new IllegalArgumentException("Destination buffer is too small for sequential quad indices.");
        }
        for (int index = 0; index < indexCount; index++) {
            int value = index / QUAD_INDEX_PATTERN.length * 4
                    + QUAD_INDEX_PATTERN[index % QUAD_INDEX_PATTERN.length];
            destination.putInt(value + vertexBase);
        }
    }
}
