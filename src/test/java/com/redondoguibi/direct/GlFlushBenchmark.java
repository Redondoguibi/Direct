package com.redondoguibi.direct;

import java.nio.*;
import java.util.Locale;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

/**
 * Reproduces the in-game DX12 wait. A frame tail of short OpenGL draws interleaved with CPU work
 * (entities, GUI) precedes the next DX12 terrain submission, on which the render thread blocks.
 * Compares leaving submission to the driver (until the "swap" flush) with a flush per stage, in
 * alternating A/B/B/A phases. Never launches Minecraft.
 */
public final class GlFlushBenchmark {
    private static final String VS = "#version 330 core\nconst vec2 p[3]=vec2[](vec2(-1,-1),vec2(3,-1),vec2(-1,3));void main(){gl_Position=vec4(p[gl_VertexID],0,1);}";
    private static final String FS = "#version 330 core\nuniform float seed;out vec4 color;void main(){vec2 u=gl_FragCoord.xy*0.001+seed;float a=0.0;"
            + "for(int i=0;i<4;i++){a+=sin(u.x*float(i)+a)*cos(u.y-a);}color=vec4(fract(a),0,0,1);}";

    public static void main(String[] args) {
        System.load(args[0]);
        double targetGlMs = args.length > 1 ? Double.parseDouble(args[1]) : 12.0;
        if (!GLFW.glfwInit()) throw new AssertionError("GLFW");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, 0);
        long window = GLFW.glfwCreateWindow(32, 32, "Direct flush benchmark", 0, 0);
        if (window == 0) throw new AssertionError("GL window");
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        int target = GL11.glGenTextures(), fbo = GL30.glGenFramebuffers(), vao = GL30.glGenVertexArrays();
        int program = program();
        int seed = GL20.glGetUniformLocation(program, "seed");
        NativeDx12.initializeInterop(1920, 1080, false, 16, 8, 64);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, target);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1920, 1080, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target, 0);
            GL11.glViewport(0, 0, 1920, 1080);
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vao);
            for (int i = 0; i < 5; i++) draw(seed, i);
            GL11.glFinish();
            long t = System.nanoTime();
            for (int i = 0; i < 100; i++) draw(seed, i);
            GL11.glFinish();
            double drawMs = (System.nanoTime() - t) / 1e6 / 100;
            int draws = Math.max(1, (int) Math.round(targetGlMs / drawMs));
            System.out.printf(Locale.ROOT, "glDrawMs=%.4f drawsPerFrame=%d queuedGlMs~%.2f%n", drawMs, draws, drawMs * draws);
            float[] matrix = scene();
            long frame = 0;
            for (boolean flush : new boolean[]{false, true, true, false}) frame = phase(flush, seed, draws, matrix, frame);
        } finally {
            GL11.glFinish();
            NativeDx12.shutdownInterop();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL30.glDeleteFramebuffers(fbo);
            GL30.glDeleteVertexArrays(vao);
            GL20.glDeleteProgram(program);
            GL11.glDeleteTextures(target);
            GL.setCapabilities(null);
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    private static long phase(boolean flush, int seed, int draws, float[] matrix, long frame) {
        int samples = 90, warmup = 20;
        double[] wait = new double[samples], gpu = new double[samples], period = new double[samples];
        long last = System.nanoTime();
        for (int i = 0; i < samples + warmup; i++, frame++) {
            long now = System.nanoTime();
            if (i >= warmup) period[i - warmup] = (now - last) / 1e6;
            last = now;
            for (int d = 0; d < draws; d++) {
                draw(seed, d);
                if (flush) GL11.glFlush(); // Dx12GlInterop.flushAfterStage()
                spin(1.5);
            }
            GL11.glFlush(); // the buffer swap
            spin(2.0);      // next frame up to the DX12 submission
            if (NativeDx12.renderInterop(matrix, 0, 0, 0, 256, frame, 0, new float[0], false, true) != 0)
                throw new AssertionError("Submit");
            long before = System.nanoTime();
            if (!NativeDx12.waitInteropFrame(frame + 1)) throw new AssertionError("Fence");
            double waited = (System.nanoTime() - before) / 1e6;
            double[] metrics = NativeDx12.frameMetrics();
            NativeDx12.signalInteropFence(frame + 1);
            if (i >= warmup) {
                wait[i - warmup] = waited;
                gpu[i - warmup] = metrics != null ? metrics[0] : -1;
            }
        }
        java.util.Arrays.sort(wait);
        java.util.Arrays.sort(gpu);
        java.util.Arrays.sort(period);
        System.out.printf(Locale.ROOT, "flushPerStage=%s waitP50=%.4f waitP95=%.4f dxGpuP50=%.4f frameP50=%.4f frameP95=%.4f%n",
                flush, wait[samples / 2], wait[samples * 95 / 100], gpu[samples / 2], period[samples / 2], period[samples * 95 / 100]);
        return frame;
    }

    private static float[] scene() {
        var atlas = ByteBuffer.allocateDirect(64);
        for (int i = 0; i < 16; i++) atlas.putInt(-1);
        atlas.flip();
        NativeDx12.updateTerrainAtlas(new ByteBuffer[]{atlas}, 4, 4, false, 1);
        var light = ByteBuffer.allocateDirect(1024);
        for (int i = 0; i < 256; i++) light.putInt(-1);
        light.flip();
        NativeDx12.updateLightmap(light);
        var vertices = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        for (float[] p : new float[][]{{-.9f, -.9f}, {.9f, -.9f}, {.9f, .9f}, {-.9f, .9f}})
            vertices.putShort((short) Math.round((p[0] + 16) * 1024)).putShort((short) Math.round((p[1] + 16) * 1024))
                    .putShort((short) Math.round(16.25 * 1024)).putShort((short) 0xf0f0).putInt(-1)
                    .putShort((short) 32768).putShort((short) 32768);
        vertices.flip();
        var indices = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder());
        for (int i : new int[]{0, 1, 2, 2, 3, 0}) indices.putInt(i);
        indices.flip();
        long[] keys = new long[16];
        for (int i = 0; i < 16; i++) {
            keys[i] = i + 1;
            NativeDx12.updateTerrainSection(i + 1, 0, 0, 0, vertices, 4, indices, new int[]{6, 0, 0});
        }
        NativeDx12.setVisibleSections(keys, 16, true);
        NativeDx12.setProfilingEnabled(true);
        float[] matrix = new float[28];
        new Matrix4f().get(matrix);
        matrix[20] = 100;
        matrix[21] = 200;
        for (int i = 24; i < 28; i++) matrix[i] = 1;
        return matrix;
    }

    private static void draw(int seed, int index) {
        GL20.glUniform1f(seed, index * 0.37f);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    private static void spin(double ms) {
        long end = System.nanoTime() + (long) (ms * 1e6);
        while (System.nanoTime() < end) Thread.onSpinWait();
    }

    private static int program() {
        int vs = shader(GL20.GL_VERTEX_SHADER, VS), fs = shader(GL20.GL_FRAGMENT_SHADER, FS), p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) throw new AssertionError(GL20.glGetProgramInfoLog(p));
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        return p;
    }

    private static int shader(int type, String source) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, source);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) throw new AssertionError(GL20.glGetShaderInfoLog(s));
        return s;
    }
}
