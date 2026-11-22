package com.redondoguibi.direct;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.*;
import java.nio.file.Path;
import org.joml.Matrix4f;
import org.junit.jupiter.api.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/**
 * World graph (API 1.7) through real WGL and D3D12: the scene is written by OpenGL exactly like the world
 * capture, the passes run natively and the presented color is read back. Covers the 1.5 chain contract,
 * multiple render targets, scaled buffers, compute passes, previous-frame history, the terrain-only depth
 * and configuration errors. Both depth bridges are exercised.
 */
@Tag("graphics")
abstract class NativeWorldGraphInteropTestBase {
    static final int SIZE = 64;
    static final String HEADER = """
            Texture2D<float4> Previous : register(t0);
            Texture2D<float> Depth : register(t1);
            Texture2D<float4> Original : register(t2);
            Texture2D<float> TerrainDepth : register(t3);
            Texture2D<float4> Buffer0 : register(t4);
            Texture2D<float4> Buffer1 : register(t5);
            Texture2D<float4> Buffer2 : register(t6);
            Texture2D<float4> Buffer3 : register(t7);
            Texture2D<float4> History3 : register(t15);
            SamplerState LinearClamp : register(s0);
            SamplerState PointClamp : register(s1);
            cbuffer Frame : register(b0) { float4 DirectFrame; float4 Data[63]; };
            cbuffer Graph : register(b1) { float4 DirectTarget; float4 DirectBufferSize[8]; };
            """;

    abstract boolean bridged();

    long window, device, colorObject, depthObject;
    int sharedColor, sharedDepth, fbo;
    long frame;

    @BeforeEach void open() {
        System.load(Path.of("build/generated/nativeResources/META-INF/native/windows-x86_64/direct_renderer.dll").toAbsolutePath().toString());
        assertTrue(GLFW.glfwInit());
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, 0);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        window = GLFW.glfwCreateWindow(SIZE, SIZE, "World graph test", 0, 0);
        assertNotEquals(0, window);
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        long[] handles = NativeDx12.initializeInterop(SIZE, SIZE, true, 16, 8, 64);
        device = WGLNVDXInterop.wglDXOpenDeviceNV(handles[0]);
        assertNotEquals(0, device);
        var color = InteropColorTarget.create(device, handles[1], SIZE, SIZE, true);
        assertTrue(color.direct, color.fallbackReason);
        sharedColor = color.texture;
        colorObject = color.object;
        sharedDepth = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sharedDepth);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        long depth = bridged() ? NativeDx12.acquireDepthBridgeTarget() : handles[4];
        assertNotEquals(0, depth);
        depthObject = WGLNVDXInterop.wglDXRegisterObjectNV(device, depth, sharedDepth, GL11.GL_TEXTURE_2D, WGLNVDXInterop.WGL_ACCESS_READ_WRITE_NV);
        assertNotEquals(0, depthObject);
        if (bridged()) NativeDx12.configureDirectBridge(true);
        fbo = GL30.glGenFramebuffers();
        var atlas = ByteBuffer.allocateDirect(64);
        for (int i = 0; i < 16; i++) atlas.putInt(-1);
        atlas.flip();
        NativeDx12.updateTerrainAtlas(new ByteBuffer[]{atlas}, 4, 4, false, 1);
        var light = ByteBuffer.allocateDirect(1024);
        for (int i = 0; i < 256; i++) light.putInt(-1);
        light.flip();
        NativeDx12.updateLightmap(light);
        terrainFrame(); // submits the atlas and lightmap uploads; world frames never overtake them
    }

    void terrainFrame() {
        float[] matrix = new float[28];
        new Matrix4f().get(matrix);
        matrix[20] = 100;
        matrix[21] = 200;
        for (int i = 24; i < 28; i++) matrix[i] = 1;
        assertEquals(0, NativeDx12.renderInterop(matrix, 0, 0, 0, 256, frame, 0, new float[0], false, true));
        long value = ++frame;
        assertTrue(NativeDx12.waitInteropFrame(value));
        NativeDx12.signalInteropFence(value);
    }

    @AfterEach void close() {
        GL11.glFinish();
        WGLNVDXInterop.wglDXUnregisterObjectNV(device, colorObject);
        WGLNVDXInterop.wglDXUnregisterObjectNV(device, depthObject);
        WGLNVDXInterop.wglDXCloseDeviceNV(device);
        NativeDx12.shutdownInterop();
        GL11.glDeleteTextures(sharedColor);
        GL11.glDeleteTextures(sharedDepth);
        GL30.glDeleteFramebuffers(fbo);
        GL.setCapabilities(null);
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    /** OpenGL writes the scene color and depth into the shared images, as the world capture does. */
    void scene(float r, float g, float b, float depth) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var objects = stack.pointers(colorObject, depthObject);
            assertTrue(WGLNVDXInterop.wglDXLockObjectsNV(device, objects));
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, sharedColor, 0);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, sharedDepth, 0);
            assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));
            GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
            GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[]{r, g, b, 1});
            GL30.glClearBufferfv(GL11.GL_COLOR, 1, new float[]{depth, 0, 0, 0});
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            assertTrue(WGLNVDXInterop.wglDXUnlockObjectsNV(device, objects));
        }
        GL11.glFinish();
    }

    /** Runs the configured world graph once and returns the presented RGBA8 pixel at (x, y), D3D rows. */
    int[] world(int passes, int x, int y) {
        assertEquals(0, NativeDx12.renderWorldInterop(new float[passes * 252], frame));
        long value = ++frame;
        assertTrue(NativeDx12.waitInteropFrame(value));
        NativeDx12.signalInteropFence(value);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var objects = stack.pointers(colorObject);
            assertTrue(WGLNVDXInterop.wglDXLockObjectsNV(device, objects));
            try {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, sharedColor);
                var image = ByteBuffer.allocateDirect(SIZE * SIZE * 4);
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, image);
                int at = (y * SIZE + x) * 4;
                return new int[]{Byte.toUnsignedInt(image.get(at)), Byte.toUnsignedInt(image.get(at + 1)),
                        Byte.toUnsignedInt(image.get(at + 2)), Byte.toUnsignedInt(image.get(at + 3))};
            } finally {
                assertTrue(WGLNVDXInterop.wglDXUnlockObjectsNV(device, objects));
                GL11.glFinish();
            }
        }
    }

    static void near(int expected, int actual, String what) {
        assertTrue(Math.abs(expected - actual) <= 1, what + ": expected " + expected + " but was " + actual);
    }

    @Test void passWithoutDirectivesKeepsTheChainContract() {
        NativeDx12.configureWorldPasses(new String[]{HEADER
                + "float4 main(float4 p : SV_Position) : SV_Target0 { float4 c = Previous.Load(int3(int2(p.xy), 0));"
                + " return float4(1.0 - c.rgb, Depth.Load(int3(int2(p.xy), 0))); }"});
        scene(0.2f, 0.4f, 0.6f, 0.5f);
        int[] pixel = world(1, 10, 10);
        near(204, pixel[0], "red");
        near(153, pixel[1], "green");
        near(102, pixel[2], "blue");
        near(128, pixel[3], "depth in alpha");
    }

    @Test void multipleTargetsScaledBuffersAndLaterReads() {
        String buffers = "// direct:buffer 0 rgba16f\n// direct:buffer 1 rgba8 scale=0.5\n";
        NativeDx12.configureWorldPasses(new String[]{
                buffers + "// direct:outputs color,0\n" + HEADER
                        + "struct Out { float4 color : SV_Target0; float4 extra : SV_Target1; };"
                        + "Out main(float4 p : SV_Position) { Out o; o.color = Previous.Load(int3(int2(p.xy), 0)); o.extra = float4(0.25, 0.5, 0.75, 1); return o; }",
                buffers + "// direct:outputs 1\n" + HEADER
                        + "float4 main(float4 p : SV_Position) : SV_Target0 { return float4(DirectTarget.x / 64.0, DirectTarget.y / 64.0, DirectBufferSize[0].x / 128.0, 1); }",
                buffers + HEADER
                        + "float4 main(float4 p : SV_Position) : SV_Target0 { float2 uv = p.xy / DirectFrame.yz;"
                        + " float4 a = Buffer0.Load(int3(int2(p.xy), 0)); float4 b = Buffer1.SampleLevel(LinearClamp, uv, 0);"
                        + " return p.x < 32 ? a : b; }"});
        scene(0.1f, 0.1f, 0.1f, 1);
        int[] left = world(3, 8, 8);
        near(64, left[0], "MRT buffer red");
        near(128, left[1], "MRT buffer green");
        near(191, left[2], "MRT buffer blue");
        int[] right = world(3, 48, 8);
        near(128, right[0], "half-size target width");
        near(128, right[1], "half-size target height");
        near(128, right[2], "full-size buffer width seen by another pass");
    }

    @Test void computePassesWriteBuffersReadByLaterPasses() {
        String buffers = "// direct:buffer 2 rgba8\n";
        NativeDx12.configureWorldPasses(new String[]{
                buffers + "// direct:compute\n// direct:outputs 2\n" + HEADER
                        + "RWTexture2D<float4> Out2 : register(u2);"
                        + "[numthreads(8, 8, 1)] void main(uint3 id : SV_DispatchThreadID) { Out2[id.xy] = float4(id.x / 63.0, id.y / 63.0, 0.5, 1); }",
                buffers + HEADER + "float4 main(float4 p : SV_Position) : SV_Target0 { return Buffer2.Load(int3(int2(p.xy), 0)); }"});
        scene(0, 0, 0, 1);
        int[] pixel = world(2, 63, 21);
        near(255, pixel[0], "x gradient");
        near(85, pixel[1], "y gradient");
        near(128, pixel[2], "constant");
    }

    @Test void historyBuffersAccumulateAcrossFrames() {
        String buffers = "// direct:buffer 3 rgba16f history\n";
        NativeDx12.configureWorldPasses(new String[]{
                buffers + "// direct:outputs 3\n" + HEADER
                        + "float4 main(float4 p : SV_Position) : SV_Target0 { return History3.Load(int3(int2(p.xy), 0)) + float4(0.1, 0.2, 0, 0.25); }",
                buffers + HEADER + "float4 main(float4 p : SV_Position) : SV_Target0 { return Buffer3.Load(int3(int2(p.xy), 0)); }"});
        scene(0, 0, 0, 1);
        for (int n = 1; n <= 3; n++) {
            int[] pixel = world(2, 5, 5);
            near(Math.round(n * 0.1f * 255), pixel[0], "red after frame " + n);
            near(Math.round(n * 0.2f * 255), pixel[1], "green after frame " + n);
            near(Math.round(n * 0.25f * 255), pixel[3], "alpha after frame " + n);
        }
    }

    @Test void terrainDepthIsTheNativeTerrainBeforeTheScene() {
        NativeDx12.configureWorldPasses(new String[]{"// direct:terrain-depth\n" + HEADER
                + "float4 main(float4 p : SV_Position) : SV_Target0 { int3 q = int3(int2(p.xy), 0);"
                + " return float4(TerrainDepth.Load(q), Depth.Load(q), 0, 1); }"});
        var vertices = ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder());
        for (float[] v : new float[][]{{-1, -1}, {1, -1}, {1, 1}, {-1, 1}})
            vertices.putShort((short) Math.round((v[0] + 16) * 1024)).putShort((short) Math.round((v[1] + 16) * 1024))
                    .putShort((short) Math.round(16.25 * 1024)).putShort((short) 0xf0f0).putInt(-1)
                    .putShort((short) 32768).putShort((short) 32768).putInt(0x00007f00);
        vertices.flip();
        var indices = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder());
        for (int i : new int[]{0, 1, 2, 2, 3, 0}) indices.putInt(i);
        indices.flip();
        NativeDx12.updateTerrainSection(1, 0, 0, 0, vertices, 4, indices, new int[]{6, 0, 0, 0});
        NativeDx12.setVisibleSections(new long[]{1}, 1, true);
        terrainFrame();
        scene(0, 0, 0, 0.75f); // the scene depth differs from the terrain's 0.25
        int[] pixel = world(1, 32, 32);
        near(64, pixel[0], "terrain-only depth");
        near(191, pixel[1], "scene depth");
    }

    @Test void invalidGraphsAreRejectedAndKeepTheWorkingChain() {
        String passthrough = HEADER + "float4 main(float4 p : SV_Position) : SV_Target0 { return Previous.Load(int3(int2(p.xy), 0)); }";
        NativeDx12.configureWorldPasses(new String[]{passthrough});
        assertThrows(IllegalArgumentException.class, () -> NativeDx12.configureWorldPasses(new String[]{"// direct:outputs 4\n" + passthrough, passthrough}),
                "writing an undeclared buffer");
        assertThrows(IllegalArgumentException.class, () -> NativeDx12.configureWorldPasses(new String[]{
                "// direct:buffer 0 rgba8\n" + passthrough, "// direct:buffer 0 rgba16f\n" + passthrough}), "conflicting declarations");
        assertThrows(IllegalArgumentException.class, () -> NativeDx12.configureWorldPasses(new String[]{
                "// direct:buffer 0 rgba8\n// direct:outputs 0\n" + passthrough}), "final pass without the chain color");
        assertThrows(IllegalArgumentException.class, () -> NativeDx12.configureWorldPasses(new String[]{
                "// direct:buffer 0 rgba8 scale=0.5\n// direct:outputs color,0\n" + passthrough}), "outputs of different sizes");
        assertThrows(IllegalArgumentException.class, () -> NativeDx12.configureWorldPasses(new String[]{"// direct:frobnicate\n" + passthrough}),
                "unknown directive");
        scene(0.3f, 0.6f, 0.9f, 1);
        int[] pixel = world(1, 1, 1);
        near(77, pixel[0], "previous chain still active");
        near(230, pixel[2], "previous chain still active");
    }
}

class NativeWorldGraphInteropTest extends NativeWorldGraphInteropTestBase {
    @Override boolean bridged() { return false; }
}

class NativeBridgedWorldGraphInteropTest extends NativeWorldGraphInteropTestBase {
    @Override boolean bridged() { return true; }
}
