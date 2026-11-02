package com.redondoguibi.direct;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

@Tag("graphics")
@EnabledOnOs(OS.WINDOWS)
class NativeTerrainInteropTest {
    @Test
    void transfersCurrentColorDepthLightAndAnimatedPatchesThroughRealWgl() {
        System.load(Path.of("build/generated/nativeResources/META-INF/native/windows-x86_64/direct_renderer.dll").toAbsolutePath().toString());
        assertTrue(GLFW.glfwInit());
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        long window = GLFW.glfwCreateWindow(32, 32, "DX12 depth regression", 0, 0);
        assertNotEquals(0, window);
        long device = 0;
        long colorObject = 0;
        long depthObject = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            assertTrue(GL.getCapabilitiesWGL().WGL_NV_DX_interop2, "WGL interop required by Direct");
            long[] handles = NativeDx12.initializeInterop(32, 32, true, 16, 8, 64);
            assertEquals(5, handles.length);
            device = WGLNVDXInterop.wglDXOpenDeviceNV(handles[0]);
            assertNotEquals(0, device);
            int color = GL11.glGenTextures();
            int depth = GL11.glGenTextures();
            colorObject = register(device, handles[1], color);
            depthObject = register(device, handles[4], depth);
            ByteBuffer atlas = ByteBuffer.allocateDirect(4 * 4 * 4);
            for (int i = 0; i < 16; i++) atlas.putInt(-1);
            atlas.flip();
            NativeDx12.updateTerrainAtlas(new ByteBuffer[] {atlas}, 4, 4, false, 1);
            ByteBuffer light = ByteBuffer.allocateDirect(1024);
            for (int i = 0; i < 256; i++) light.putInt(-1);
            light.flip();
            NativeDx12.updateLightmap(light);
            ByteBuffer vertices = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
            float[][] positions = {{-.75f,-.75f,.25f}, {.75f,-.75f,.25f}, {.75f,.75f,.25f}, {-.75f,.75f,.25f}};
            for (float[] v : positions) {
                for (float axis : v) vertices.putShort((short) Math.round((axis + 16) * 1024));
                vertices.putShort((short) 0xf0f0).putInt(-1).putShort((short) 32768).putShort((short) 32768);
            }
            vertices.flip();
            ByteBuffer indices = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder());
            for (int index : new int[] {0,1,2,2,3,0}) indices.putInt(index);
            indices.flip();
            NativeDx12.updateTerrainSection(1, 0, 0, 0, vertices, 4, indices, new int[] {6,0,0});
            NativeDx12.setVisibleSections(new long[] {1}, 1, true);
            float[] constants = new float[28];
            new Matrix4f().get(constants);
            constants[20] = 100;
            constants[21] = 200;
            for (int i = 24; i < 28; i++) constants[i] = 1;
            NativeDx12.setProfilingEnabled(true);
            NativeDx12.renderInterop(constants, 0, 0, 0, .01f, 0, 0, new float[0], false, true);
            NativeDx12.waitInteropFrame(1);
            assertTrue(NativeDx12.frameMetrics()[0] >= 0);
            assertEquals(1, NativeDx12.frameMetrics()[2]);
            var objects = stack.pointers(colorObject, depthObject);
            assertTrue(WGLNVDXInterop.wglDXLockObjectsNV(device, objects));
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
            ByteBuffer colors = stack.malloc(32 * 32 * 4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, colors);
            assertEquals(255, Byte.toUnsignedInt(colors.get((16 * 32 + 16) * 4)));
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
            var depths = stack.mallocFloat(32 * 32);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RED, GL11.GL_FLOAT, depths);
            assertEquals(.25f, depths.get(16 * 32 + 16), 1e-5);
            assertEquals(1f, depths.get(0), 1e-5);
            verifyGlDepthComposition(color, depth, stack);
            assertTrue(WGLNVDXInterop.wglDXUnlockObjectsNV(device, objects));
            GL11.glFinish();
            NativeDx12.signalInteropFence(1);

            // Updating one sprite changes the existing texture; no full-atlas replacement.
            ByteBuffer red = ByteBuffer.allocateDirect(64);
            for (int i = 0; i < 16; i++) red.put(new byte[] {(byte)255, 0, 0, (byte)255});
            red.flip();
            assertTrue(NativeDx12.updateAtlasRegion(red, 0, 0, 0, 4, 4));
            constants[16] = 0; constants[17] = 0; constants[18] = 1; constants[19] = 1;
            constants[20] = 0; constants[21] = .1f;
            new Matrix4f().translate(.1f, 0, 0).get(constants);
            NativeDx12.renderInterop(constants, 0, 0, 0, 256, 1, 0, new float[0], false, false);
            NativeDx12.waitInteropFrame(2);
            assertTrue(WGLNVDXInterop.wglDXLockObjectsNV(device, objects));
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, colors);
            int center = (16 * 32 + 16) * 4;
            assertEquals(0, Byte.toUnsignedInt(colors.get(center)));
            assertEquals(255, Byte.toUnsignedInt(colors.get(center + 2)), "vanilla fog must reach blue");
            assertTrue(WGLNVDXInterop.wglDXUnlockObjectsNV(device, objects));
            GL11.glFinish();
            NativeDx12.signalInteropFence(2);
            // Cutout alpha must remove both the color AND the exported depth.
            ByteBuffer transparent = ByteBuffer.allocateDirect(64);
            NativeDx12.updateTerrainAtlas(new ByteBuffer[] {transparent}, 4, 4, false, 1);
            NativeDx12.updateTerrainSection(1, 0, 0, 0, vertices, 4, indices, new int[] {0,6,0});
            constants[20] = 100; constants[21] = 200;
            NativeDx12.renderInterop(constants, 0, 0, 0, 256, 2, 0, new float[0], false, true);
            NativeDx12.waitInteropFrame(3);
            assertTrue(WGLNVDXInterop.wglDXLockObjectsNV(device, objects));
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RED, GL11.GL_FLOAT, depths);
            assertEquals(1f, depths.get(16 * 32 + 16), 1e-5, "cutout holes must not occlude entities");
            assertTrue(WGLNVDXInterop.wglDXUnlockObjectsNV(device, objects));
            GL11.glFinish(); NativeDx12.signalInteropFence(3);

            // The off-thread encoder's BC7 output can update an existing compressed atlas.
            NativeDx12.updateTerrainAtlas(new ByteBuffer[] {atlas, atlas, atlas}, 4, 4, true, 1);
            assertTrue(NativeDx12.isAtlasCompressed());
            assertEquals(3, NativeDx12.atlasMipLevels());
            assertThrows(IllegalStateException.class, () -> NativeDx12.updateAtlasRegion(red, 3, 0, 0, 4, 4));
            ByteBuffer encoded = ByteBuffer.allocateDirect(16);
            NativeDx12.encodeAtlasRegion(red, encoded, 4, 4, 1);
            assertTrue(NativeDx12.updateAtlasRegion(encoded, 0, 0, 0, 4, 4));
            NativeDx12.encodeAtlasRegion(red, encoded, 2, 2, 1);
            assertTrue(NativeDx12.updateAtlasRegion(encoded, 1, 0, 0, 2, 2));
            NativeDx12.encodeAtlasRegion(red, encoded, 1, 1, 1);
            assertTrue(NativeDx12.updateAtlasRegion(encoded, 2, 0, 0, 1, 1));
            NativeDx12.updateTerrainSection(1, 0, 0, 0, vertices, 4, indices, new int[] {6,0,0});
            NativeDx12.renderInterop(constants, 0, 0, 0, 256, 3, 0, new float[0], true, true);
            NativeDx12.waitInteropFrame(4);
            assertTrue(WGLNVDXInterop.wglDXLockObjectsNV(device, objects));
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, colors);
            assertTrue(Byte.toUnsignedInt(colors.get(center)) >= 250);
            assertTrue(Byte.toUnsignedInt(colors.get(center + 1)) <= 5);
            assertTrue(WGLNVDXInterop.wglDXUnlockObjectsNV(device, objects));
            GL11.glFinish(); NativeDx12.signalInteropFence(4);
            // Back-facing triangles must be culled using the same winding as vanilla.
            ByteBuffer reversed = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder());
            for (int index : new int[] {2,1,0,0,3,2}) reversed.putInt(index);
            reversed.flip();
            NativeDx12.updateTerrainSection(1, 0, 0, 0, vertices, 4, reversed, new int[] {6,0,0});
            NativeDx12.renderInterop(constants, 0, 0, 0, 256, 4, 0, new float[0], false, true);
            NativeDx12.waitInteropFrame(5);
            assertTrue(WGLNVDXInterop.wglDXLockObjectsNV(device, objects));
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RED, GL11.GL_FLOAT, depths);
            assertEquals(1f, depths.get(16 * 32 + 16), 1e-5, "back faces must not write depth");
            assertTrue(WGLNVDXInterop.wglDXUnlockObjectsNV(device, objects));
            GL11.glFinish(); NativeDx12.signalInteropFence(5);

            // CPU light updates must affect the native terrain without GL lightmap readback.
            NativeDx12.updateLightmap(ByteBuffer.allocateDirect(1024));
            NativeDx12.updateTerrainSection(1, 0, 0, 0, vertices, 4, indices, new int[] {6,0,0});
            NativeDx12.renderInterop(constants, 0, 0, 0, 256, 5, 0, new float[0], false, true);
            NativeDx12.waitInteropFrame(6);
            assertTrue(WGLNVDXInterop.wglDXLockObjectsNV(device, objects));
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, colors);
            assertEquals(0, Byte.toUnsignedInt(colors.get(center)));
            assertTrue(WGLNVDXInterop.wglDXUnlockObjectsNV(device, objects));
            GL11.glFinish(); NativeDx12.signalInteropFence(6);

            // Resize requires unregistering BOTH resources; an empty world exports clear depth.
            assertTrue(WGLNVDXInterop.wglDXUnregisterObjectNV(device, colorObject)); colorObject = 0;
            assertTrue(WGLNVDXInterop.wglDXUnregisterObjectNV(device, depthObject)); depthObject = 0;
            long[] resized = NativeDx12.resizeInterop(16, 16);
            colorObject = register(device, resized[0], GL11.glGenTextures());
            int resizedDepth = GL11.glGenTextures();
            depthObject = register(device, resized[1], resizedDepth);
            NativeDx12.clearTerrain();
            NativeDx12.renderInterop(constants, 0, 0, 0, 256, 6, 0, new float[0], false, true);
            NativeDx12.waitInteropFrame(7);
            var resizedObjects = stack.pointers(colorObject, depthObject);
            assertTrue(WGLNVDXInterop.wglDXLockObjectsNV(device, resizedObjects));
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, resizedDepth);
            var cleared = stack.mallocFloat(16 * 16);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RED, GL11.GL_FLOAT, cleared);
            assertEquals(1f, cleared.get(8 * 16 + 8), 1e-5);
            assertTrue(WGLNVDXInterop.wglDXUnlockObjectsNV(device, resizedObjects));
            GL11.glFinish(); NativeDx12.signalInteropFence(7);
            assertFalse(NativeDx12.hasInteropGpuSyncFault());
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            GL11.glFinish();
            if (colorObject != 0) WGLNVDXInterop.wglDXUnregisterObjectNV(device, colorObject);
            if (depthObject != 0) WGLNVDXInterop.wglDXUnregisterObjectNV(device, depthObject);
            if (device != 0) WGLNVDXInterop.wglDXCloseDeviceNV(device);
            NativeDx12.shutdownInterop();
            GL.setCapabilities(null);
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static long register(long device, long resource, int texture) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        long object = WGLNVDXInterop.wglDXRegisterObjectNV(device, resource, texture, GL11.GL_TEXTURE_2D, WGLNVDXInterop.WGL_ACCESS_READ_ONLY_NV);
        assertNotEquals(0, object);
        return object;
    }

    private static void verifyGlDepthComposition(int color, int depth, MemoryStack stack) {
        int framebuffer = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        int output = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, output);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 32, 32, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, output, 0);
        int buffer = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, buffer);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH_COMPONENT32F, 32, 32);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, buffer);
        assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));
        GL11.glClearDepth(1);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT);
        int program = GL20.glCreateProgram();
        for (int type : new int[] {GL20.GL_VERTEX_SHADER, GL20.GL_FRAGMENT_SHADER}) {
            int shader = GL20.glCreateShader(type);
            GL20.glShaderSource(shader, type == GL20.GL_VERTEX_SHADER ? Dx12GlInterop.VERTEX_SHADER : Dx12GlInterop.FRAGMENT_SHADER);
            GL20.glCompileShader(shader);
            assertEquals(GL11.GL_TRUE, GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS), GL20.glGetShaderInfoLog(shader));
            GL20.glAttachShader(program, shader);
            GL20.glDeleteShader(shader);
        }
        GL20.glLinkProgram(program);
        assertEquals(GL11.GL_TRUE, GL20.glGetProgrami(program, GL20.GL_LINK_STATUS), GL20.glGetProgramInfoLog(program));
        GL20.glUseProgram(program);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "sourceTexture"), 0);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "sourceDepth"), 1);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "writeTerrainDepth"), 1);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, color);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL30.glBindVertexArray(GL30.glGenVertexArrays());
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_ALWAYS);
        GL11.glDepthMask(true);
        GL11.glViewport(0, 0, 32, 32);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        var actual = stack.mallocFloat(32 * 32);
        GL11.glReadPixels(0, 0, 32, 32, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, actual);
        assertEquals(.25f, actual.get(16 * 32 + 16), 1e-5, "depth must reach the GL framebuffer");
        assertEquals(1f, actual.get(0), 1e-5, "uncovered sky must not receive terrain depth");
        // A later GL object behind the terrain is occluded; the same object covers empty sky.
        int red = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, red);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                stack.bytes((byte)255, (byte)0, (byte)0, (byte)255));
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "writeTerrainDepth"), 0);
        GL11.glDepthFunc(GL11.GL_LESS);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        ByteBuffer pixels = stack.malloc(32 * 32 * 4);
        GL11.glReadPixels(0, 0, 32, 32, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        assertEquals(255, Byte.toUnsignedInt(pixels.get((16 * 32 + 16) * 4 + 1)), "terrain must occlude the red GL object");
        assertEquals(255, Byte.toUnsignedInt(pixels.get(0)));
        assertEquals(0, Byte.toUnsignedInt(pixels.get(1)));
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }
}
