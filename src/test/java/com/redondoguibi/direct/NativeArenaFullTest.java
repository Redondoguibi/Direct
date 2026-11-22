package com.redondoguibi.direct;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.*;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;

/** A full terrain arena rejects the section instead of failing the renderer, and frees up again on removal. */
@Tag("graphics")
class NativeArenaFullTest {
    private static final int QUADS = 16384; // 65536 vertices: about 1.5 MiB per section

    private long window;

    @BeforeEach void open() {
        System.load(Path.of("build/generated/nativeResources/META-INF/native/windows-x86_64/direct_renderer.dll").toAbsolutePath().toString());
        // Like every other interop test: a hidden GL context exists before the D3D12 device does.
        assertTrue(GLFW.glfwInit());
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, 0);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        window = GLFW.glfwCreateWindow(32, 32, "Arena test", 0, 0);
        assertNotEquals(0, window);
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        NativeDx12.initializeInterop(64, 64, true, 16, 8, 64);
    }

    @AfterEach void close() {
        NativeDx12.shutdownInterop();
        GL.setCapabilities(null);
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    private static void upload(long key) {
        var vertices = ByteBuffer.allocateDirect(QUADS * 4 * 20).order(ByteOrder.nativeOrder());
        var indices = ByteBuffer.allocateDirect(QUADS * 6 * 4).order(ByteOrder.nativeOrder());
        for (int q = 0; q < QUADS; q++) for (int i : new int[]{0, 1, 2, 2, 3, 0}) indices.putInt(q * 4 + i);
        indices.flip();
        NativeDx12.updateTerrainSection(key, 0, 0, 0, vertices, QUADS * 4, indices, new int[]{QUADS * 6, 0, 0, 0});
    }

    @Test void fullArenaRejectsWithoutFailingAndRecoversAfterRemoval() {
        long firstRejected = 0;
        for (long key = 1; key <= 80 && firstRejected == 0; key++) {
            upload(key);
            if (NativeDx12.consumeArenaRejection()) firstRejected = key;
        }
        assertNotEquals(0, firstRejected, "a 64 MiB arena must fill up with 80 sections of 1.5 MiB");
        long meshBytes = QUADS * (4L * 20 + 6L * Short.BYTES);
        assertTrue(NativeDx12.largestTerrainAllocation() < meshBytes);
        assertTrue(firstRejected > 10, "the arena must hold many sections before rejecting: " + firstRejected);
        assertFalse(NativeDx12.consumeArenaRejection(), "the flag is consumed once");
        upload(1000);
        assertTrue(NativeDx12.consumeArenaRejection(), "still full");
        NativeDx12.removeTerrainSection(1);
        NativeDx12.removeTerrainSection(2);
        upload(1001);
        assertTrue(NativeDx12.consumeArenaRejection(), "freed ranges stay reserved until the GPU finishes the frames that may read them");
        assertTrue(NativeDx12.largestTerrainAllocation() < meshBytes);
        // Retired ranges are released once a later frame observes the fence of the frame that used them.
        float[] matrix = new float[28];
        new org.joml.Matrix4f().get(matrix);
        matrix[20] = 100;
        matrix[21] = 200;
        for (int i = 24; i < 28; i++) matrix[i] = 1;
        for (int frame = 0; frame < 3; frame++) {
            assertEquals(0, NativeDx12.renderInterop(matrix, 0, 0, 0, 256, frame, 0, new float[0], false, true));
            assertTrue(NativeDx12.waitInteropFrame(frame + 1));
            NativeDx12.signalInteropFence(frame + 1);
        }
        assertTrue(NativeDx12.largestTerrainAllocation() >= meshBytes);
        upload(1002);
        assertFalse(NativeDx12.consumeArenaRejection(), "space released by removals is reusable after those frames");
    }
}
