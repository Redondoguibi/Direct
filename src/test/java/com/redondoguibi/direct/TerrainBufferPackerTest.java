package com.redondoguibi.direct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

class TerrainBufferPackerTest {
    private static final int STRIDE = 24;
    private static final int SOURCE_PREFIX = 8;

    @Test
    void v2PreservesNormalsAndAnchorsPlantBases() {
        var source=ByteBuffer.allocateDirect(4*32).order(ByteOrder.nativeOrder());
        for(int i=0;i<4;i++)source.putFloat(i%2).putFloat(i<2?0:1).putFloat(0).putInt(-1)
                .putFloat(.2f).putFloat(.3f).putInt(0x00f000f0).put((byte)-127).put((byte)0).put((byte)127).put((byte)0);
        source.flip();var packed=ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder());
        TerrainBufferPacker.packVertices20(packed,source,4,32,0,12,16,24,28,new byte[]{8});
        assertEquals(0,source.position());assertEquals(80,packed.position());
        for(int i=0;i<4;i++){
            assertEquals(-127,packed.get(i*20+16));assertEquals(127,packed.get(i*20+18));
            assertEquals(i<2?8:136,Byte.toUnsignedInt(packed.get(i*20+19)));
        }
        packed.clear();
        assertThrows(IllegalArgumentException.class,()->TerrainBufferPacker.packVertices20(packed,source,4,32,0,12,16,24,30,new byte[]{8}));
        assertEquals(0,packed.position());
        assertThrows(IllegalArgumentException.class,()->TerrainBufferPacker.packVertices20(packed,source,4,32,0,12,16,24,28,new byte[0]));
    }

    @Test
    void v2MarksWaterSurfacesWithoutMovingTheBottom() {
        var source=ByteBuffer.allocateDirect(8*32).order(ByteOrder.nativeOrder());
        for(float[] p:new float[][]{{0,1,0},{0,1,1},{1,1,1},{1,1,0},{0,0,0},{1,0,0},{1,0,1},{0,0,1}})
            source.putFloat(p[0]).putFloat(p[1]).putFloat(p[2]).putInt(-1).putFloat(.2f).putFloat(.3f).putInt(0).putInt(0x00007f00);
        source.flip();var packed=ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder());
        TerrainBufferPacker.packVertices20(packed,source,8,32,0,12,16,24,28,new byte[]{5,5});
        for(int i=0;i<8;i++)assertEquals(i<4?133:5,Byte.toUnsignedInt(packed.get(i*20+19)));
    }

    @Test
    void packsMultipleDirectVertexLayersAtTheirCurrentPositions() {
        ByteBuffer destination = ByteBuffer.allocateDirect(2 * 16).order(ByteOrder.nativeOrder());
        ByteBuffer firstLayer = vertex(1.0f, 2.0f, 3.0f, 0x11223344, 0.5f, 0.25f);
        ByteBuffer secondLayer = vertex(4.0f, 5.0f, 6.0f, 0x55667788, 1.0f, 0.0f);

        TerrainBufferPacker.packVertices(destination, firstLayer, 1, STRIDE, 0, 12, 16);
        TerrainBufferPacker.packVertices(destination, secondLayer, 1, STRIDE, 0, 12, 16);

        assertEquals(32, destination.position());
        destination.flip();
        assertPackedVertex(destination, 17_408, 18_432, 19_456, 0x11223344, 32_768, 16_384);
        assertPackedVertex(destination, 20_480, 21_504, 22_528, 0x55667788, 65_535, 0);
    }

    @Test
    void packsMultipleDirectIndexLayersAtTheirCurrentPositions() {
        ByteBuffer destination = ByteBuffer.allocateDirect(4 * Integer.BYTES).order(ByteOrder.nativeOrder());
        ByteBuffer firstLayer = ByteBuffer.allocateDirect(2 + 2 * Short.BYTES).order(ByteOrder.nativeOrder());
        firstLayer.position(2);
        firstLayer.putShort((short) 1).putShort((short) 3);
        firstLayer.position(2);
        ByteBuffer secondLayer = ByteBuffer.allocateDirect(3 + 2 * Integer.BYTES).order(ByteOrder.nativeOrder());
        secondLayer.position(3);
        secondLayer.putInt(2).putInt(4);
        secondLayer.position(3);

        TerrainBufferPacker.packIndices(destination, firstLayer, 2, Short.BYTES, 0);
        TerrainBufferPacker.packIndices(destination, secondLayer, 2, Integer.BYTES, 4);

        assertEquals(4 * Integer.BYTES, destination.position());
        destination.flip();
        assertEquals(1, destination.getInt());
        assertEquals(3, destination.getInt());
        assertEquals(6, destination.getInt());
        assertEquals(8, destination.getInt());
    }

    @Test
    void rejectsVertexPacksThatExceedDestinationRemainingBytes() {
        ByteBuffer source = vertex(1.0f, 2.0f, 3.0f, 0, 0.0f, 0.0f);
        ByteBuffer destination = ByteBuffer.allocateDirect(15);
        assertThrows(IllegalArgumentException.class,
                () -> TerrainBufferPacker.packVertices(destination, source, 1, STRIDE, 0, 12, 16));
    }

    @Test
    void preservesBothVanillaLightCoordinatesWithoutIncreasingVertexStride() {
        ByteBuffer source = ByteBuffer.allocateDirect(28).order(ByteOrder.nativeOrder());
        source.putFloat(1).putFloat(2).putFloat(3).putInt(-1).putFloat(.25f).putFloat(.75f);
        source.putShort((short) 232).putShort((short) 120).flip();
        ByteBuffer packed = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        TerrainBufferPacker.packVertices(packed, source, 1, 28, 0, 12, 16, 24);
        assertEquals(16, packed.position());
        assertEquals(232 | 120 << 8, Short.toUnsignedInt(packed.getShort(6)));
    }

    @Test
    void rejectsAttributesOutsideTheirVertexBeforeWritingAnything() {
        ByteBuffer source = ByteBuffer.allocateDirect(56);
        ByteBuffer packed = ByteBuffer.allocateDirect(32);
        assertThrows(IllegalArgumentException.class,
                () -> TerrainBufferPacker.packVertices(packed, source, 2, 28, 0, 12, 16, 26));
        assertEquals(0, packed.position());
    }

    private static ByteBuffer vertex(float x, float y, float z, int color, float u, float v) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(SOURCE_PREFIX + STRIDE).order(ByteOrder.nativeOrder());
        buffer.position(SOURCE_PREFIX);
        buffer.putFloat(x).putFloat(y).putFloat(z).putInt(color).putFloat(u).putFloat(v);
        buffer.position(SOURCE_PREFIX);
        return buffer;
    }

    private static void assertPackedVertex(ByteBuffer buffer, int x, int y, int z,
            int color, int u, int v) {
        assertEquals(x, Short.toUnsignedInt(buffer.getShort()));
        assertEquals(y, Short.toUnsignedInt(buffer.getShort()));
        assertEquals(z, Short.toUnsignedInt(buffer.getShort()));
        assertEquals(0, Short.toUnsignedInt(buffer.getShort()));
        assertEquals(color, buffer.getInt());
        assertEquals(u, Short.toUnsignedInt(buffer.getShort()));
        assertEquals(v, Short.toUnsignedInt(buffer.getShort()));
    }
}
