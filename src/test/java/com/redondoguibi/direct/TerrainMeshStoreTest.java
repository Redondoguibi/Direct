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

    private static void assertEqualsIndices(ByteBuffer destination, int[] expected) {
        destination.flip();
        int[] actual = new int[expected.length];
        for (int index = 0; index < actual.length; index++) {
            actual[index] = destination.getInt();
        }
        assertArrayEquals(expected, actual);
    }
}
