package com.redondoguibi.direct;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreWin32;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.WGLCapabilities;
import org.lwjgl.opengl.WGLNVDXInterop;
import org.lwjgl.system.MemoryStack;

import com.redondoguibi.direct.api.DirectFrameContext;
import com.redondoguibi.direct.api.DirectFrameRenderEvent;
import com.redondoguibi.direct.api.DirectRenderApi;
import com.redondoguibi.direct.api.DirectRenderCapabilities;
import com.redondoguibi.direct.api.DirectRenderPass;

public final class Dx12GlInterop {
    private static final int[] NO_OBJECTS = new int[0];
    private static final int MAX_ATLAS_LEVELS = 16;
    private static final long RETRY_DELAY_NANOS = 5_000_000_000L;
    private static final String VERTEX_SHADER = """
            #version 150
            out vec2 texCoord;
            void main() {
                vec2 position = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                texCoord = position * 0.5;
                gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String FRAGMENT_SHADER = """
            #version 150
            uniform sampler2D sourceTexture;
            in vec2 texCoord;
            out vec4 color;
            void main() {
                // D3D textures use a top-left row origin; flip V when sampling in the GL compositor.
                color = texture(sourceTexture, vec2(texCoord.x, 1.0 - texCoord.y));
            }
            """;

    // GL e D3D projetam NDC +Y para o topo da viewport; apenas o intervalo Z precisa de conversão.
    // O winding CW do D3D é configurado no PSO para o sistema de coordenadas da viewport.
    private static final Matrix4f CLIP_CORRECTION = new Matrix4f().m22(0.5f).m32(0.5f);
    private static final Matrix4f CAPTURED_VIEW = new Matrix4f();
    private static final Matrix4f CAPTURED_PROJECTION = new Matrix4f();
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    private static final float[] MATRIX = new float[16];
    private static final int[] PREVIOUS_VIEWPORT = new int[4];
    private static final boolean[] PREVIOUS_COLOR_MASK = new boolean[4];
    private static final String[] NO_PASS_SOURCES = new String[0];

    private static boolean matricesCaptured;
    private static boolean lastFrameSucceeded;
    private static double cameraX;
    private static double cameraY;
    private static double cameraZ;

    private static boolean initialized;
    private static boolean disabled;
    private static boolean reportedUnsupported;
    private static boolean reportedHook;
    private static boolean wglInteropAvailable;
    private static float[] passUniformScratch = new float[0];
    private static long configuredPassRevision = -1L;
    private static volatile boolean blockAtlasDirty = true;
    private static boolean terrainCaptureEnabled;
    private static boolean gpuSemaphoreInterop;
    private static boolean reportedSemaphoreFallback;
    private static ClientLevel renderedLevel;
    private static int width;
    private static int height;
    private static long interopDevice;
    private static long interopObject;
    private static long frameIndex;
    private static long startedAtNanos;
    private static long retryAfterNanos;
    private static int texture;
    private static int vertexArray;
    private static int program;
    private static int sourceTextureUniform = -1;
    private static int interopSemaphore;
    private static int glDoneSemaphore;
    private static long pendingGlSync;
    private static long pendingGlFenceValue;
    private static int blockAtlasId = -1;

    private Dx12GlInterop() {
    }

    private static final class UnsupportedGpuSemaphoreException extends IllegalStateException {
        private UnsupportedGpuSemaphoreException(String message) {
            super(message);
        }
    }

    /** Chamado pelo LevelRendererMixin com as matrizes reais do frame. */
    public static void captureLevelMatrices(Camera camera, Matrix4f viewRotation, Matrix4f projection) {
        CAPTURED_VIEW.set(viewRotation);
        CAPTURED_PROJECTION.set(projection);
        Vec3 position = camera.getPosition();
        cameraX = position.x;
        cameraY = position.y;
        cameraZ = position.z;
        matricesCaptured = true;
    }

    /**
     * Replacement mode draws Direct before vanilla's base terrain layers so they can be skipped.
     * Preview mode draws after all three layers to avoid vanilla overwriting the Direct composite.
     */
    public static boolean shouldCompositeBeforeVanilla() {
        return ClientConfig.REPLACE_VANILLA_TERRAIN.get();
    }

    public static void renderTerrainOverlay() {
        lastFrameSucceeded = false;
        if (!pollCpuGlSync()) {
            DirectStatusOverlay.setStatus("D3D12 / waiting for OpenGL");
            return;
        }
        if (!reportedHook) {
            Direct.LOGGER.info("DX12/OpenGL interop terrain composite hook reached.");
            reportedHook = true;
        }
        boolean captured = matricesCaptured;
        matricesCaptured = false;
        if (!ClientConfig.ENABLE_DX12.get()) {
            TerrainMeshStore.clearCaptureFault();
            if (initialized || terrainCaptureEnabled) {
                disableTerrainCapture();
                shutdown();
            }
            disabled = false;
            retryAfterNanos = 0;
            DirectStatusOverlay.setStatus("Vanilla (disabled in config)");
            return;
        }
        if (TerrainMeshStore.hasCaptureFault()) {
            disableTerrainCapture();
            shutdown();
            disabled = true;
            DirectStatusOverlay.setStatus("Vanilla (terrain capture failed)");
            return;
        }
        if (disabled) {
            return;
        }
        if (!initialized && retryAfterNanos != 0 && System.nanoTime() - retryAfterNanos < 0) {
            return;
        }

        try {
            if (!wglInteropAvailable) {
                WGLCapabilities capabilities = GL.getCapabilitiesWGL();
                if (capabilities == null || !capabilities.WGL_NV_DX_interop2) {
                    if (!reportedUnsupported) {
                        Direct.LOGGER.error("WGL_NV_DX_interop2 is unavailable on the active OpenGL driver; "
                                + "vanilla rendering remains active.");
                        reportedUnsupported = true;
                    }
                    DirectRenderApi.updateCapabilities(DirectRenderCapabilities.unavailable());
                    DirectStatusOverlay.setStatus("Vanilla (WGL interop unavailable)");
                    disabled = true;
                    disableTerrainCapture();
                    return;
                }
                wglInteropAvailable = true;
            }

            Minecraft minecraft = Minecraft.getInstance();
            if (!terrainCaptureEnabled) {
                TerrainMeshStore.setCaptureEnabled(true);
                terrainCaptureEnabled = true;
                if (minecraft.level != null) {
                    minecraft.levelRenderer.allChanged();
                }
            }
            if (minecraft.level != renderedLevel) {
                if (renderedLevel != null) {
                    TerrainMeshStore.clear();
                }
                renderedLevel = minecraft.level;
            }
            if (minecraft.level == null || !captured) {
                return;
            }
            int currentWidth = minecraft.getWindow().getWidth();
            int currentHeight = minecraft.getWindow().getHeight();
            if (currentWidth <= 0 || currentHeight <= 0) {
                return;
            }
            if (!initialized) {
                initialize(currentWidth, currentHeight);
                blockAtlasDirty = true;
            } else if (currentWidth != width || currentHeight != height) {
                resize(currentWidth, currentHeight);
            }

            if (!NativeDx12.isCurrentFrameSlotReady()) {
                DirectStatusOverlay.setStatus("D3D12 / waiting for GPU");
                return;
            }
            TerrainMeshStore.uploadPending();
            uploadBlockAtlasIfNeeded(minecraft);

            CLIP_CORRECTION.mul(CAPTURED_PROJECTION, VIEW_PROJECTION).mul(CAPTURED_VIEW);
            VIEW_PROJECTION.get(MATRIX);
            if (frameIndex == Long.MAX_VALUE - 1) {
                throw new IllegalStateException("DX12/OpenGL interop fence timeline has exhausted its frame values.");
            }
            long dxReadyValue = frameIndex + 1;
            float elapsedSeconds = (System.nanoTime() - startedAtNanos) / 1_000_000_000.0f;
            float renderDistance = minecraft.options.getEffectiveRenderDistance() * 16.0f
                    * (ClientConfig.TERRAIN_RENDER_DISTANCE_PERCENT.get() / 100.0f);
            DirectFrameContext frameContext = DirectRenderApi.frame(frameIndex, currentWidth, currentHeight,
                    elapsedSeconds, renderDistance);
            DirectRenderEventBridge.postFrame(new DirectFrameRenderEvent(
                    DirectFrameRenderEvent.Stage.BEFORE_PASSES, frameContext));
            DirectRenderApi.PassSnapshot passSnapshot = DirectRenderApi.snapshotState();
            List<DirectRenderPass> passes = passSnapshot.passes();
            if (passSnapshot.revision() != configuredPassRevision) {
                String[] sources = NO_PASS_SOURCES;
                if (!passes.isEmpty()) {
                    sources = new String[passes.size()];
                    for (int index = 0; index < passes.size(); index++) {
                        sources[index] = passes.get(index).pixelShaderSource();
                    }
                }
                NativeDx12.configureRenderPasses(sources);
                configuredPassRevision = passSnapshot.revision();
            }
            int uniformCount = passes.size() * DirectRenderApi.MAX_UNIFORM_FLOATS;
            if (passUniformScratch.length != uniformCount) {
                passUniformScratch = new float[uniformCount];
            }
            for (int index = 0; index < passes.size(); index++) {
                DirectRenderPass pass = passes.get(index);
                DirectFrameContext passContext = DirectRenderApi.frame(frameIndex, currentWidth, currentHeight,
                        elapsedSeconds, renderDistance, pass.id(), index);
                DirectRenderApi.copyUniforms(pass, passContext, passUniformScratch,
                        index * DirectRenderApi.MAX_UNIFORM_FLOATS);
            }
            boolean useGpuSync = gpuSemaphoreInterop && ClientConfig.USE_GPU_SEMAPHORE_SYNC.get();
            int renderResult = NativeDx12.renderInterop(MATRIX, cameraX, cameraY, cameraZ,
                    renderDistance, frameIndex,
                    elapsedSeconds, passUniformScratch, useGpuSync, ClientConfig.USE_EXECUTE_INDIRECT.get());
            if (renderResult == NativeDx12.FRAME_SKIPPED_BUSY) {
                DirectStatusOverlay.setStatus("D3D12 / waiting for GPU");
                return;
            }
            if (renderResult != NativeDx12.FRAME_RENDERED_CPU_SYNC
                    && renderResult != NativeDx12.FRAME_RENDERED_GPU_SYNC) {
                throw new IllegalStateException("DX12 returned an invalid frame status: " + renderResult);
            }
            boolean nativeGpu = renderResult == NativeDx12.FRAME_RENDERED_GPU_SYNC;
            if (useGpuSync && !nativeGpu) {
                disableGpuSemaphore("The native command queue could not enqueue a GPU-side fence wait; "
                        + "using CPU fence waits.");
            }
            boolean frameUsesGpuSemaphore = useGpuSync && nativeGpu;
            if (frameUsesGpuSemaphore && !waitForDx12Frame(dxReadyValue)) {
                NativeDx12.waitInteropFence(dxReadyValue);
                disableGpuSemaphore("OpenGL rejected the D3D12 timeline semaphore wait; using CPU fence waits.");
                frameUsesGpuSemaphore = false;
            }
            if (NativeDx12.hasInteropGpuSyncFault()) {
                throw new IllegalStateException("The D3D12 fence missed its deadline or the device was removed.");
            }
            drawImportedTexture(frameUsesGpuSemaphore, frameIndex);
            DirectRenderEventBridge.postFrame(new DirectFrameRenderEvent(
                    DirectFrameRenderEvent.Stage.AFTER_PASSES, frameContext));
            lastFrameSucceeded = true;
            frameIndex++;
            retryAfterNanos = 0;
            DirectStatusOverlay.setStatus(frameUsesGpuSemaphore ? "D3D12 / GPU sync" : "D3D12 / CPU fallback");
        } catch (IOException | RuntimeException | UnsatisfiedLinkError exception) {
            boolean unsupportedGpuSemaphore = exception instanceof UnsupportedGpuSemaphoreException;
            Direct.LOGGER.error("DX12/OpenGL interop failed; retaining vanilla rendering.", exception);
            shutdown();
            disableTerrainCapture();
            DirectRenderApi.updateCapabilities(DirectRenderCapabilities.unavailable());
            disabled = unsupportedGpuSemaphore;
            retryAfterNanos = unsupportedGpuSemaphore ? 0 : System.nanoTime() + RETRY_DELAY_NANOS;
            DirectStatusOverlay.setStatus(unsupportedGpuSemaphore
                    ? "Vanilla (D3D12 fence import unsupported)"
                    : "Vanilla (retrying after renderer error)");
        }
    }

    public static void shutdown() {
        configuredPassRevision = -1L;
        lastFrameSucceeded = false;
        if (interopSemaphore != 0 || glDoneSemaphore != 0 || interopObject != 0) {
            GL11.glFinish();
        }
        finishPendingGlSync();
        if (interopSemaphore != 0) {
            EXTSemaphore.glDeleteSemaphoresEXT(interopSemaphore);
            interopSemaphore = 0;
        }
        if (glDoneSemaphore != 0) {
            EXTSemaphore.glDeleteSemaphoresEXT(glDoneSemaphore);
            glDoneSemaphore = 0;
        }
        gpuSemaphoreInterop = false;
        wglInteropAvailable = false;
        unregisterTexture();
        if (interopDevice != 0) {
            WGLNVDXInterop.wglDXCloseDeviceNV(interopDevice);
            interopDevice = 0;
        }
        if (vertexArray != 0) {
            GL30.glDeleteVertexArrays(vertexArray);
            vertexArray = 0;
        }
        if (program != 0) {
            GL20.glDeleteProgram(program);
            program = 0;
            sourceTextureUniform = -1;
        }
        if (initialized) {
            NativeDx12.shutdownInterop();
            initialized = false;
        }
        DirectRenderApi.updateCapabilities(DirectRenderCapabilities.unavailable());
        width = 0;
        height = 0;
        frameIndex = 0;
        startedAtNanos = 0;
        blockAtlasId = -1;
    }

    public static void blockAtlasUpdated() {
        blockAtlasDirty = true;
    }

    /**
     * Allows the LevelRenderer mixin to skip only the three base chunk layers Direct can reproduce.
     * Vanilla remains active until the capture queue has drained and a successful DX12 frame exists.
     */
    public static boolean shouldSkipVanillaLayer(net.minecraft.client.renderer.RenderType layer) {
        if (!ClientConfig.REPLACE_VANILLA_TERRAIN.get() || !initialized || disabled
                || !lastFrameSucceeded || !TerrainMeshStore.isCaptureReady()) {
            return false;
        }
        return layer == net.minecraft.client.renderer.RenderType.solid()
                || layer == net.minecraft.client.renderer.RenderType.cutoutMipped()
                || layer == net.minecraft.client.renderer.RenderType.cutout();
    }

    private static void disableTerrainCapture() {
        TerrainMeshStore.setCaptureEnabled(false);
        if (terrainCaptureEnabled) {
            TerrainMeshStore.clear();
            terrainCaptureEnabled = false;
        }
    }

    private static void initialize(int frameWidth, int frameHeight) throws IOException {
        NativeLibrary.load();
        long[] handles = NativeDx12.initializeInterop(frameWidth, frameHeight,
                ClientConfig.PARALLEL_SECTION_RECORDING.get(), ClientConfig.UPLOAD_RING_BUFFER_MIB.get(),
                ClientConfig.TERRAIN_ARENA_PAGE_MIB.get(), ClientConfig.MAX_TERRAIN_ARENA_MIB.get());
        if (handles == null || handles.length != 4 || handles[0] == 0 || handles[1] == 0) {
            throw new IllegalStateException("DX12 did not expose its D3D11 device and shared texture.");
        }
        initialized = true;
        interopDevice = WGLNVDXInterop.wglDXOpenDeviceNV(handles[0]);
        if (interopDevice == 0) {
            throw new IllegalStateException("WGL_NV_DX_interop2 could not open the D3D11On12 device.");
        }
        registerTexture(handles[1]);
        if (ClientConfig.USE_GPU_SEMAPHORE_SYNC.get()) {
            initializeGpuSemaphore(handles[2], handles[3]);
        }
        String adapterName = NativeDx12.interopAdapterName();
        int featureFlags = NativeDx12.interopFeatureFlags();
        DirectRenderApi.updateCapabilities(new DirectRenderCapabilities("Direct3D 12",
                adapterName == null || adapterName.isBlank() ? "unknown" : adapterName,
                (featureFlags & 1) != 0, true, true, (featureFlags & 2) != 0, (featureFlags & 4) != 0,
                (featureFlags & 8) != 0, gpuSemaphoreInterop, true, false));
        DirectStatusOverlay.setStatus("D3D12 / initializing");
        program = createProgram();
        sourceTextureUniform = GL20.glGetUniformLocation(program, "sourceTexture");
        vertexArray = GL30.glGenVertexArrays();
        width = frameWidth;
        height = frameHeight;
        frameIndex = 0;
        startedAtNanos = System.nanoTime();
    }

    private static void resize(int frameWidth, int frameHeight) {
        GL11.glFinish();
        finishPendingGlSync();
        unregisterTexture();
        long sharedTexture = NativeDx12.resizeInterop(frameWidth, frameHeight);
        if (sharedTexture == 0) {
            throw new IllegalStateException("DX12/OpenGL interop resize did not return a shared texture.");
        }
        registerTexture(sharedTexture);
        width = frameWidth;
        height = frameHeight;
    }

    private static void registerTexture(long sharedTexture) {
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            interopObject = WGLNVDXInterop.wglDXRegisterObjectNV(interopDevice, sharedTexture, texture,
                    GL11.GL_TEXTURE_2D, WGLNVDXInterop.WGL_ACCESS_READ_ONLY_NV);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL13.glActiveTexture(previousActiveTexture);
        }
        if (interopObject == 0) {
            throw new IllegalStateException("WGL_NV_DX_interop2 could not register the shared D3D11 texture.");
        }
    }

    private static void unregisterTexture() {
        if (interopDevice != 0 && interopObject != 0) {
            WGLNVDXInterop.wglDXUnregisterObjectNV(interopDevice, interopObject);
        }
        interopObject = 0;
        if (texture != 0) {
            GL11.glDeleteTextures(texture);
            texture = 0;
        }
    }

    private static void uploadBlockAtlasIfNeeded(Minecraft minecraft) {
        int atlasTexture = minecraft.getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS).getId();
        if (atlasTexture != blockAtlasId) {
            blockAtlasId = atlasTexture;
            blockAtlasDirty = true;
        }
        if (!blockAtlasDirty) {
            return;
        }
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousPackBuffer = GL11.glGetInteger(GL30.GL_PIXEL_PACK_BUFFER_BINDING);
        int previousPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        try {
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlasTexture);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            int baseWidth = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int baseHeight = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (baseWidth <= 0 || baseHeight <= 0) {
                return;
            }
            int maxLevel = Math.min(GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL),
                    MAX_ATLAS_LEVELS - 1);
            List<ByteBuffer> levels = new ArrayList<>();
            for (int level = 0; level <= maxLevel; level++) {
                int levelWidth = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_WIDTH);
                int levelHeight = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_HEIGHT);
                if (levelWidth != Math.max(1, baseWidth >> level) || levelHeight != Math.max(1, baseHeight >> level)) {
                    break;
                }
                long byteCount = (long) levelWidth * levelHeight * 4;
                if (byteCount > Integer.MAX_VALUE) {
                    throw new IllegalStateException("Minecraft block atlas exceeds the supported DX12 upload size.");
                }
                ByteBuffer pixels = ByteBuffer.allocateDirect((int) byteCount);
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, level, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                levels.add(pixels);
            }
            NativeDx12.updateTerrainAtlas(levels.toArray(ByteBuffer[]::new), baseWidth, baseHeight,
                    ClientConfig.COMPRESS_BLOCK_ATLAS.get(), ClientConfig.BC7_ENCODER_THREADS.get());
            blockAtlasDirty = false;
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, previousPackAlignment);
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, previousPackBuffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL13.glActiveTexture(previousActiveTexture);
        }
    }

    private static void drawImportedTexture(boolean useGpuSemaphore, long frame) {
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, PREVIOUS_VIEWPORT);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean stencilEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int blendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int blendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int blendEquationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        int blendEquationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer colorMask = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask);
            for (int component = 0; component < 4; component++) {
                PREVIOUS_COLOR_MASK[component] = colorMask.get(component) != 0;
            }
            var objects = stack.mallocPointer(1);
            objects.put(0, interopObject);
            if (!WGLNVDXInterop.wglDXLockObjectsNV(interopDevice, objects)) {
                throw new IllegalStateException("WGL_NV_DX_interop2 could not lock the shared texture.");
            }
            boolean drawn = false;
            try {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glEnable(GL11.GL_BLEND);
                GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
                GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                        GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glDisable(GL11.GL_STENCIL_TEST);
                GL11.glDepthMask(false);
                GL11.glColorMask(true, true, true, true);
                GL11.glViewport(0, 0, width, height);
                GL20.glUseProgram(program);
                GL30.glBindVertexArray(vertexArray);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                GL20.glUniform1i(sourceTextureUniform, 0);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                drawn = true;
            } finally {
                objects.put(0, interopObject);
                boolean unlocked = WGLNVDXInterop.wglDXUnlockObjectsNV(interopDevice, objects);
                if (!unlocked) {
                    if (drawn) {
                        throw new IllegalStateException("WGL_NV_DX_interop2 could not unlock the shared texture.");
                    }
                    Direct.LOGGER.error("WGL_NV_DX_interop2 unlock also failed after a draw error.");
                }
            }
        } finally {
            GL20.glUseProgram(previousProgram);
            GL30.glBindVertexArray(previousVertexArray);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL13.glActiveTexture(previousActiveTexture);
            GL11.glViewport(PREVIOUS_VIEWPORT[0], PREVIOUS_VIEWPORT[1], PREVIOUS_VIEWPORT[2], PREVIOUS_VIEWPORT[3]);
            GL11.glDepthMask(depthMask);
            GL11.glColorMask(PREVIOUS_COLOR_MASK[0], PREVIOUS_COLOR_MASK[1], PREVIOUS_COLOR_MASK[2],
                    PREVIOUS_COLOR_MASK[3]);
            GL20.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha);
            GL14.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
            setEnabled(GL11.GL_DEPTH_TEST, depthEnabled);
            setEnabled(GL11.GL_BLEND, blendEnabled);
            setEnabled(GL11.GL_CULL_FACE, cullEnabled);
            setEnabled(GL11.GL_SCISSOR_TEST, scissorEnabled);
            setEnabled(GL11.GL_STENCIL_TEST, stencilEnabled);
        }

        if (useGpuSemaphore) {
            if (!signalGlDoneFence(frame + 1)) {
                disableGpuSemaphore("OpenGL rejected the D3D12 timeline semaphore signal; using a polled GL sync.");
                queueCpuGlSync(frame + 1);
            }
        } else {
            queueCpuGlSync(frame + 1);
        }
    }

    /** Queue a non-blocking GL completion fence for the CPU-synchronized interop fallback. */
    private static void queueCpuGlSync(long value) {
        if (pendingGlSync != 0) {
            finishPendingGlSync();
        }
        long sync = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (sync == 0) {
            // The sync object could not be created; preserve correctness with the old blocking fallback.
            GL11.glFinish();
            NativeDx12.signalInteropFence(value);
            return;
        }
        pendingGlSync = sync;
        pendingGlFenceValue = value;
        GL11.glFlush();
    }

    /** Polls without stalling the render thread. A false result means vanilla should render this frame. */
    private static boolean pollCpuGlSync() {
        if (pendingGlSync == 0) {
            return true;
        }
        int result = GL32.glClientWaitSync(pendingGlSync, 0, 0L);
        if (result == GL32.GL_ALREADY_SIGNALED || result == GL32.GL_CONDITION_SATISFIED) {
            long value = pendingGlFenceValue;
            GL32.glDeleteSync(pendingGlSync);
            pendingGlSync = 0;
            pendingGlFenceValue = 0;
            NativeDx12.signalInteropFence(value);
            return true;
        }
        if (result == GL32.GL_WAIT_FAILED) {
            finishPendingGlSync();
            return true;
        }
        return false;
    }

    /** Used only during resize/shutdown, when waiting is required before releasing shared resources. */
    private static void finishPendingGlSync() {
        if (pendingGlSync == 0) {
            return;
        }
        GL11.glFinish();
        long value = pendingGlFenceValue;
        GL32.glDeleteSync(pendingGlSync);
        pendingGlSync = 0;
        pendingGlFenceValue = 0;
        NativeDx12.signalInteropFence(value);
    }

    private static void initializeGpuSemaphore(long sharedReadyFenceHandle, long sharedGlDoneFenceHandle) {
        var capabilities = GL.getCapabilities();
        if (sharedReadyFenceHandle == 0 || sharedGlDoneFenceHandle == 0
                || !capabilities.GL_EXT_semaphore || !capabilities.GL_EXT_semaphore_win32) {
            disableGpuSemaphore("The driver does not expose a shared D3D12 fence semaphore; using CPU fence waits.");
            return;
        }
        drainGlErrors();
        int readySemaphore = EXTSemaphore.glGenSemaphoresEXT();
        int doneSemaphore = EXTSemaphore.glGenSemaphoresEXT();
        EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(readySemaphore,
                EXTSemaphoreWin32.GL_HANDLE_TYPE_D3D12_FENCE_EXT, sharedReadyFenceHandle);
        EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(doneSemaphore,
                EXTSemaphoreWin32.GL_HANDLE_TYPE_D3D12_FENCE_EXT, sharedGlDoneFenceHandle);
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            EXTSemaphore.glDeleteSemaphoresEXT(readySemaphore);
            EXTSemaphore.glDeleteSemaphoresEXT(doneSemaphore);
            String reason = "The OpenGL driver rejected the shared D3D12 fence import (GL error 0x"
                    + Integer.toHexString(error) + "). The native renderer is unsafe on this driver; "
                    + "Direct will keep vanilla rendering active for this session.";
            disableGpuSemaphore(reason);
            throw new UnsupportedGpuSemaphoreException(reason);
        }
        interopSemaphore = readySemaphore;
        glDoneSemaphore = doneSemaphore;
        gpuSemaphoreInterop = true;
        Direct.LOGGER.info("Using a shared D3D12 timeline fence for GPU synchronization between D3D12 and OpenGL.");
    }

    private static boolean waitForDx12Frame(long value) {
        drainGlErrors();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            setSemaphoreFenceValue(stack, interopSemaphore, value);
            EXTSemaphore.glWaitSemaphoreEXT(interopSemaphore, NO_OBJECTS, NO_OBJECTS, NO_OBJECTS);
        }
        return GL11.glGetError() == GL11.GL_NO_ERROR;
    }

    private static boolean signalGlDoneFence(long value) {
        drainGlErrors();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            setSemaphoreFenceValue(stack, glDoneSemaphore, value);
            EXTSemaphore.glSignalSemaphoreEXT(glDoneSemaphore, NO_OBJECTS, NO_OBJECTS, NO_OBJECTS);
            GL11.glFlush();
        }
        return GL11.glGetError() == GL11.GL_NO_ERROR;
    }

    private static void setSemaphoreFenceValue(MemoryStack stack, int semaphore, long value) {
        var fenceValue = stack.mallocLong(1);
        fenceValue.put(0, value);
        EXTSemaphore.glSemaphoreParameterui64vEXT(semaphore, EXTSemaphoreWin32.GL_D3D12_FENCE_VALUE_EXT, fenceValue);
    }

    private static void drainGlErrors() {
        for (int attempt = 0; attempt < 64 && GL11.glGetError() != GL11.GL_NO_ERROR; attempt++) {
            // descarta erros antigos do Minecraft para não culpar o semáforo
        }
    }

    private static void disableGpuSemaphore(String reason) {
        gpuSemaphoreInterop = false;
        if (!reportedSemaphoreFallback) {
            Direct.LOGGER.warn("{}", reason);
            reportedSemaphoreFallback = true;
        }
    }

    private static int createProgram() {
        int vertexShader = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader;
        try {
            fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        } catch (IllegalStateException exception) {
            GL20.glDeleteShader(vertexShader);
            throw exception;
        }
        int shaderProgram = GL20.glCreateProgram();
        GL20.glAttachShader(shaderProgram, vertexShader);
        GL20.glAttachShader(shaderProgram, fragmentShader);
        GL20.glLinkProgram(shaderProgram);
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);
        if (GL20.glGetProgrami(shaderProgram, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(shaderProgram);
            GL20.glDeleteProgram(shaderProgram);
            throw new IllegalStateException("OpenGL overlay shader link failed: " + log);
        }
        return shaderProgram;
    }

    private static int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("OpenGL overlay shader compilation failed: " + log);
        }
        return shader;
    }

    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) {
            GL11.glEnable(capability);
        } else {
            GL11.glDisable(capability);
        }
    }
}