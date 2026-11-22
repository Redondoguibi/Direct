package com.redondoguibi.direct.backend;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

import org.lwjgl.system.MemoryUtil;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.redondoguibi.direct.Direct;
import com.redondoguibi.direct.NativeLibrary;

import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.AbstractTexture;

/**
 * Phase 1 shadow backend: mirrors every blaze3d texture, framebuffer, state change and draw onto a private D3D12 device
 * (dx12_backend.cpp) while OpenGL keeps rendering the game. {@link Dx12ShadowProbe} compares both images. Enabled only
 * with {@code -Ddirect.phase1.shadow=true}; the mixins that call in here are not applied otherwise.
 * Render thread only.
 */
public final class Dx12Shadow {
    public static final boolean ENABLED = Boolean.getBoolean("direct.phase1.shadow");
    /** -Ddirect.phase1.present=true: the window shows the DX12 image through DXGI instead of OpenGL's back buffer. */
    static final boolean PRESENT = ENABLED && Boolean.getBoolean("direct.phase1.present");
    /** -Ddirect.phase1.skipGl=true (with present): OpenGL draws are cancelled; only DX12 renders. */
    public static final boolean SKIP_GL = PRESENT && Boolean.getBoolean("direct.phase1.skipGl");
    private static final int GL_TEXTURE_2D = 3553;

    private static boolean ready, broken;
    // ---- Mirrored OpenGL state ---------------------------------------------------------------------------------
    private static int activeUnit;
    private static final int[] bound = new int[32];
    private static int unpackRowLength, unpackSkipPixels, unpackSkipRows, unpackAlignment = 4;
    private static int drawFramebuffer, readFramebuffer;
    private static boolean blend;
    private static int srcRgb = 1, dstRgb = 0, srcAlpha = 1, dstAlpha = 0, equation = 32774;
    private static boolean depthTest, depthMask = true;
    private static int depthFunc = 513;
    private static boolean cull;
    private static int colorMask = 15;
    private static boolean scissor;
    private static int scissorX, scissorY, scissorW, scissorH;
    private static int viewportX, viewportY, viewportW, viewportH;
    private static boolean polygonOffset;
    private static float offsetFactor, offsetUnits;
    private static float clearR, clearG, clearB, clearA;
    private static float clearDepth = 1;
    private static Object program;
    private static final Map<Integer, float[]> params = new HashMap<>();
    // ---- Programs ----------------------------------------------------------------------------------------------
    private record ProgramInfo(int id, int cbufferBytes, String[] inputNames, int[] inputLocations,
                               String[] uniformNames, int[] uniformOffsets, String[] uniformTypes, String[] samplers) {}
    private static final Map<String, ProgramInfo> programs = new HashMap<>();
    private static final Map<String, String> unsupported = new LinkedHashMap<>();
    private static final ByteBuffer cbuffer = ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN);
    // ---- Statistics --------------------------------------------------------------------------------------------
    static long draws, skippedDraws, errors;
    /** Per frame: VertexBuffer.draw calls, draws handed to native, and the native count at frame start. */
    static int frameCalls, frameDraws, lastCalls, lastDraws, frameTerrain, lastTerrain;
    static long nativeAtFrameStart, lastNative;
    // ---- Trace (-Ddirect.phase1.trace=true): every clear and draw of the current frame ----------------------------
    static final boolean TRACE = Boolean.getBoolean("direct.phase1.trace");
    static final StringBuilder trace = new StringBuilder();
    private static final Map<String, Integer> errorMessages = new LinkedHashMap<>();

    private Dx12Shadow() {}

    /** Lazily creates the device on the render thread at the first mirrored call. */
    private static boolean live() {
        if (!ENABLED || broken) return false;
        if (ready) return true;
        try {
            NativeLibrary.load();
            Dx12BackendNative.create(Boolean.getBoolean("direct.phase1.debug"));
            ready = true;
            Direct.LOGGER.info("Direct phase1 shadow backend created");
        } catch (IOException | RuntimeException | LinkageError ex) {
            broken = true;
            Direct.LOGGER.error("Direct phase1 shadow backend could not start", ex);
        }
        return ready;
    }

    private static void fail(String where, Throwable error) {
        errors++;
        String key = where + ": " + error.getMessage();
        int seen = errorMessages.merge(key, 1, Integer::sum);
        if (seen == 1 && errorMessages.size() <= 64) Direct.LOGGER.warn("Direct phase1 shadow {}", key);
    }

    static Map<String, Integer> errorMessages() { return errorMessages; }
    static Map<String, String> unsupported() { return unsupported; }

    // ---- State hooks (GlStateManager HEAD) ---------------------------------------------------------------------
    public static void blend(boolean enabled) { blend = enabled; }
    public static void blendFunc(int src, int dst) { srcRgb = srcAlpha = src; dstRgb = dstAlpha = dst; }
    public static void blendFuncSeparate(int src, int dst, int srcA, int dstA) { srcRgb = src; dstRgb = dst; srcAlpha = srcA; dstAlpha = dstA; }
    public static void blendEquation(int mode) { equation = mode; }
    public static void depthTest(boolean enabled) { depthTest = enabled; }
    public static void depthFunc(int func) { depthFunc = func; }
    public static void depthMask(boolean flag) { depthMask = flag; }
    public static void cull(boolean enabled) { cull = enabled; }
    public static void colorMask(boolean r, boolean g, boolean b, boolean a) { colorMask = (r ? 1 : 0) | (g ? 2 : 0) | (b ? 4 : 0) | (a ? 8 : 0); }
    public static void scissor(boolean enabled) { scissor = enabled; }
    public static void scissorBox(int x, int y, int w, int h) { scissorX = x; scissorY = y; scissorW = w; scissorH = h; }
    public static void viewport(int x, int y, int w, int h) { viewportX = x; viewportY = y; viewportW = w; viewportH = h; }
    public static void polygonOffset(boolean enabled) { polygonOffset = enabled; }
    public static void polygonOffset(float factor, float units) { offsetFactor = factor; offsetUnits = units; }
    public static void clearColor(float r, float g, float b, float a) { clearR = r; clearG = g; clearB = b; clearA = a; }
    public static void clearDepth(double depth) { clearDepth = (float) depth; }
    public static void activeTexture(int unit) { activeUnit = unit - 33984; }
    public static void bindTexture(int texture) { if (activeUnit >= 0 && activeUnit < bound.length) bound[activeUnit] = texture; }
    public static void pixelStore(int name, int value) {
        switch (name) {
            case 3314 -> unpackRowLength = value;
            case 3315 -> unpackSkipRows = value;
            case 3316 -> unpackSkipPixels = value;
            case 3317 -> unpackAlignment = value;
            default -> { }
        }
    }

    public static void bindFramebuffer(int target, int framebuffer) {
        if (target == 36160 || target == 36009) drawFramebuffer = framebuffer;
        if (target == 36160 || target == 36008) readFramebuffer = framebuffer;
    }

    public static void framebufferTexture(int target, int attachment, int textureTarget, int texture) {
        if (!live()) return;
        int framebuffer = target == 36008 ? readFramebuffer : drawFramebuffer;
        int slot = attachment == 36064 ? 0 : attachment == 36096 || attachment == 33306 ? 1 : -1;
        if (slot < 0 || (textureTarget != GL_TEXTURE_2D && texture != 0)) return;
        try { Dx12BackendNative.attach(framebuffer, slot, texture); } catch (RuntimeException ex) { fail("attach", ex); }
    }

    public static void deleteFramebuffer(int framebuffer) {
        if (!live()) return;
        try { Dx12BackendNative.deleteFramebuffer(framebuffer); } catch (RuntimeException ex) { fail("deleteFramebuffer", ex); }
    }

    public static void clear(int mask) {
        if (!live()) return;
        if (TRACE) trace.append(String.format("clear fbo=%d mask=%x color=%.3f,%.3f,%.3f,%.3f depth=%.3f scissor=%s colorMask=%d%n",
                drawFramebuffer, mask, clearR, clearG, clearB, clearA, clearDepth,
                scissor ? scissorX + "," + scissorY + "," + scissorW + "x" + scissorH : "off", colorMask));
        try {
            Dx12BackendNative.clear(drawFramebuffer, mask, clearR, clearG, clearB, clearA, clearDepth,
                    new int[]{scissor ? 1 : 0, scissorX, scissorY, scissorW, scissorH}, colorMask);
        } catch (RuntimeException ex) {
            fail("clear", ex);
        }
    }

    // ---- Textures ----------------------------------------------------------------------------------------------
    private static int boundTexture() { return activeUnit >= 0 && activeUnit < bound.length ? bound[activeUnit] : 0; }

    public static void texParameter(int target, int name, float value) {
        if (target != GL_TEXTURE_2D || !live()) return;
        int texture = boundTexture();
        if (texture == 0) return;
        // {minFilter, magFilter, wrapS, wrapT, maxLevel, lodBias, minLod, maxLod} with OpenGL's defaults.
        float[] p = params.computeIfAbsent(texture, id -> new float[]{9986, 9729, 10497, 10497, 1000, 0, -1000, 1000});
        int index = switch (name) {
            case 10241 -> 0; case 10240 -> 1; case 10242 -> 2; case 10243 -> 3; case 33085 -> 4;
            case 34049 -> 5; case 33082 -> 6; case 33083 -> 7;
            default -> -1;
        };
        if (index < 0) return;
        p[index] = value;
        try {
            Dx12BackendNative.textureParams(texture, (int) p[0], (int) p[1], (int) p[2], (int) p[3], (int) p[4], p[5], p[6], p[7]);
        } catch (RuntimeException ex) {
            fail("texParameter", ex);
        }
    }

    public static void texImage2D(int target, int level, int internalFormat, int width, int height, int format, int type,
            IntBuffer pixels) {
        if (target != GL_TEXTURE_2D || !live()) return;
        int texture = boundTexture();
        if (texture == 0) return;
        try {
            Dx12BackendNative.textureImage(texture, level, internalFormat, width, height);
            if (pixels != null && type == 5121)
                Dx12BackendNative.textureSubImage(texture, level, 0, 0, width, height, format, MemoryUtil.memAddress(pixels),
                        unpackRowLength, unpackSkipPixels, unpackSkipRows, unpackAlignment);
        } catch (RuntimeException ex) {
            fail("texImage2D", ex);
        }
    }

    public static void texSubImage2D(int target, int level, int x, int y, int width, int height, int format, int type, long pixels) {
        if (target != GL_TEXTURE_2D || type != 5121 || pixels == 0 || !live()) return;
        int texture = boundTexture();
        if (texture == 0) return;
        try {
            Dx12BackendNative.textureSubImage(texture, level, x, y, width, height, format, pixels,
                    unpackRowLength, unpackSkipPixels, unpackSkipRows, unpackAlignment);
        } catch (RuntimeException ex) {
            fail("texSubImage2D", ex);
        }
    }

    /** GlStateManager._upload: rows of exactly {@code width} pixels, 4-byte aligned for RGBA. */
    public static void upload(int level, int x, int y, int width, int height, int glFormat, int components, IntBuffer pixels) {
        if (pixels == null || !live()) return;
        int texture = boundTexture();
        if (texture == 0) return;
        try {
            Dx12BackendNative.textureSubImage(texture, level, x, y, width, height, glFormat, MemoryUtil.memAddress(pixels),
                    width, 0, 0, components);
        } catch (RuntimeException ex) {
            fail("upload", ex);
        }
    }

    public static void deleteTexture(int texture) {
        for (int i = 0; i < bound.length; i++) if (bound[i] == texture) bound[i] = 0;
        params.remove(texture);
        if (!live()) return;
        try { Dx12BackendNative.textureDelete(texture); } catch (RuntimeException ex) { fail("deleteTexture", ex); }
    }

    // ---- Programs ----------------------------------------------------------------------------------------------
    public static void applied(Object shader) { program = shader; }

    private static String strip(String name) {
        return name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
    }

    private static ProgramInfo program(String kind, String rawName) {
        String name = strip(rawName);
        String key = kind + "/" + name;
        if (programs.containsKey(key)) return programs.get(key);
        ProgramInfo info = null;
        try {
            String base = "/assets/direct/dx12/" + kind + "/" + name + "/";
            byte[] vertex = read(base + "vert.hlsl"), fragment = read(base + "frag.hlsl"), layout = read(base + "program.json");
            if (vertex == null || fragment == null || layout == null) {
                unsupported.putIfAbsent(key, "no translated HLSL");
            } else {
                JsonObject json = JsonParser.parseReader(new InputStreamReader(new java.io.ByteArrayInputStream(layout),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                var inputs = json.getAsJsonArray("inputs");
                String[] inputNames = new String[inputs.size()];
                int[] inputLocations = new int[inputs.size()];
                for (int i = 0; i < inputs.size(); i++) {
                    inputNames[i] = inputs.get(i).getAsJsonObject().get("name").getAsString();
                    inputLocations[i] = inputs.get(i).getAsJsonObject().get("location").getAsInt();
                }
                var uniforms = json.getAsJsonArray("uniforms");
                String[] uniformNames = new String[uniforms.size()];
                String[] uniformTypes = new String[uniforms.size()];
                int[] uniformOffsets = new int[uniforms.size()];
                for (int i = 0; i < uniforms.size(); i++) {
                    var u = uniforms.get(i).getAsJsonObject();
                    uniformNames[i] = u.get("name").getAsString();
                    uniformTypes[i] = u.get("type").getAsString();
                    uniformOffsets[i] = u.get("offset").getAsInt();
                }
                var samplerArray = json.getAsJsonArray("samplers");
                String[] samplers = new String[samplerArray.size()];
                for (int i = 0; i < samplers.length; i++) samplers[i] = samplerArray.get(i).getAsString();
                int cbufferBytes = json.get("cbufferSize").getAsInt();
                int id = Dx12BackendNative.createProgram(key, vertex, fragment, cbufferBytes, samplers.length);
                info = new ProgramInfo(id, cbufferBytes, inputNames, inputLocations, uniformNames, uniformOffsets, uniformTypes, samplers);
            }
        } catch (IOException | RuntimeException ex) {
            unsupported.putIfAbsent(key, String.valueOf(ex.getMessage()));
            Direct.LOGGER.warn("Direct phase1 shadow cannot load program {}", key, ex);
        }
        programs.put(key, info);
        return info;
    }

    private static byte[] read(String path) throws IOException {
        try (InputStream in = Dx12Shadow.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    // ---- Meshes and draws --------------------------------------------------------------------------------------
    /**
     * What a VertexBuffer last uploaded; the MeshData is closed right after upload. Static buffers (terrain sections,
     * sky, clouds) live in native mesh storage ({@code id}); dynamic ones (BufferUploader) keep a Java copy that each
     * draw sends to the upload ring.
     */
    public static final class Mesh {
        ByteBuffer vertices, indices;
        int vertexBytes, indexBytes, indexCount, glMode, id;
        /** The VertexBuffer's OpenGL vertex buffer name, for -Ddirect.phase2.verifyMeshes. */
        public int glBuffer;
        /** The VertexBuffer's own index count at draw time, for -Ddirect.phase2.verifyMeshes. */
        public int glIndexCount;
        boolean sequentialIndices;
        boolean wide;
        VertexFormat format;
        VertexFormat.IndexType indexType;
    }

    private static ByteBuffer scratchIndices;

    public static void upload(Mesh mesh, MeshData data, boolean persistent) {
        if (!live()) return;
        try {
            MeshData.DrawState state = data.drawState();
            ByteBuffer source = data.vertexBuffer();
            mesh.format = state.format();
            VertexFormat.Mode mode = state.mode();
            mesh.glMode = mode == VertexFormat.Mode.TRIANGLE_FAN ? 4 : mode.asGLMode;
            mesh.indexType = state.indexType();
            ByteBuffer indexSource = data.indexBuffer();
            if (persistent) {
                ByteBuffer indices;
                if (indexSource != null) {
                    mesh.sequentialIndices = false;
                    indices = indexSource;
                    mesh.wide = mesh.indexType == VertexFormat.IndexType.INT;
                    mesh.indexCount = state.indexCount();
                } else {
                    scratchIndices = sequential(scratchIndices, mesh, mode, state.vertexCount(), state.indexCount());
                    indices = scratchIndices;
                }
                // A null vertex buffer keeps the previous OpenGL contents; this mirror has none to keep.
                mesh.vertexBytes = source == null ? 0 : source.remaining();
                mesh.indexBytes = indices.remaining();
                mesh.vertices = mesh.indices = null;
                mesh.id = Dx12BackendNative.meshUpload(mesh.id, source == null ? 0 : MemoryUtil.memAddress(source), mesh.vertexBytes,
                        MemoryUtil.memAddress(indices), mesh.indexBytes);
                return;
            }
            mesh.vertexBytes = source == null ? 0 : source.remaining();
            mesh.vertices = copy(mesh.vertices, source);
            if (indexSource != null) {
                mesh.indices = copy(mesh.indices, indexSource);
                mesh.indexBytes = indexSource.remaining();
                mesh.wide = mesh.indexType == VertexFormat.IndexType.INT;
                mesh.indexCount = state.indexCount();
            } else {
                mesh.indices = sequential(mesh.indices, mesh, mode, state.vertexCount(), state.indexCount());
            }
        } catch (RuntimeException ex) {
            fail("upload", ex);
        }
    }

    /** VertexBuffer.uploadIndexBuffer: a re-sorted index buffer for the same vertices, of the last upload's index type. */
    public static void uploadIndices(Mesh mesh, ByteBuffer indices) {
        if (!live() || indices == null) return;
        try {
            mesh.wide = mesh.indexType == VertexFormat.IndexType.INT;
            mesh.indexBytes = indices.remaining();
            if (mesh.id > 0) Dx12BackendNative.meshIndices(mesh.id, MemoryUtil.memAddress(indices), mesh.indexBytes);
            else mesh.indices = copy(mesh.indices, indices);
        } catch (RuntimeException ex) {
            fail("uploadIndices", ex);
        }
    }

    /** VertexBuffer.close. */
    public static void close(Mesh mesh) {
        mesh.vertices = mesh.indices = null;
        if (mesh.id <= 0 || !ready) return;
        try { Dx12BackendNative.meshDelete(mesh.id); } catch (RuntimeException ex) { fail("meshDelete", ex); }
        mesh.id = 0;
    }

    /**
     * Dx12GlInterop.resolvePendingFrame: the Direct terrain frame {@code readyValue} is composited into the bound
     * framebuffer, as the OpenGL composite does. The terrain renderer shares this backend's device.
     * @return true when recorded
     */
    public static boolean compositeTerrain(long readyValue, boolean writeDepth) {
        if (!live()) return false;
        try {
            if (TRACE) trace.append(String.format("terrain fbo=%d ready=%d writeDepth=%s%n", drawFramebuffer, readyValue, writeDepth));
            // OpenGL also composites the shared image unless DX12 renders alone.
            boolean done = Dx12BackendNative.compositeTerrain(drawFramebuffer, readyValue, writeDepth, !(SKIP_GL && presenting()));
            if (done) frameTerrain++;
            return done;
        } catch (RuntimeException ex) {
            fail("compositeTerrain", ex);
            return false;
        }
    }

    /** GlStateManager._glBlitFrameBuffer (RenderTarget.copyDepthFrom). */
    public static void blitFramebuffer(int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, int mask) {
        if (!live()) return;
        try {
            if (!Dx12BackendNative.blit(readFramebuffer, drawFramebuffer, new int[]{sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1}, mask))
                unsupported.putIfAbsent("blit " + (sx1 - sx0) + "x" + (sy1 - sy0) + "->" + (dx1 - dx0) + "x" + (dy1 - dy0), "scaled or partial");
        } catch (RuntimeException ex) {
            fail("blit", ex);
        }
    }

    private static ByteBuffer copy(ByteBuffer target, ByteBuffer source) {
        int bytes = source == null ? 0 : source.remaining();
        if (target == null || target.capacity() < bytes) target = ByteBuffer.allocateDirect(Math.max(bytes, 256)).order(ByteOrder.nativeOrder());
        target.clear();
        if (source != null) target.put(source.duplicate());
        target.flip();
        return target;
    }

    /** RenderSystem's shared sequential index buffers, generated as 32-bit indices into {@code target}. */
    private static ByteBuffer sequential(ByteBuffer target, Mesh mesh, VertexFormat.Mode mode, int vertexCount, int indexCount) {
        int count;
        if (mode == VertexFormat.Mode.TRIANGLE_FAN) count = Math.max(0, vertexCount - 2) * 3;
        else count = indexCount;
        int bytes = count * 4;
        if (target == null || target.capacity() < bytes)
            target = ByteBuffer.allocateDirect(Math.max(bytes, 256)).order(ByteOrder.nativeOrder());
        target.clear();
        IntBuffer out = target.asIntBuffer();
        if (mode == VertexFormat.Mode.QUADS || mode == VertexFormat.Mode.LINES) {
            boolean lines = mode == VertexFormat.Mode.LINES;
            for (int i = 0, base = 0; i + 6 <= count; i += 6, base += 4) {
                if (lines) out.put(base).put(base + 1).put(base + 2).put(base + 3).put(base + 2).put(base + 1);
                else out.put(base).put(base + 1).put(base + 2).put(base + 2).put(base + 3).put(base);
            }
        } else if (mode == VertexFormat.Mode.TRIANGLE_FAN) {
            for (int i = 1; i + 1 < vertexCount; i++) out.put(0).put(i).put(i + 1);
        } else {
            for (int i = 0; i < count; i++) out.put(i);
        }
        target.limit(bytes);
        mesh.sequentialIndices = mode == VertexFormat.Mode.QUADS;
        mesh.indexBytes = bytes;
        mesh.indexCount = count;
        mesh.wide = true;
        return target;
    }

    /** Debug: every 30th frame, each static mesh drawn is compared with OpenGL's copy of its vertices. */
    private static final boolean VERIFY_MESHES = Boolean.getBoolean("direct.phase2.verifyMeshes");
    private static long frameIndex, verified, mismatched;

    private static void verify(Mesh mesh) {
        int previous = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER_BINDING);
        org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, mesh.glBuffer);
        int size = org.lwjgl.opengl.GL15.glGetBufferParameteri(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, org.lwjgl.opengl.GL15.GL_BUFFER_SIZE);
        ByteBuffer gl = MemoryUtil.memAlloc(Math.max(size, 1)), dx = MemoryUtil.memAlloc(Math.max(size, 1));
        try {
            org.lwjgl.opengl.GL15.glGetBufferSubData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0, gl);
            int stored = Dx12BackendNative.meshGpuRead(mesh.id, 0, dx);
            verified++;
            int first = -1;
            for (int i = 0; i < Math.min(size, stored) && first < 0; i++) if (gl.get(i) != dx.get(i)) first = i;
            int badIndex = -1;
            if (mesh.sequentialIndices) {
                ByteBuffer indices = MemoryUtil.memAlloc(Math.max(mesh.indexBytes, 4));
                try {
                    int bytes = Dx12BackendNative.meshGpuRead(mesh.id, 1, indices);
                    if (bytes != mesh.indexCount * 4) badIndex = -2;
                    for (int i = 0; i < mesh.indexCount && badIndex == -1 && 4 * i + 4 <= bytes; i++) {
                        int quad = i / 6, corner = new int[]{0, 1, 2, 2, 3, 0}[i % 6];
                        if (indices.getInt(4 * i) != quad * 4 + corner) badIndex = i;
                    }
                } finally {
                    MemoryUtil.memFree(indices);
                }
            }
            if (mesh.glIndexCount != mesh.indexCount) badIndex = -3;
            if (stored != size || stored != mesh.vertexBytes || first >= 0 || badIndex != -1) {
                mismatched++;
                if (mismatched <= 20) Direct.LOGGER.warn("Direct phase2 mesh mismatch id={} glBuffer={} glBytes={} stored={} expected={} firstDiff={} badIndex={} indexCount={}/{}",
                        mesh.id, mesh.glBuffer, size, stored, mesh.vertexBytes, first, badIndex, mesh.indexCount, mesh.glIndexCount);
            }
        } finally {
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, previous);
            MemoryUtil.memFree(gl);
            MemoryUtil.memFree(dx);
        }
    }

    static String meshVerification() { return VERIFY_MESHES ? verified + " checked, " + mismatched + " mismatched" : "off"; }

    public static void draw(Mesh mesh) {
        frameCalls++;
        if (!live() || (mesh.vertices == null && mesh.id <= 0) || mesh.indexCount == 0 || mesh.format == null) return;
        if (VERIFY_MESHES && mesh.id > 0 && frameIndex % 30 == 0) verify(mesh);
        Object current = program;
        String kind, name;
        if (current instanceof ShaderInstance shader) { kind = "core"; name = shader.getName(); }
        else if (current instanceof EffectInstance effect) { kind = "program"; name = ((EffectInstanceAccess) effect).direct$name(); }
        else { skippedDraws++; if (TRACE) trace.append("skip noProgram ").append(current).append(System.lineSeparator()); return; }
        ProgramInfo info = program(kind, name);
        if (info == null) { skippedDraws++; if (TRACE) trace.append("skip ").append(kind).append('/').append(name).append(System.lineSeparator()); return; }
        try {
            // Vertex layout: format elements matched to the program's inputs by attribute name.
            List<Integer> layout = new ArrayList<>();
            VertexFormat format = mesh.format;
            for (VertexFormatElement element : format.getElements()) {
                String attribute = format.getElementName(element);
                for (int i = 0; i < info.inputNames.length; i++) {
                    if (!info.inputNames[i].equals(attribute)) continue;
                    int dxgi = dxgiFormat(element);
                    if (dxgi == 0) throw new IllegalStateException("unsupported vertex element " + element + " for " + attribute);
                    layout.add(info.inputLocations[i]);
                    layout.add(dxgi);
                    layout.add(format.getOffset(element));
                }
            }
            int[] layoutArray = layout.stream().mapToInt(Integer::intValue).toArray();
            packUniforms(current, info);
            int[] textures = new int[info.samplers.length];
            for (int i = 0; i < textures.length; i++) textures[i] = sampler(current, info.samplers[i]);
            int[] state = {
                    blend ? 1 : 0, srcRgb, dstRgb, srcAlpha, dstAlpha, equation, equation,
                    depthTest ? 1 : 0, depthFunc, depthMask ? 1 : 0,
                    cull ? 1 : 0, colorMask,
                    scissor ? 1 : 0, scissorX, scissorY, scissorW, scissorH,
                    viewportX, viewportY, viewportW, viewportH,
                    polygonOffset ? 1 : 0, Float.floatToRawIntBits(offsetFactor), Float.floatToRawIntBits(offsetUnits)};
            if (TRACE) trace.append(String.format("draw %s/%s fbo=%d indices=%d mode=%d vtx=%d stride=%d viewport=%d,%d,%dx%d scissor=%s "
                            + "blend=%s depth=%s/%d/%s cull=%s mask=%d tex=%s cb=%s%n", kind, name, drawFramebuffer, mesh.indexCount,
                    mesh.glMode, mesh.vertexBytes, format.getVertexSize(), viewportX, viewportY, viewportW, viewportH,
                    scissor ? scissorX + "," + scissorY + "," + scissorW + "x" + scissorH : "off",
                    blend ? srcRgb + "," + dstRgb + "," + srcAlpha + "," + dstAlpha + "/" + equation : "off", depthTest, depthFunc, depthMask,
                    cull, colorMask, java.util.Arrays.toString(textures), floats(info.cbufferBytes)));
            Dx12BackendNative.draw(info.id, drawFramebuffer, mesh.id, mesh.id > 0 ? 0 : MemoryUtil.memAddress(mesh.vertices),
                    mesh.vertexBytes, format.getVertexSize(), layoutArray, info.inputLocations,
                    mesh.id > 0 ? 0 : MemoryUtil.memAddress(mesh.indices),
                    mesh.indexBytes, mesh.wide, mesh.indexCount, mesh.glMode, state, cbuffer, info.cbufferBytes, textures);
            draws++;
            frameDraws++;
        } catch (RuntimeException ex) {
            fail("draw " + kind + "/" + name, ex);
        }
    }

    private static String floats(int bytes) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < Math.min(bytes, 160); i += 4) out.append(i == 0 ? "" : ",").append(String.format("%.3g", cbuffer.getFloat(i)));
        return out.append(bytes > 160 ? ",...]" : "]").toString();
    }

    private static int dxgiFormat(VertexFormatElement element) {
        int count = element.count();
        VertexFormatElement.Usage usage = element.usage();
        boolean normalized = usage == VertexFormatElement.Usage.COLOR || usage == VertexFormatElement.Usage.NORMAL;
        boolean integer = usage == VertexFormatElement.Usage.UV && element.type() != VertexFormatElement.Type.FLOAT;
        return switch (element.type()) {
            case FLOAT -> count == 1 ? 41 : count == 2 ? 16 : count == 3 ? 6 : 2;
            case UBYTE -> normalized ? 28 : integer ? 30 : 0;
            case BYTE -> normalized ? 31 : integer ? 32 : 0;
            case SHORT -> count == 2 ? (normalized ? 37 : integer ? 38 : 0) : 0;
            case USHORT -> count == 2 ? (normalized ? 35 : integer ? 36 : 0) : 0;
            case INT -> count == 2 ? 18 : 0;
            case UINT -> count == 2 ? 17 : 0;
        };
    }

    private static void packUniforms(Object current, ProgramInfo info) {
        cbuffer.clear();
        for (int i = 0; i < info.cbufferBytes; i++) cbuffer.put(i, (byte) 0);
        for (int i = 0; i < info.uniformNames.length; i++) {
            Uniform uniform = current instanceof ShaderInstance shader ? shader.getUniform(info.uniformNames[i])
                    : ((EffectInstance) current).getUniform(info.uniformNames[i]);
            if (uniform == null) continue;
            int offset = info.uniformOffsets[i];
            String type = info.uniformTypes[i];
            switch (type) {
                case "int", "ivec2", "ivec3", "ivec4" -> {
                    IntBuffer values = uniform.getIntBuffer();
                    int n = type.equals("int") ? 1 : type.charAt(4) - '0';
                    for (int c = 0; c < n && c < values.capacity(); c++) cbuffer.putInt(offset + 4 * c, values.get(c));
                }
                case "mat4" -> putFloats(uniform.getFloatBuffer(), offset, 16, 4, 4);
                case "mat3" -> putFloats(uniform.getFloatBuffer(), offset, 9, 3, 4);
                case "mat2" -> putFloats(uniform.getFloatBuffer(), offset, 4, 2, 4);
                default -> {
                    int n = type.equals("float") ? 1 : type.charAt(3) - '0';
                    putFloats(uniform.getFloatBuffer(), offset, n, n, n);
                }
            }
        }
        cbuffer.limit(info.cbufferBytes);
    }

    /** std140: each matrix column occupies a vec4 slot. */
    private static void putFloats(FloatBuffer values, int offset, int total, int columnLength, int columnStride) {
        if (values == null) return;
        for (int i = 0; i < total && i < values.capacity(); i++) {
            int column = i / columnLength, row = i % columnLength;
            cbuffer.putFloat(offset + 4 * (column * columnStride + row), values.get(i));
        }
    }

    private static int sampler(Object current, String name) {
        if (current instanceof ShaderInstance shader) {
            Object value = ((ShaderInstanceAccess) shader).direct$samplerMap().get(name);
            if (value instanceof RenderTarget target) return target.getColorTextureId();
            if (value instanceof AbstractTexture texture) return texture.getId();
            if (value instanceof Integer id) return id;
            return 0;
        }
        IntSupplier supplier = ((EffectInstanceAccess) current).direct$samplerMap().get(name);
        return supplier == null ? 0 : supplier.getAsInt();
    }

    // ---- Frame -------------------------------------------------------------------------------------------------
    /** Window.updateDisplay: everything of the frame is recorded; run it so the next frame starts empty. */
    public static void endFrame() {
        if (!ready) return;
        frameIndex++;
        lastCalls = frameCalls;
        lastDraws = frameDraws;
        lastTerrain = frameTerrain;
        frameTerrain = 0;
        long nativeNow = Dx12BackendNative.draws();
        lastNative = nativeNow - nativeAtFrameStart;
        nativeAtFrameStart = nativeNow;
        frameCalls = frameDraws = 0;
        Dx12ShadowProbe.frame();
        try {
            if (presenting()) {
                var mc = net.minecraft.client.Minecraft.getInstance();
                var window = mc.getWindow();
                int matches = Dx12BackendNative.present(org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(window.getWindow()),
                        mc.getMainRenderTarget().getColorTextureId(), window.getWidth(), window.getHeight(),
                        mc.options.enableVsync().get(), Dx12ShadowProbe.verifyDesktop());
                if (matches >= 0) Dx12ShadowProbe.desktop(matches);
            } else {
                Dx12BackendNative.flush();
            }
        } catch (RuntimeException ex) {
            fail(presenting() ? "present" : "flush", ex);
        }
        trace.setLength(0);
        if (DEBUG && debugLogged < 50) {
            String messages = Dx12BackendNative.messages();
            if (!messages.isEmpty()) {
                debugLogged++;
                Direct.LOGGER.warn("Direct DX12 backend debug layer:{}", messages.length() > 4000 ? messages.substring(0, 4000) + " ..." : messages);
            }
        }
    }

    private static final boolean DEBUG = Boolean.getBoolean("direct.phase1.debug");
    private static int debugLogged;

    /** Present mode, once the backend runs; until then OpenGL keeps showing the loading screens. */
    public static boolean presenting() { return PRESENT && ready && !broken; }

    static long meshBytes() { return ready ? Dx12BackendNative.meshBytes() : 0; }

    static void readback(int texture, ByteBuffer out) {
        Dx12BackendNative.readback(texture, out);
    }

    /** Implemented by ShaderInstanceShadowMixin. */
    public interface ShaderInstanceAccess { Map<String, Object> direct$samplerMap(); }
    /** Implemented by EffectInstanceShadowMixin. */
    public interface EffectInstanceAccess { Map<String, IntSupplier> direct$samplerMap(); String direct$name(); }
}
