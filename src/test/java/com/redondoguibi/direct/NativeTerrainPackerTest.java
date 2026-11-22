package com.redondoguibi.direct;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.SplittableRandom;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** The C++ packer must reproduce the Java reference byte for byte, including float edge cases. */
@Tag("graphics")
@EnabledOnOs(OS.WINDOWS)
class NativeTerrainPackerTest {
    // DefaultVertexFormat.BLOCK: position 0, color 12, uv0 16, uv2 (light) 24, normal 28; stride 32.
    static final int STRIDE = 32, POSITION = 0, COLOR = 12, UV = 16, LIGHT = 24, NORMAL = 28;
    private static final float[] SPECIAL = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.0f, 0.0f,
            0.49999997f, 0.5f, -0.5f, 1.0f, 1.0000001f, -16.0f, 47.99995f, 48.0f, 1e30f, -1e30f, Float.MIN_VALUE,
            -16.000488f, -15.999512f, 0.99999994f, 1.5258789e-5f};

    @BeforeAll
    static void loadLibrary() {
        System.load(Path.of("build/generated/nativeResources/META-INF/native/windows-x86_64/direct_renderer.dll").toAbsolutePath().toString());
    }

    static ByteBuffer mesh(int vertices, SplittableRandom random, boolean special) {
        ByteBuffer source = ByteBuffer.allocateDirect(vertices * STRIDE + 7).order(ByteOrder.nativeOrder());
        for (int v = 0; v < vertices; v++) {
            int base = v * STRIDE;
            for (int axis = 0; axis < 3; axis++) source.putFloat(base + POSITION + axis * 4, value(random, special, -17, 49));
            source.putInt(base + COLOR, random.nextInt());
            source.putFloat(base + UV, value(random, special, -0.2f, 1.2f));
            source.putFloat(base + UV + 4, value(random, special, -0.2f, 1.2f));
            source.putShort(base + LIGHT, (short) random.nextInt(-400, 400));
            source.putShort(base + LIGHT + 2, (short) random.nextInt(-400, 400));
            for (int i = 0; i < 4; i++) source.put(base + NORMAL + i, (byte) random.nextInt());
        }
        return source;
    }

    private static float value(SplittableRandom random, boolean special, float low, float high) {
        if (special && random.nextInt(4) == 0) return SPECIAL[random.nextInt(SPECIAL.length)];
        // Quantized grid points exercise exact rounding ties of the 1/1024 position packing.
        if (random.nextBoolean()) return (float) (random.nextInt(-20000, 50000) / 2048.0);
        return low + random.nextFloat() * (high - low);
    }

    static byte[] materials(int vertices, SplittableRandom random) {
        byte[] materials = new byte[(vertices + 3) / 4];
        byte[] kinds = {0, 1, 3, 5, 5, 8, 8, 9};
        for (int i = 0; i < materials.length; i++) materials[i] = kinds[random.nextInt(kinds.length)];
        return materials;
    }

    private static void assertSame(ByteBuffer source, int count, byte[] materials, String label) {
        ByteBuffer java = ByteBuffer.allocateDirect(count * 20 + 20).order(ByteOrder.nativeOrder());
        ByteBuffer cpp = ByteBuffer.allocateDirect(count * 20 + 20).order(ByteOrder.nativeOrder());
        java.position(20);
        cpp.position(20); // non-zero destination offset, as when layers are appended
        TerrainBufferPacker.packVertices20Java(java, source, count, STRIDE, POSITION, COLOR, UV, LIGHT, NORMAL, materials);
        NativeDx12.packTerrainVertices20(cpp, cpp.position(), source, source.position(), count, STRIDE, POSITION, COLOR, UV, LIGHT, NORMAL, materials);
        for (int i = 0; i < count * 20; i++) {
            byte expected = java.get(20 + i), actual = cpp.get(20 + i);
            if (expected != actual) fail(label + ": vertex " + i / 20 + " byte " + i % 20 + " expected " + expected + " but native wrote " + actual);
        }
    }

    @Test
    void matchesJavaOnRandomAndEdgeCaseMeshes() {
        SplittableRandom random = new SplittableRandom(20261120);
        for (int round = 0; round < 400; round++) {
            int count = random.nextInt(0, 700);
            boolean special = round % 3 == 0;
            ByteBuffer source = mesh(count + 3, random, special);
            source.position(round % 4 == 0 ? STRIDE : 0); // non-zero source offset
            int usable = Math.min(count, (source.capacity() - source.position()) / STRIDE);
            assertSame(source, usable, materials(usable, random), "round " + round);
        }
    }

    @Test
    void rejectsOutOfRangeRequests() {
        ByteBuffer source = mesh(4, new SplittableRandom(1), false);
        ByteBuffer small = ByteBuffer.allocateDirect(60).order(ByteOrder.nativeOrder());
        assertThrows(IllegalArgumentException.class, () -> NativeDx12.packTerrainVertices20(small, 0, source, 0, 4,
                STRIDE, POSITION, COLOR, UV, LIGHT, NORMAL, new byte[1]), "destination too small");
        ByteBuffer out = ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder());
        assertThrows(IllegalArgumentException.class, () -> NativeDx12.packTerrainVertices20(out, 0, source, 0, 4,
                STRIDE, POSITION, COLOR, UV, LIGHT, NORMAL, new byte[0]), "missing materials");
        assertThrows(IllegalArgumentException.class, () -> NativeDx12.packTerrainVertices20(out, 0, source, 0, 4,
                8, POSITION, COLOR, UV, LIGHT, NORMAL, new byte[1]), "attribute beyond stride");
    }
}
