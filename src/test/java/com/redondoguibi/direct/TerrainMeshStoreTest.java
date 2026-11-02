package com.redondoguibi.direct;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

class TerrainMeshStoreTest {
    private static final int[] TWO_QUADS_WITH_BASE = {
            8, 9, 10, 10, 11, 8,
            12, 13, 14, 14, 15, 12
    };

    @Test
    void validatesSequentialQuadIndexCounts() {
        assertTrue(TerrainIndexPacker.hasValidSequentialQuadIndices(4, 6));
        assertTrue(TerrainIndexPacker.hasValidSequentialQuadIndices(8, 12));
        assertFalse(TerrainIndexPacker.hasValidSequentialQuadIndices(7, 12));
        assertFalse(TerrainIndexPacker.hasValidSequentialQuadIndices(4, 5));
        assertFalse(TerrainIndexPacker.hasValidSequentialQuadIndices(-1, 6));
    }

    @Test
    void packsSequentialIndicesIntoDirectBuffer() {
        ByteBuffer destination = ByteBuffer.allocateDirect(TWO_QUADS_WITH_BASE.length * Integer.BYTES)
                .order(ByteOrder.nativeOrder());

        TerrainIndexPacker.packSequentialQuadIndices(destination, 12, 8);

        assertEqualsIndices(destination, TWO_QUADS_WITH_BASE);
    }

    @Test
    void packsSequentialIndicesIntoHeapBuffer() {
        ByteBuffer destination = ByteBuffer.allocate(TWO_QUADS_WITH_BASE.length * Integer.BYTES)
                .order(ByteOrder.nativeOrder());

        TerrainIndexPacker.packSequentialQuadIndices(destination, 12, 8);

        assertEqualsIndices(destination, TWO_QUADS_WITH_BASE);
    }

    @Test
    void rejectsInvalidSequentialIndexCount() {
        ByteBuffer destination = ByteBuffer.allocate(5 * Integer.BYTES);
        assertThrows(IllegalArgumentException.class,
                () -> TerrainIndexPacker.packSequentialQuadIndices(destination, 5, 0));
    }

    @Test
    void productionCapturePreservesBlockAndSkyLight() {
        var format = com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK;
        ByteBuffer source = ByteBuffer.allocateDirect(format.getVertexSize()).order(ByteOrder.nativeOrder());
        int light = format.getOffset(com.mojang.blaze3d.vertex.VertexFormatElement.UV2);
        source.putShort(light, (short) 232).putShort(light + 2, (short) 120);
        var state = new com.mojang.blaze3d.vertex.MeshData.DrawState(format, 1, 0,
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                com.mojang.blaze3d.vertex.VertexFormat.IndexType.SHORT);
        ByteBuffer packed = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());

        TerrainMeshStore.packVertices(packed, source, state);

        org.junit.jupiter.api.Assertions.assertEquals(232 | 120 << 8,
                Short.toUnsignedInt(packed.getShort(6)));
    }

    private static void assertEqualsIndices(ByteBuffer destination, int[] expected) {
        destination.flip();
        int[] actual = new int[expected.length];
        for (int index = 0; index < actual.length; index++) {
            actual[index] = destination.getInt();
        }
        assertArrayEquals(expected, actual);
    }
}
