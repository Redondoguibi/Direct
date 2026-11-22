package com.redondoguibi.direct;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/**
 * Whole-frame reproduction of Direct + a Raydiance world pack, without Minecraft: OpenGL sky, native DX12 terrain
 * and its composite, OpenGL "entity" work interleaved with CPU work, the complete-world HLSL chain (the pack's real
 * prepared sources, see Raydiance's PackSourceDumpTest) and a CPU tail standing in for hand, GUI and the next
 * frame's tick. Each mode runs in A/B/B/A phases. Synthetic: never a Minecraft FPS measurement.
 *
 * <p>Arguments: dll, pack source directory (world-N.hlsl, geometry.hlsl, options.txt) or "none",
 * comma-separated modes, entity GL ms, CPU tail ms.</p>
 */
public final class WorldPostBenchmark {
    static final int W = 1920, H = 1080, FLOATS = 252;
    private static final String VS = "#version 150\nuniform float depth;const vec2 p[3]=vec2[](vec2(-1,-1),vec2(3,-1),vec2(-1,3));void main(){gl_Position=vec4(p[gl_VertexID],depth,1);}";
    private static final String HEAVY = "#version 150\nuniform float seed;out vec4 color;void main(){vec2 u=gl_FragCoord.xy*0.001+seed;float a=0.0;"
            + "for(int i=0;i<4;i++){a+=sin(u.x*float(i)+a)*cos(u.y-a);}color=vec4(fract(a),0.3,0.2,1);}";
    private static final String FLAT = "#version 150\nuniform vec4 tint;out vec4 color;void main(){color=tint;}";

    long window, device;
    InteropColorTarget color;
    int depthTexture, sceneFbo, sceneColor, sceneDepth, vao, composite, heavy, flat;
    long colorObject, depthObject, frameIndex, pendingGlSync, pendingGlValue;
    boolean bridge;
    final InteropSceneCapture capture = new InteropSceneCapture();
    float[] terrainMatrix, world;
    String[] worldSources = new String[0];

    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        var bench = new WorldPostBenchmark();
        String packDir = args.length > 1 ? args[1] : "none";
        String[] modes = (args.length > 2 ? args[2] : "off,current").split(",");
        double entityMs = args.length > 3 ? Double.parseDouble(args[3]) : 6;
        double tailMs = args.length > 4 ? Double.parseDouble(args[4]) : 5;
        bench.open(packDir);
        try {
            int draws = bench.calibrate(entityMs);
            System.out.printf(Locale.ROOT, "adapter=%s size=%dx%d pack=%s entityDraws=%d tailCpuMs=%.1f directBridge=%s%n",
                    NativeDx12.interopAdapterName(), W, H, packDir, draws, tailMs, bench.bridge);
            for (String mode : modes) bench.verify(mode);
            List<String> order = new ArrayList<>(Arrays.asList(modes));
            List<String> reversed = new ArrayList<>(order);
            Collections.reverse(reversed);
            order.addAll(reversed);
            for (String mode : order) bench.phase(mode, draws, tailMs);
        } finally {
            bench.close();
        }
    }

    void open(String packDir) throws Exception {
        if (!GLFW.glfwInit()) throw new AssertionError("GLFW");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, 0);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, 1);
        window = GLFW.glfwCreateWindow(32, 32, "Direct world post benchmark", 0, 0);
        if (window == 0) throw new AssertionError("GL window");
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        long[] handles = NativeDx12.initializeInterop(W, H, true, 16, 8, 64);
        device = WGLNVDXInterop.wglDXOpenDeviceNV(handles[0]);
        if (device == 0) throw new AssertionError("WGL device");
        color = InteropColorTarget.create(device, handles[1], W, H, true);
        colorObject = color.object;
        depthTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        bridge = System.getenv("BENCH_BRIDGE") != null;
        long depthResource = bridge ? NativeDx12.acquireDepthBridgeTarget() : handles[4];
        if (depthResource == 0) throw new AssertionError("depth bridge unavailable");
        depthObject = WGLNVDXInterop.wglDXRegisterObjectNV(device, depthResource, depthTexture, GL11.GL_TEXTURE_2D,
                WGLNVDXInterop.WGL_ACCESS_READ_WRITE_NV);
        if (depthObject == 0) throw new AssertionError("WGL depth");
        if (bridge) NativeDx12.configureDirectBridge(true);
        sceneColor = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneColor);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, W, H, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        sceneDepth = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneDepth);
        // Minecraft's main target: unsized depth component with float data.
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, W, H, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        sceneFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, sceneFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, sceneColor, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, sceneDepth, 0);
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("scene FBO");
        vao = GL30.glGenVertexArrays();
        composite = program(Dx12GlInterop.VERTEX_SHADER, Dx12GlInterop.FRAGMENT_SHADER);
        heavy = program(VS, HEAVY);
        flat = program(VS, FLAT);
        terrainMatrix = terrain();
        world = new float[FLOATS];
        if (!packDir.equals("none")) {
            Path dir = Path.of(packDir);
            List<String> sources = new ArrayList<>();
            for (int i = 0; Files.exists(dir.resolve("world-" + i + ".hlsl")); i++) sources.add(Files.readString(dir.resolve("world-" + i + ".hlsl")));
            worldSources = sources.toArray(String[]::new);
            String trivial = System.getenv("BENCH_TRIVIAL");
            if (trivial != null) {
                // Same bindings, no work: isolates the fixed cost of a world pass from the pack's shader.
                worldSources = new String[]{"Texture2D<float4> Previous : register(t0);Texture2D<float> Depth : register(t1);"
                        + "float4 main(float4 p : SV_Position) : SV_Target0 { float4 c = Previous.Load(int3(int2(p.xy), 0));"
                        + (trivial.equals("depth") ? "c.rgb *= Depth.Load(int3(int2(p.xy), 0)) < 1 ? 0.5 : 1.0;" : "")
                        + "return float4(1.0 - c.rgb, c.a); }"};
            }
            if (Files.exists(dir.resolve("geometry.hlsl")) && System.getenv("BENCH_NO_GEOMETRY") == null) {
                List<String> values = Files.readAllLines(dir.resolve("options.txt"));
                float[] geometry = new float[32];
                for (int i = 0; i < values.size() && i < 32; i++) {
                    geometry[i] = Float.parseFloat(values.get(i));
                    world[32 + i] = geometry[i];
                }
                NativeDx12.configurePackGeometry(Files.readString(dir.resolve("geometry.hlsl")));
                NativeDx12.setPackGeometryOptions(geometry, false, false);
            }
            fillContext(world);
        }
        NativeDx12.setProfilingEnabled(true);
    }

    /** Raydiance's frame block: matrices at 64..127, sun, clocks, camera and sky at 128..147. */
    static void fillContext(float[] frame) {
        var projection = new Matrix4f().perspective((float) Math.toRadians(70), W / (float) H, 0.05f, 256);
        var view = new Matrix4f();
        projection.get(frame, 64);
        new Matrix4f(projection).invert().get(frame, 80);
        new Matrix4f(view).invert().get(frame, 96);
        view.get(frame, 112);
        frame[128] = 0.3f; frame[129] = 0.8f; frame[130] = -0.52f; frame[131] = 0.9f;
        frame[134] = 120; frame[135] = 4000;
        frame[137] = 70;
        frame[140] = 1;
        frame[144] = 0.47f; frame[145] = 0.65f; frame[146] = 1f; frame[147] = 1;
        // Benchmark-only constants for variants that read per-frame colors from the cbuffer (floats 160..183).
        float[] colors = {0.97f, 0.99f, 1.02f, 0, 0.1f, 0.05f, -0.1f, 0, 1.0f, 1.0f, 0.99f, 0, 0.7f, 0.79f, 0.86f, 0, 0, 0, 0, 1.03f, 0.3f, 0.5f, 0.8f, 0.05f};
        System.arraycopy(colors, 0, frame, 160, colors.length);
    }

    int calibrate(double targetMs) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, sceneFbo);
        GL11.glViewport(0, 0, W, H);
        GL20.glUseProgram(heavy);
        GL30.glBindVertexArray(vao);
        for (int i = 0; i < 5; i++) heavyDraw(i);
        GL11.glFinish();
        long t = System.nanoTime();
        for (int i = 0; i < 60; i++) heavyDraw(i);
        GL11.glFinish();
        double ms = (System.nanoTime() - t) / 1e6 / 60;
        return Math.max(1, (int) Math.round(targetMs / ms));
    }

    void heavyDraw(int index) {
        GL20.glUniform1f(GL20.glGetUniformLocation(heavy, "seed"), index * 0.37f);
        GL20.glUniform1f(GL20.glGetUniformLocation(heavy, "depth"), 0.2f);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    // ---- One frame in Minecraft's order -------------------------------------------------------------------------

    final double[] stage = new double[8];
    double gpuTerrain, gpuImport, gpuPasses;

    /** @param scene null for timed frames; otherwise a flat scene color used to verify the presented output. */
    void frame(String mode, int draws, double tailMs, float[] scene) {
        long t0 = System.nanoTime();
        // Sky: vanilla clears the main target and draws the sky before terrain setup.
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, sceneFbo);
        GL11.glViewport(0, 0, W, H);
        GL11.glDepthMask(true);
        GL11.glColorMask(true, true, true, true);
        if (scene != null) GL11.glClearColor(scene[0], scene[1], scene[2], 1);
        else GL11.glClearColor(0.4f, 0.6f, 0.9f, 1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        // Terrain submission (renderTerrainOverlay): only after OpenGL released the previous output.
        while (!pollGlSync()) Thread.onSpinWait();
        long t1 = System.nanoTime();
        if (NativeDx12.renderInterop(terrainMatrix, 0, 0, 0, 256, frameIndex, 0, new float[0], false, true) != 0)
            throw new AssertionError("terrain submit");
        long terrainValue = frameIndex + 1, terrainFrame = frameIndex;
        frameIndex++;
        long t2 = System.nanoTime();
        // Composite before the solid layer (compositeTerrainFrame).
        if (!NativeDx12.waitInteropFrame(terrainValue)) throw new AssertionError("terrain wait");
        long t3 = System.nanoTime();
        var metrics = NativeDx12.frameMetrics();
        gpuTerrain = metrics != null ? metrics[0] : -1;
        drawImported(terrainFrame, mode.equals("merged") && worldSources.length > 0);
        long t4 = System.nanoTime();
        // Entities, block entities, translucent and particles: GL draws between CPU work.
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL20.glUseProgram(heavy);
        GL30.glBindVertexArray(vao);
        if (scene == null) for (int d = 0; d < draws; d++) { heavyDraw(d); spin(0.6); }
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        long t5 = System.nanoTime();
        if (!mode.equals("off") && worldSources.length > 0) worldPost(mode);
        if (heldLock) throw new AssertionError("lock still held");
        long t6 = System.nanoTime();
        // Hand, GUI, swap and the next frame's CPU work before terrain.
        GL20.glUseProgram(flat);
        GL20.glUniform4f(GL20.glGetUniformLocation(flat, "tint"), 1, 1, 1, 1);
        GL20.glUniform1f(GL20.glGetUniformLocation(flat, "depth"), 0);
        GL11.glViewport(0, 0, 64, 64);
        if (scene == null) GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL11.glViewport(0, 0, W, H);
        GL11.glFlush();
        spin(tailMs);
        long t7 = System.nanoTime();
        stage[0] = (t1 - t0) / 1e6; stage[1] = (t2 - t1) / 1e6; stage[2] = (t3 - t2) / 1e6; stage[3] = (t4 - t3) / 1e6;
        stage[4] = (t5 - t4) / 1e6; stage[5] = (t6 - t5) / 1e6; stage[6] = (t7 - t6) / 1e6; stage[7] = (t7 - t0) / 1e6;
    }

    final double[] world3 = new double[3];
    final double[] detail = new double[8];

    /** Dx12GlInterop.renderWorld, step for step. */
    void worldPost(String mode) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!NativeDx12.isWorldFrameReady()) throw new AssertionError("world slot busy");
            float[] uniforms = worldSources.length == 1 ? world : new float[worldSources.length * FLOATS];
            for (int i = 0; worldSources.length > 1 && i < worldSources.length; i++) System.arraycopy(world, 0, uniforms, i * FLOATS, FLOATS);
            if (configured != worldSources) { NativeDx12.configureWorldPasses(worldSources); configured = worldSources; }
            long start = System.nanoTime();
            var objects = stack.pointers(colorObject, depthObject);
            if (!heldLock) {
                access(WGLNVDXInterop.WGL_ACCESS_WRITE_DISCARD_NV);
                if (!WGLNVDXInterop.wglDXLockObjectsNV(device, objects)) throw new AssertionError("lock in");
            }
            heldLock = false;
            long a = System.nanoTime();
            capture.capture(sceneFbo, sceneDepth, color.texture, depthTexture, W, H);
            long b = System.nanoTime();
            if (!WGLNVDXInterop.wglDXUnlockObjectsNV(device, objects)) throw new AssertionError("unlock in");
            long c = System.nanoTime();
            finishGl();
            long captured = System.nanoTime();
            if (NativeDx12.renderWorldInterop(uniforms, frameIndex) != 0) throw new AssertionError("world submit");
            long value = ++frameIndex;
            long d = System.nanoTime();
            if (!NativeDx12.waitInteropFrame(value)) throw new AssertionError("world wait");
            long rendered = System.nanoTime();
            access(WGLNVDXInterop.WGL_ACCESS_READ_ONLY_NV);
            var colorOnly = stack.pointers(colorObject);
            if (!WGLNVDXInterop.wglDXLockObjectsNV(device, colorOnly)) throw new AssertionError("lock out");
            long e = System.nanoTime();
            capture.presentColor(sceneFbo, color.texture, W, H);
            if (!WGLNVDXInterop.wglDXUnlockObjectsNV(device, colorOnly)) throw new AssertionError("unlock out");
            queueGlSync(value);
            long presented = System.nanoTime();
            detail[0] = (a - start) / 1e6; detail[1] = (b - a) / 1e6; detail[2] = (c - b) / 1e6; detail[3] = (captured - c) / 1e6;
            detail[4] = (d - captured) / 1e6; detail[5] = (rendered - d) / 1e6; detail[6] = (e - rendered) / 1e6; detail[7] = (presented - e) / 1e6;
            world3[0] = (captured - start) / 1e6; world3[1] = (rendered - captured) / 1e6; world3[2] = (presented - rendered) / 1e6;
            try {
                var gpu = NativeDx12.worldFrameMetrics();
                if (gpu != null) { gpuImport = gpu[0]; gpuPasses = gpu[1]; }
            } catch (UnsatisfiedLinkError olderLibrary) {
                gpuImport = gpuPasses = -1; // DLLs before 1.9 have no world timestamps
            }
        }
    }
    private String[] configured;
    private int accessMode = WGLNVDXInterop.WGL_ACCESS_READ_WRITE_NV;

    void access(int mode) {
        if (accessMode == mode) return;
        if (!WGLNVDXInterop.wglDXObjectAccessNV(colorObject, mode) || !WGLNVDXInterop.wglDXObjectAccessNV(depthObject, mode))
            throw new AssertionError("access");
        accessMode = mode;
    }

    boolean heldLock;

    /** @param hold keep the images locked until the world capture (one WGL round trip less per frame). */
    void drawImported(long frame, boolean hold) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var objects = stack.pointers(colorObject, depthObject);
            access(hold ? WGLNVDXInterop.WGL_ACCESS_READ_WRITE_NV : WGLNVDXInterop.WGL_ACCESS_READ_ONLY_NV);
            if (!WGLNVDXInterop.wglDXLockObjectsNV(device, objects)) throw new AssertionError("lock terrain");
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_ALWAYS);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL20.glUseProgram(composite);
            GL30.glBindVertexArray(vao);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, color.texture);
            GL20.glUniform1i(GL20.glGetUniformLocation(composite, "sourceTexture"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(composite, "sourceDepth"), 1);
            GL20.glUniform1i(GL20.glGetUniformLocation(composite, "writeTerrainDepth"), 1);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            if (hold) heldLock = true;
            else if (!WGLNVDXInterop.wglDXUnlockObjectsNV(device, objects)) throw new AssertionError("unlock terrain");
            queueGlSync(frame + 1);
        }
    }

    void queueGlSync(long value) {
        if (pendingGlSync != 0) { GL32.glDeleteSync(pendingGlSync); NativeDx12.signalInteropFence(pendingGlValue); }
        pendingGlSync = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        pendingGlValue = value;
        GL11.glFlush();
    }

    boolean pollGlSync() {
        if (pendingGlSync == 0) return true;
        int result = GL32.glClientWaitSync(pendingGlSync, 0, 0L);
        if (result == GL32.GL_ALREADY_SIGNALED || result == GL32.GL_CONDITION_SATISFIED) {
            GL32.glDeleteSync(pendingGlSync);
            pendingGlSync = 0;
            NativeDx12.signalInteropFence(pendingGlValue);
            return true;
        }
        return false;
    }

    void finishGl() {
        GL11.glFinish();
        if (pendingGlSync != 0) { GL32.glDeleteSync(pendingGlSync); pendingGlSync = 0; NativeDx12.signalInteropFence(pendingGlValue); }
    }

    // ---- Phases ---------------------------------------------------------------------------------------------------

    final Map<String, int[]> reference = new HashMap<>();
    static final float[][] PALETTE = {{0.9f, 0.2f, 0.1f}, {0.1f, 0.8f, 0.3f}, {0.2f, 0.3f, 0.9f}, {0.7f, 0.7f, 0.2f}};

    /** Presented sky-corner pixels must match the CPU-synchronized reference for the same scene, every frame. */
    void verify(String mode) {
        int mismatches = 0;
        for (int i = 0; i < 48; i++) {
            float[] scene = PALETTE[i % PALETTE.length];
            frame(mode, 0, 0, scene);
            int[] pixel = readPixel(40, H - 40);
            int[] ground = readPixel(W / 2, H / 3);
            float depth = readDepth(W / 2, H / 3);
            if (i < PALETTE.length) System.out.printf(Locale.ROOT, "verify mode=%s palette=%d sky=%s terrain=%s terrainDepth=%.7f%n",
                    mode, i, Arrays.toString(pixel), Arrays.toString(ground), depth);
            // The first post mode verified is the reference; "off" keeps its own (unprocessed) reference.
            String key = (mode.equals("off") ? "off" : "post") + (i % PALETTE.length);
            int[] expected = reference.computeIfAbsent(key, k -> pixel);
            for (int c = 0; c < 3; c++) if (Math.abs(expected[c] - pixel[c]) > 2) { mismatches++; break; }
        }
        System.out.printf(Locale.ROOT, "verify mode=%s mismatchedFrames=%d/48 pixel=%s unprocessed=%s%n", mode, mismatches,
                Arrays.toString(reference.get(mode.equals("off") ? "off0" : "post0")), Arrays.toString(reference.get("off0")));
    }

    float readDepth(int x, int y) {
        GL11.glFinish();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var value = stack.mallocFloat(1);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sceneFbo);
            GL11.glReadPixels(x, y, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, value);
            return value.get(0);
        }
    }

    int[] readPixel(int x, int y) {
        GL11.glFinish();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var pixel = stack.malloc(4);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sceneFbo);
            GL11.glReadPixels(x, y, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
            return new int[]{Byte.toUnsignedInt(pixel.get(0)), Byte.toUnsignedInt(pixel.get(1)), Byte.toUnsignedInt(pixel.get(2))};
        }
    }

    void phase(String mode, int draws, double tailMs) {
        int warmup = 30, samples = 120;
        double[][] values = new double[24][samples];
        long last = System.nanoTime();
        for (int i = 0; i < warmup + samples; i++) {
            frame(mode, draws, tailMs, null);
            long now = System.nanoTime();
            if (i >= warmup) {
                int s = i - warmup;
                for (int k = 0; k < 8; k++) values[k][s] = stage[k];
                values[8][s] = (now - last) / 1e6;
                values[9][s] = gpuTerrain;
                values[10][s] = mode.equals("off") ? 0 : world3[0];
                values[11][s] = mode.equals("off") ? 0 : world3[1];
                values[12][s] = mode.equals("off") ? 0 : world3[2];
                values[13][s] = mode.equals("off") ? 0 : gpuImport + gpuPasses;
                for (int k = 0; k < 8; k++) values[14 + k][s] = mode.equals("off") ? 0 : detail[k];
                values[22][s] = mode.equals("off") ? 0 : gpuImport;
                values[23][s] = mode.equals("off") ? 0 : gpuPasses;
            }
            last = now;
        }
        String[] names = {"sky", "terrainSubmit", "terrainWait", "terrainComposite", "entities", "worldPost", "tail", "frameCpu",
                "period", "gpuTerrain", "worldCapture", "worldSubmitWait", "worldPresent", "gpuWorld",
                "w.lockIn", "w.captureCmds", "w.unlockIn", "w.glFinish", "w.submit", "w.dxWait", "w.lockOut", "w.presentCmds",
                "gpuImport", "gpuPasses"};
        StringBuilder line = new StringBuilder("mode=" + mode);
        for (int k = 0; k < names.length; k++) {
            Arrays.sort(values[k]);
            line.append(String.format(Locale.ROOT, " %s=%.2f", names[k], values[k][samples / 2]));
        }
        Arrays.sort(values[8]);
        line.append(String.format(Locale.ROOT, " periodP95=%.2f", values[8][samples * 95 / 100]));
        System.out.println(line);
    }

    // ---- Setup helpers --------------------------------------------------------------------------------------------

    /** Overlapping full-screen quads give the native terrain a measurable 1080p fill cost. */
    static float[] terrain() {
        var atlas = ByteBuffer.allocateDirect(64);
        for (int i = 0; i < 16; i++) atlas.putInt(0xff80b080);
        atlas.flip();
        NativeDx12.updateTerrainAtlas(new ByteBuffer[]{atlas}, 4, 4, false, 1);
        var light = ByteBuffer.allocateDirect(1024);
        for (int i = 0; i < 256; i++) light.putInt(-1);
        light.flip();
        NativeDx12.updateLightmap(light);
        var vertices = ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder());
        // Lower 80% of the screen; the top rows stay sky for the verification pixel.
        for (float[] p : new float[][]{{-.9f, -.9f}, {.9f, -.9f}, {.9f, .6f}, {-.9f, .6f}})
            vertices.putShort((short) Math.round((p[0] + 16) * 1024)).putShort((short) Math.round((p[1] + 16) * 1024))
                    .putShort((short) Math.round(16.25 * 1024)).putShort((short) 0xf0f0).putInt(-1)
                    .putShort((short) 32768).putShort((short) 32768).putInt(0x01007f00);
        vertices.flip();
        var indices = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder());
        for (int i : new int[]{0, 1, 2, 2, 3, 0}) indices.putInt(i);
        indices.flip();
        int quads = 6;
        long[] keys = new long[quads];
        for (int i = 0; i < quads; i++) {
            keys[i] = i + 1;
            NativeDx12.updateTerrainSection(i + 1, 0, 0, 0, vertices, 4, indices, new int[]{6, 0, 0, 0});
        }
        NativeDx12.setVisibleSections(keys, quads, true);
        float[] matrix = new float[28];
        new Matrix4f().get(matrix);
        matrix[20] = 100;
        matrix[21] = 200;
        for (int i = 24; i < 28; i++) matrix[i] = 1;
        return matrix;
    }

    static void spin(double ms) {
        long end = System.nanoTime() + (long) (ms * 1e6);
        while (System.nanoTime() < end) Thread.onSpinWait();
    }

    static int program(String vertex, String fragment) {
        int vs = shader(GL20.GL_VERTEX_SHADER, vertex), fs = shader(GL20.GL_FRAGMENT_SHADER, fragment), p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) throw new AssertionError(GL20.glGetProgramInfoLog(p));
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        return p;
    }

    static int shader(int type, String source) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, source);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) throw new AssertionError(GL20.glGetShaderInfoLog(s));
        return s;
    }

    void close() {
        GL11.glFinish();
        if (pendingGlSync != 0) { GL32.glDeleteSync(pendingGlSync); NativeDx12.signalInteropFence(pendingGlValue); }
        capture.close();
        color.close();
        WGLNVDXInterop.wglDXUnregisterObjectNV(device, depthObject);
        WGLNVDXInterop.wglDXCloseDeviceNV(device);
        NativeDx12.shutdownInterop();
        GL.setCapabilities(null);
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }
}
