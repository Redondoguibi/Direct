package com.redondoguibi.direct;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.WGLNVDXInterop;
import org.lwjgl.system.MemoryStack;

/**
 * GPU cost and image equivalence of world-pass sources (Raydiance's prepared pack sources, see PackSourceDumpTest),
 * without Minecraft. A fixed 1920x1080 scene (sky band, perspective terrain depth, varied colors) is written into the
 * shared images through WGL like the world capture; each source set runs through Direct's real world graph with
 * Minecraft-like frame uniforms and the dump's option values. Prints the median GPU time of the passes and, for every
 * set after the first, the per-channel difference of the final RGBA8 image against the first set.
 *
 * <p>Arguments: dll, then one or more source directories (world-N.hlsl, options.txt). Not a JUnit test.</p>
 */
public final class LusPassBench {
    static final int W = 1920, H = 1080, FLOATS = 252, FRAMES = 90, WARMUP = 20;

    long window, device, colorObject, depthObject;
    int sharedColor, sharedDepth, fbo;
    long frame;
    final ByteBuffer sceneColor = ByteBuffer.allocateDirect(W * H * 4);
    final FloatBuffer sceneDepth = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        var bench = new LusPassBench();
        bench.open();
        try {
            byte[] reference = null;
            for (int i = 1; i < args.length; i++) {
                Path dir = Path.of(args[i]);
                var result = bench.run(dir);
                String diff = "";
                if (reference == null) reference = result.image;
                else diff = compare(reference, result.image);
                System.out.printf(Locale.ROOT, "sources=%s passes=%d gpuImportMs=%.3f gpuPassesMs=%.3f %s%n",
                        dir.getFileName(), result.passes, result.importMs, result.passesMs, diff);
            }
        } finally {
            bench.close();
        }
    }

    record Result(int passes, double importMs, double passesMs, byte[] image) {}

    void open() {
        if (!GLFW.glfwInit()) throw new AssertionError("GLFW");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, 0);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        window = GLFW.glfwCreateWindow(32, 32, "Lus pass bench", 0, 0);
        if (window == 0) throw new AssertionError("GL window");
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        long[] handles = NativeDx12.initializeInterop(W, H, true, 16, 8, 64);
        device = WGLNVDXInterop.wglDXOpenDeviceNV(handles[0]);
        if (device == 0) throw new AssertionError("WGL device");
        var color = InteropColorTarget.create(device, handles[1], W, H, true);
        if (!color.direct) throw new AssertionError(color.fallbackReason);
        sharedColor = color.texture;
        colorObject = color.object;
        sharedDepth = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sharedDepth);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        depthObject = WGLNVDXInterop.wglDXRegisterObjectNV(device, NativeDx12.acquireDepthBridgeTarget(), sharedDepth,
                GL11.GL_TEXTURE_2D, WGLNVDXInterop.WGL_ACCESS_READ_WRITE_NV);
        if (depthObject == 0) throw new AssertionError("depth bridge registration");
        NativeDx12.configureDirectBridge(true);
        NativeDx12.setProfilingEnabled(true);
        fbo = GL30.glGenFramebuffers();
        var atlas = ByteBuffer.allocateDirect(64);
        for (int i = 0; i < 16; i++) atlas.putInt(-1);
        NativeDx12.updateTerrainAtlas(new ByteBuffer[]{atlas.flip()}, 4, 4, false, 1);
        var light = ByteBuffer.allocateDirect(1024);
        for (int i = 0; i < 256; i++) light.putInt(-1);
        NativeDx12.updateLightmap(light.flip());
        float[] matrix = new float[28];
        new Matrix4f().get(matrix);
        if (NativeDx12.renderInterop(matrix, 0, 0, 0, 256, frame, 0, new float[0], false, true) != 0) throw new AssertionError("terrain");
        long value = ++frame;
        if (!NativeDx12.waitInteropFrame(value)) throw new AssertionError("terrain wait");
        NativeDx12.signalInteropFence(value);
        buildScene();
    }

    /** Sky in the top 38% of rows; below, a ground plane's perspective depth with a few nearer "walls". */
    void buildScene() {
        Matrix4f projection = projection();
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int glRow = y; // GL rows: y = 0 is the bottom of the screen
                float v = glRow / (float) (H - 1);
                float depth = 1.0f;
                if (v < 0.62f) {
                    // Ground at eye height 1.62 below the camera, looking at the horizon.
                    float ndcY = v * 2 - 1;
                    float viewZ = -1.62f / Math.max(0.0005f, -ndcY * (float) Math.tan(Math.toRadians(35)));
                    if (((x / 160) & 3) == 1 && v > 0.25f) viewZ = Math.max(viewZ, -6f - (x % 160) * 0.05f); // a nearer wall
                    viewZ = Math.max(viewZ, -760f);
                    var clip = projection.transform(new org.joml.Vector4f(0, 0, viewZ, 1));
                    depth = (clip.z / clip.w) * 0.5f + 0.5f;
                }
                int i = y * W + x;
                sceneDepth.put(i, depth);
                int r = (int) (90 + 120 * Math.sin(x * 0.013) * Math.cos(y * 0.007)) & 255;
                int g = (int) (110 + 100 * Math.cos(x * 0.009 + y * 0.004)) & 255;
                int b = depth >= 1 ? 230 : (int) (60 + 50 * Math.sin((x ^ y) * 0.05)) & 255;
                sceneColor.put(i * 4, (byte) r).put(i * 4 + 1, (byte) g).put(i * 4 + 2, (byte) b).put(i * 4 + 3, (byte) 255);
            }
        }
    }

    static Matrix4f projection() {
        return new Matrix4f().setPerspective((float) Math.toRadians(70), W / (float) H, 0.05f, 768f);
    }

    /** Minecraft-like frame block (Raydiance FrameUniforms layout) plus the dump's 32 option values at 32..63. */
    static float[] uniforms(Path dir, int passes) throws Exception {
        float[] frame = new float[FLOATS];
        List<String> options = Files.readAllLines(dir.resolve("options.txt"));
        for (int i = 0; i < Math.min(32, options.size()); i++)
            if (!options.get(i).isBlank()) frame[32 + i] = Float.parseFloat(options.get(i).trim());
        Matrix4f projection = projection();
        Matrix4f view = new Matrix4f().rotateX((float) Math.toRadians(8)).rotateY((float) Math.toRadians(30));
        projection.get(frame, 64);
        new Matrix4f(projection).invert().get(frame, 80);
        new Matrix4f(view).invert().get(frame, 96);
        view.get(frame, 112);
        Vector3f sun = view.transformDirection(new Vector3f(0.35f, 0.85f, 0.25f).normalize(), new Vector3f());
        // LUS_BENCH_SCENE: day (default), night (daylight 0.05, sun below the horizon) or rain (day with full rain).
        String scene = System.getenv().getOrDefault("LUS_BENCH_SCENE", "day");
        boolean night = scene.equals("night");
        if (night) view.transformDirection(new Vector3f(-0.35f, -0.85f, 0.25f).normalize(), sun);
        frame[128] = sun.x; frame[129] = sun.y; frame[130] = sun.z; frame[131] = night ? 0.05f : 0.9f;
        frame[132] = scene.equals("rain") ? 1 : 0; frame[133] = scene.equals("rain") ? 0.4f : 0; frame[134] = 123.4f;
        frame[135] = night ? 16500 : 4000;
        frame[136] = 10.5f; frame[137] = 72.0f; frame[138] = -3.25f; frame[139] = 0; frame[140] = 1; frame[141] = 0;
        frame[144] = 0.47f; frame[145] = 0.65f; frame[146] = 1.0f; frame[147] = 1;
        float[] all = new float[FLOATS * passes];
        for (int p = 0; p < passes; p++) System.arraycopy(frame, 0, all, p * FLOATS, FLOATS);
        return all;
    }

    Result run(Path dir) throws Exception {
        List<String> sources = new ArrayList<>();
        for (int i = 0; Files.exists(dir.resolve("world-" + i + ".hlsl")); i++) sources.add(Files.readString(dir.resolve("world-" + i + ".hlsl")));
        if (sources.isEmpty()) throw new AssertionError("no world-N.hlsl in " + dir);
        NativeDx12.configureWorldPasses(sources.toArray(String[]::new));
        float[] uniforms = uniforms(dir, sources.size());
        double[] imports = new double[FRAMES], passes = new double[FRAMES];
        byte[] image = null;
        for (int f = 0; f < WARMUP + FRAMES; f++) {
            writeScene();
            if (NativeDx12.renderWorldInterop(uniforms, frame) != 0) throw new AssertionError("world frame");
            long value = ++frame;
            if (!NativeDx12.waitInteropFrame(value)) throw new AssertionError("world wait");
            NativeDx12.signalInteropFence(value);
            double[] gpu = NativeDx12.worldFrameMetrics();
            if (f >= WARMUP && gpu != null) { imports[f - WARMUP] = gpu[0]; passes[f - WARMUP] = gpu[1]; }
            if (f == WARMUP + FRAMES - 1) image = readOutput();
        }
        Arrays.sort(imports);
        Arrays.sort(passes);
        return new Result(sources.size(), imports[FRAMES / 2], passes[FRAMES / 2], image);
    }

    /** The world output replaces the scene color, so the capture is repeated before every frame. */
    void writeScene() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var objects = stack.pointers(colorObject, depthObject);
            if (!WGLNVDXInterop.wglDXLockObjectsNV(device, objects)) throw new AssertionError("lock scene");
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sharedColor);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, W, H, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, sceneColor);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sharedDepth);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, W, H, GL11.GL_RED, GL11.GL_FLOAT, sceneDepth);
            if (!WGLNVDXInterop.wglDXUnlockObjectsNV(device, objects)) throw new AssertionError("unlock scene");
        }
        GL11.glFinish();
    }

    byte[] readOutput() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var objects = stack.pointers(colorObject);
            if (!WGLNVDXInterop.wglDXLockObjectsNV(device, objects)) throw new AssertionError("lock output");
            try {
                var image = ByteBuffer.allocateDirect(W * H * 4);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, sharedColor);
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, image);
                byte[] bytes = new byte[W * H * 4];
                image.get(bytes);
                return bytes;
            } finally {
                WGLNVDXInterop.wglDXUnlockObjectsNV(device, objects);
                GL11.glFinish();
            }
        }
    }

    static String compare(byte[] reference, byte[] image) {
        int max = 0;
        long over1 = 0, differing = 0;
        for (int i = 0; i < reference.length; i++) {
            if ((i & 3) == 3) continue; // alpha is the scene alpha in both
            int d = Math.abs(Byte.toUnsignedInt(reference[i]) - Byte.toUnsignedInt(image[i]));
            if (d > 0) differing++;
            if (d > 1) over1++;
            max = Math.max(max, d);
        }
        return String.format(Locale.ROOT, "maxDiff=%d channelsDiffering=%d channelsOver1=%d of %d", max, differing, over1,
                reference.length / 4 * 3);
    }

    void close() {
        GL11.glFinish();
        WGLNVDXInterop.wglDXUnregisterObjectNV(device, colorObject);
        WGLNVDXInterop.wglDXUnregisterObjectNV(device, depthObject);
        WGLNVDXInterop.wglDXCloseDeviceNV(device);
        NativeDx12.shutdownInterop();
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }
}
