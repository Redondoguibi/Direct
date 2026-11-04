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
import org.lwjgl.opengl.GL33;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.core.SectionPos;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
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
    private static final AtlasRegionUpdates ATLAS_REGIONS = new AtlasRegionUpdates();
    private static final InteropSceneCapture SCENE_CAPTURE=new InteropSceneCapture();
    private static final long RETRY_DELAY_NANOS = 5_000_000_000L;
    static final String VERTEX_SHADER = """
            #version 150
            out vec2 texCoord;
            void main() {
                vec2 position = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                // This oversized triangle reaches NDC 3, so its UVs must reach 2.
                // Interpolation over the visible viewport then covers the full [0, 1] texture.
                texCoord = position;
                gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    static final String FRAGMENT_SHADER = """
            #version 150
            uniform sampler2D sourceTexture;
            uniform sampler2D sourceDepth;
            uniform bool writeTerrainDepth;
            in vec2 texCoord;
            out vec4 color;
            void main() {
                // D3D textures use a top-left row origin; flip V when sampling in the GL compositor.
                vec2 uv = vec2(texCoord.x, 1.0 - texCoord.y);
                color = texture(sourceTexture, uv);
                gl_FragDepth = gl_FragCoord.z;
                if (writeTerrainDepth) {
                    float depth = texture(sourceDepth, uv).r;
                    if (depth >= 1.0 && color.a <= 0.0) discard;
                    gl_FragDepth = depth;
                }
            }
            """;

    // GL e D3D projetam NDC +Y para o topo da viewport; apenas o intervalo Z precisa de conversão.
    // O winding CW do D3D é configurado no PSO para o sistema de coordenadas da viewport.
    private static final Matrix4f CLIP_CORRECTION = new Matrix4f().m22(0.5f).m32(0.5f);
    private static final Matrix4f CAPTURED_VIEW = new Matrix4f();
    private static final Matrix4f CAPTURED_PROJECTION = new Matrix4f();
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    private static final TerrainFrameView PENDING_VIEW = new TerrainFrameView();
    private static final float[] MATRIX = new float[28];
    private static final ByteBuffer LIGHT_PIXELS = ByteBuffer.allocateDirect(1024);
    private static boolean lightmapDirty = true;
    private static boolean lightmapUploaded;
    private static long[] visibleKeys = new long[256];
    private static int visibleCount;
    private static int visibleTranslucentCount;
    private static List<SectionRenderDispatcher.RenderSection> currentVisibleSections=List.of();
    private static int fallbackSections;
    private static int fallbackBaseSections;
    private static boolean translucentCovered;
    private static final java.util.Set<com.mojang.blaze3d.vertex.VertexBuffer> ownedBuffers=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static boolean reportedTranslucentBudget;
    private static boolean replacementFrame;
    private static int depthTexture;
    private static long depthInteropObject;
    private static int sourceDepthUniform;
    private static int writeDepthUniform;
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
    private static List<DirectRenderPass> configuredPasses = List.of();
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
    private static int glDoneSemaphore;
    private static long pendingGlSync;
    private static long pendingGlFenceValue;
    private static long pendingReadyValue;
    private static long pendingFrameIndex;
    private static boolean pendingFrameUsesGpuSemaphore;
    private static int blockAtlasId = -1;

    private Dx12GlInterop() {
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
    /** Replaces just the translucent layer after entities, importing their current color/depth first. */
    public static boolean renderTranslucentLayer(){
        if(!ClientConfig.ENABLE_DX12.get()||!shouldCompositeBeforeVanilla()||!initialized||!lastFrameSucceeded||!replacementFrame)return false;
        var mc=Minecraft.getInstance();var target=mc.getMainRenderTarget();
        var effects=DirectRenderApi.terrainEffects();
        if(!ClientConfig.DX12_TRANSLUCENCY.get()&&effects.water()==0&&effects.reflections()==0)return false;
        if(Minecraft.useShaderTransparency()||GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)!=target.frameBufferId)return false;
        if(!PENDING_VIEW.matches(CAPTURED_VIEW,CAPTURED_PROJECTION,cameraX,cameraY,cameraZ)||!translucentCovered)return false;
        if(visibleTranslucentCount==0)return true;
        try(MemoryStack stack=MemoryStack.stackPush()){
            if(frameIndex>=Long.MAX_VALUE-1)throw new IllegalStateException("Interop frame timeline exhausted");
            int readiness=NativeDx12.translucentFrameStatus();
            if(readiness==NativeDx12.FRAME_SKIPPED_BUSY)return false;
            if(readiness==NativeDx12.FRAME_SKIPPED_BUDGET){
                if(!reportedTranslucentBudget){Direct.LOGGER.warn("DX12 translucent index budget exceeded; keeping vanilla transparency and DX12 opaque terrain. Increase uploadRingBufferMiB if needed.");reportedTranslucentBudget=true;}
                DirectStatusOverlay.setStatus("DX12 terrain / vanilla translucency (index budget)");return false;
            }
            if(readiness!=NativeDx12.FRAME_RENDERED_CPU_SYNC)throw new IllegalStateException("Translucent preflight failed");
            long captureStart=System.nanoTime();
            finishPendingGlSync();
            var objects=stack.pointers(interopObject,depthInteropObject);
            if(!WGLNVDXInterop.wglDXLockObjectsNV(interopDevice,objects))throw new IllegalStateException("Cannot lock scene capture");
            try{SCENE_CAPTURE.capture(target.frameBufferId,target.getDepthTextureId(),texture,depthTexture,width,height);}
            finally{if(!WGLNVDXInterop.wglDXUnlockObjectsNV(interopDevice,objects))throw new IllegalStateException("Cannot unlock scene capture");}
            // Imported scene writes must complete before D3D12 can sample them. The shared
            // consumption fence cannot be reused here: it already signaled for the base layer.
            GL11.glFinish();
            if(RendererMetrics.recording)RendererMetrics.translucentCaptureNanos+=System.nanoTime()-captureStart;
            long submitStart=System.nanoTime();
            int result=NativeDx12.renderTranslucentInterop(MATRIX,cameraX,cameraY,cameraZ,frameIndex);
            if(RendererMetrics.recording)RendererMetrics.translucentSubmitNanos+=System.nanoTime()-submitStart;
            if(result==NativeDx12.FRAME_SKIPPED_BUSY||result==NativeDx12.FRAME_SKIPPED_BUDGET)return false;
            if(result!=NativeDx12.FRAME_RENDERED_CPU_SYNC)throw new IllegalStateException("Translucent submission failed");
            long value=++frameIndex;
            long waitStart=System.nanoTime();
            if(!NativeDx12.waitInteropFrame(value))throw new IllegalStateException("Translucent frame incomplete");
            if(RendererMetrics.recording)RendererMetrics.translucentWaitNanos+=System.nanoTime()-waitStart;
            if(RendererMetrics.recording){var metrics=NativeDx12.frameMetrics();if(metrics!=null&&metrics[0]>=0){RendererMetrics.translucentGpuNanos+=(long)(metrics[0]*1e6);RendererMetrics.translucentDraws+=(long)metrics[2];}}
            long presentStart=System.nanoTime();
            if(!WGLNVDXInterop.wglDXLockObjectsNV(interopDevice,objects))throw new IllegalStateException("Cannot lock transparent output");
            try{SCENE_CAPTURE.present(target.frameBufferId,texture,depthTexture,width,height);}
            finally{if(!WGLNVDXInterop.wglDXUnlockObjectsNV(interopDevice,objects))throw new IllegalStateException("Cannot unlock transparent output");}
            queueCpuGlSync(value);
            if(RendererMetrics.recording){RendererMetrics.translucentPresentNanos+=System.nanoTime()-presentStart;RendererMetrics.translucentFrames++;}
            return true;
        }catch(RuntimeException|UnsatisfiedLinkError ex){
            Direct.LOGGER.error("DX12 translucent layer failed; preserving vanilla translucent rendering",ex);
            lastFrameSucceeded=false;shutdown();disableTerrainCapture();retryAfterNanos=System.nanoTime()+RETRY_DELAY_NANOS;return false;
        }
    }

    public static void captureVisibleSections(List<SectionRenderDispatcher.RenderSection> sections) {
        currentVisibleSections=sections;
        visibleCount=0;visibleTranslucentCount=0;fallbackSections=fallbackBaseSections=0;translucentCovered=true;ownedBuffers.clear();
        if(!ClientConfig.ENABLE_DX12.get())return;
        if(visibleKeys.length<sections.size())visibleKeys=new long[sections.size()];
        for(var section:sections){
            var compiled=section.getCompiled();
            boolean translucent=!compiled.isEmpty(RenderType.translucent());
            boolean base=!compiled.isEmpty(RenderType.solid())||!compiled.isEmpty(RenderType.cutoutMipped())||!compiled.isEmpty(RenderType.cutout());
            if(!base&&!translucent)continue;
            long key=SectionPos.of(section.getOrigin()).asLong();
            boolean owned=TerrainMeshStore.owns(key);
            if(translucent){visibleTranslucentCount++;if(!owned)translucentCovered=false;}
            if(!owned){fallbackSections++;if(base)fallbackBaseSections++;continue;}
            visibleKeys[visibleCount++]=key;
            for(var layer:java.util.List.of(RenderType.solid(),RenderType.cutoutMipped(),RenderType.cutout()))
                if(!compiled.isEmpty(layer))ownedBuffers.add(section.getBuffer(layer));
        }
    }

    /** Frozen ownership for this frame; never suppress a buffer before successful composition. */
    public static boolean shouldSkipVanillaBuffer(com.mojang.blaze3d.vertex.VertexBuffer buffer){
        return lastFrameSucceeded&&replacementFrame&&ownedBuffers.contains(buffer);
    }

    /** Current-frame composition: never cancels vanilla until BOTH color and depth were imported. */
    public static void compositeTerrainFrame() {
        if (!shouldCompositeBeforeVanilla() || pendingReadyValue == 0) return;
        long started = System.nanoTime();
        try {
            // Compilation may have changed the visible meshes after submission. An image
            // that cannot be displayed must not stall vanilla waiting for its completion.
            if (TerrainMeshStore.hasCaptureFault()
                    || !PENDING_VIEW.matches(CAPTURED_VIEW,CAPTURED_PROJECTION,cameraX,cameraY,cameraZ)) {
                discardPendingFrame();replacementFrame=false;
                if(RendererMetrics.recording)RendererMetrics.discardedBeforeWait++;
                return;
            }
            long waitStart=System.nanoTime();
            boolean completed=NativeDx12.waitInteropFrame(pendingReadyValue);
            if(RendererMetrics.recording)RendererMetrics.dxWaitNanos+=System.nanoTime()-waitStart;
            if(!completed)
                throw new IllegalStateException("D3D12 did not complete the current terrain frame.");
            if (ClientConfig.PROFILE_RENDERER.get()) {
                double[] metrics = NativeDx12.frameMetrics();
                if (metrics != null && metrics[0] >= 0) {
                    RendererMetrics.gpuNanos += (long) (metrics[0] * 1e6);
                    RendererMetrics.nativeDraws += (long) metrics[2];
                    RendererMetrics.nativeFrames++;
                    if(metrics.length>=5){RendererMetrics.gpuArenaBytes=(long)metrics[3];RendererMetrics.uploadRingBytes=(long)metrics[4];}
                }
            }
            if (TerrainMeshStore.hasCaptureFault()) {
                discardPendingFrame();
                replacementFrame = false;
                return;
            }
            resolvePendingFrame(true);
        } catch (RuntimeException exception) {
            Direct.LOGGER.error("Current DX12 frame failed; using vanilla terrain.", exception);
            lastFrameSucceeded = false;
            shutdown();
            disableTerrainCapture();
            retryAfterNanos = System.nanoTime() + RETRY_DELAY_NANOS;
        } finally {
            RendererMetrics.compositeNanos += System.nanoTime() - started;
        }
    }

    public static void renderTerrainOverlay() {
        lastFrameSucceeded = false;
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
            if (!pollCpuGlSync()) {
                if (!shouldCompositeBeforeVanilla()) return;
                long waitStart=System.nanoTime();
                int result = GL32.glClientWaitSync(pendingGlSync, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 5_000_000_000L);
                if(RendererMetrics.recording)RendererMetrics.glWaitNanos+=System.nanoTime()-waitStart;
                if (result == GL32.GL_TIMEOUT_EXPIRED || result == GL32.GL_WAIT_FAILED)
                    throw new IllegalStateException("OpenGL did not release the shared terrain image.");
                if (!pollCpuGlSync()) return;
            }
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
                    discardPendingFrame();
                    TerrainMeshStore.clear();
                }
                renderedLevel = minecraft.level;
            }
            if (minecraft.level == null) {
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
                discardPendingFrame();
                if (!lastSubmittedFrameReady("resize")) {
                    return;
                }
                resize(currentWidth, currentHeight);
            }
            if (!shouldCompositeBeforeVanilla()) resolvePendingFrame(captured);
            else if (pendingReadyValue != 0) discardPendingFrame(); // mode switch: never strand the GL-consumption fence
            // Do not overwrite shared targets while a prior GL composite still samples them.
            if (!pollCpuGlSync()) {
                DirectStatusOverlay.setStatus("D3D12 / waiting for OpenGL");
                return;
            }
            if (!captured) {
                return;
            }

            if (!NativeDx12.isCurrentFrameSlotReady()) {
                DirectStatusOverlay.setStatus("D3D12 / waiting for GPU");
                return;
            }
            if (!lastSubmittedFrameReady("terrain resource updates")) return;
            TerrainMeshStore.uploadPending();
            captureVisibleSections(currentVisibleSections);
            if(RendererMetrics.recording){RendererMetrics.ownershipFrames++;RendererMetrics.ownedSectionSamples+=visibleCount;RendererMetrics.fallbackSectionSamples+=fallbackSections;if(visibleCount>0&&fallbackBaseSections>0)RendererMetrics.mixedFrames++;}
            replacementFrame = shouldCompositeBeforeVanilla() && ClientConfig.TERRAIN_RENDER_DISTANCE_PERCENT.get() == 100;
            if (shouldCompositeBeforeVanilla() && (!replacementFrame||visibleCount==0)) {
                DirectStatusOverlay.setStatus("Vanilla (initial terrain uploads)");
                return;
            }
            uploadBlockAtlasIfNeeded(minecraft);
            uploadLightmap();
            NativeDx12.setVisibleSections(visibleKeys, visibleCount, replacementFrame);
            if(DirectRenderApi.terrainEffects().shadows()>0){var keys=TerrainMeshStore.shadowKeys();NativeDx12.setShadowSections(keys);}

            CLIP_CORRECTION.mul(CAPTURED_PROJECTION, VIEW_PROJECTION).mul(CAPTURED_VIEW);
            VIEW_PROJECTION.get(MATRIX);
            float[] fog = RenderSystem.getShaderFogColor();
            System.arraycopy(fog, 0, MATRIX, 16, 4);
            MATRIX[20] = RenderSystem.getShaderFogStart();
            MATRIX[21] = RenderSystem.getShaderFogEnd();
            MATRIX[22] = RenderSystem.getShaderFogShape().getIndex();
            MATRIX[23] = 0;
            System.arraycopy(RenderSystem.getShaderColor(), 0, MATRIX, 24, 4);
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
                if (!lastSubmittedFrameReady("pass reconfiguration")) {
                    return;
                }
                String[] sources = NO_PASS_SOURCES;
                if (!passes.isEmpty()) {
                    sources = new String[passes.size()];
                    for (int index = 0; index < passes.size(); index++) {
                        sources[index] = passes.get(index).pixelShaderSource();
                    }
                }
                try {
                    NativeDx12.configureRenderPasses(sources);
                    configuredPasses = passes;
                    DirectRenderApi.reportConfiguration(passSnapshot, "");
                } catch (IllegalArgumentException exception) {
                    // A shader error leaves native pipelines intact. Retry only after a registration change.
                    Direct.LOGGER.error("Rejected extension shader configuration; retaining working passes.", exception);
                    DirectRenderApi.reportConfiguration(passSnapshot, exception.getMessage());
                }
                configuredPassRevision = passSnapshot.revision();
            }
            passes = configuredPasses;
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
            NativeDx12.setProfilingEnabled(ClientConfig.PROFILE_RENDERER.get());
            var effects=DirectRenderApi.terrainEffects();
            float angle=minecraft.level.getSunAngle(0);
            NativeDx12.setTerrainEffects(new float[]{minecraft.level.dimensionType().hasSkyLight()?effects.shadows():0,effects.water(),effects.reflections(),effects.wind(),effects.quality(),-(float)Math.sin(angle),(float)Math.cos(angle),0,(minecraft.level.getGameTime()%240000)/20f,minecraft.level.getRainLevel(0)});
            boolean useGpuSync = gpuSemaphoreInterop && ClientConfig.USE_GPU_SEMAPHORE_SYNC.get();
            long submittedAt = System.nanoTime();
            int renderResult = NativeDx12.renderInterop(MATRIX, cameraX, cameraY, cameraZ,
                    renderDistance, frameIndex,
                    elapsedSeconds, passUniformScratch, useGpuSync, ClientConfig.USE_EXECUTE_INDIRECT.get());
            RendererMetrics.submitNanos += System.nanoTime() - submittedAt;
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
            DirectRenderEventBridge.postFrame(new DirectFrameRenderEvent(
                    DirectFrameRenderEvent.Stage.AFTER_PASSES, frameContext));
            pendingReadyValue = dxReadyValue;
            pendingFrameIndex = frameIndex;
            pendingFrameUsesGpuSemaphore = frameUsesGpuSemaphore;
            PENDING_VIEW.set(CAPTURED_VIEW, CAPTURED_PROJECTION, cameraX, cameraY, cameraZ);
            frameIndex++;
            retryAfterNanos = 0;
            DirectStatusOverlay.setStatus((replacementFrame ? "DX12 terrain (experimental)" : "DX12 overlay (debug)")
                    + (frameUsesGpuSemaphore ? " / GPU sync" : " / CPU sync")
                    + (replacementFrame&&fallbackSections>0?" / "+fallbackSections+" vanilla sections":""));
        } catch (IOException | RuntimeException | UnsatisfiedLinkError exception) {
            Direct.LOGGER.error("DX12/OpenGL interop failed; retaining vanilla rendering.", exception);
            shutdown();
            disableTerrainCapture();
            DirectRenderApi.updateCapabilities(DirectRenderCapabilities.unavailable());
            disabled = false;
            retryAfterNanos = System.nanoTime() + RETRY_DELAY_NANOS;
            DirectStatusOverlay.setStatus("Vanilla (retrying after renderer error)");
        }
    }

    /** Release world-owned meshes/targets even when no renderLevel hook runs in the menu. */
    public static void idle() {
        if(Minecraft.getInstance().level!=null||(!initialized&&!terrainCaptureEnabled))return;
        if(!RenderSystem.isOnRenderThread()){RenderSystem.recordRenderCall(Dx12GlInterop::idle);return;}
        disableTerrainCapture();
        shutdown();
        renderedLevel=null;matricesCaptured=false;replacementFrame=false;
    }

    public static void shutdown() {
        configuredPassRevision = -1L;
        configuredPasses = List.of();
        lastFrameSucceeded = false;
        ownedBuffers.clear();
        currentVisibleSections=List.of();
        if (pendingGlSync != 0) {
            finishPendingGlSync();
        } else if (glDoneSemaphore != 0 || interopObject != 0) {
            GL11.glFinish();
        }
        if (glDoneSemaphore != 0) {
            EXTSemaphore.glDeleteSemaphoresEXT(glDoneSemaphore);
            glDoneSemaphore = 0;
        }
        gpuSemaphoreInterop = false;
        wglInteropAvailable = false;
        ATLAS_REGIONS.close();
        SCENE_CAPTURE.close();
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
        pendingReadyValue = 0;
        pendingFrameIndex = 0;
        pendingFrameUsesGpuSemaphore = false;
        startedAtNanos = 0;
        blockAtlasId = -1;
        reportedTranslucentBudget=false;
    }

    public static void blockAtlasUpdated(net.minecraft.client.renderer.texture.TextureAtlas atlas) {
        TerrainMaterials.refresh(atlas);
        blockAtlasDirty = true;
        ATLAS_REGIONS.reset();
    }

    public static void blockAtlasRegionUpdated(int x, int y, int width, int height) {
        if (!initialized || blockAtlasDirty || GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) != blockAtlasId) return;
        ATLAS_REGIONS.mark(x, y, width, height);
    }

    private static void resolvePendingFrame(boolean captured) {
        if (pendingReadyValue == 0) {
            return;
        }
        if (NativeDx12.hasInteropGpuSyncFault()) {
            throw new IllegalStateException("The D3D12 interop fence missed its deadline or the device was removed.");
        }
        int state = NativeDx12.pollInteropFence(pendingReadyValue);
        if (state < 0) {
            throw new IllegalStateException("The D3D12 interop frame fence is unavailable.");
        }
        long readyValue = pendingReadyValue;
        long frame = pendingFrameIndex;
        boolean useGpuSemaphore = pendingFrameUsesGpuSemaphore;
        pendingReadyValue = 0;
        pendingFrameIndex = 0;
        pendingFrameUsesGpuSemaphore = false;
        if (state == 0 || !captured
                || !PENDING_VIEW.matches(CAPTURED_VIEW, CAPTURED_PROJECTION, cameraX, cameraY, cameraZ)) {
            // No GL sampling occurred. Release the producer even for an incomplete/stale
            // image; this signals GL consumption, not D3D12 rendering completion.
            NativeDx12.signalInteropFence(readyValue);
            return;
        }
        drawImportedTexture(useGpuSemaphore, frame);
        lastFrameSucceeded = true;
    }

    private static void discardPendingFrame() {
        if (pendingReadyValue != 0 && initialized) {
            NativeDx12.signalInteropFence(pendingReadyValue);
            pendingReadyValue = 0;
            pendingFrameIndex = 0;
            pendingFrameUsesGpuSemaphore = false;
        }
    }

    /**
     * Allows the LevelRenderer mixin to skip only the three base chunk layers Direct can reproduce.
     * Vanilla remains active until the capture queue has drained and a successful DX12 frame exists.
     */
    public static boolean shouldSkipVanillaLayer(net.minecraft.client.renderer.RenderType layer) {
        if (!ClientConfig.REPLACE_VANILLA_TERRAIN.get() || !initialized || disabled
                || ClientConfig.TERRAIN_RENDER_DISTANCE_PERCENT.get() < 100
                || !lastFrameSucceeded || !replacementFrame || fallbackBaseSections!=0) {
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
        if (handles == null || handles.length != 5 || handles[0] == 0 || handles[1] == 0 || handles[4] == 0) {
            throw new IllegalStateException("DX12 did not expose its D3D11 device and shared texture.");
        }
        initialized = true;
        interopDevice = WGLNVDXInterop.wglDXOpenDeviceNV(handles[0]);
        if (interopDevice == 0) {
            throw new IllegalStateException("WGL_NV_DX_interop2 could not open the D3D11On12 device.");
        }
        registerTexture(handles[1], handles[4]);
        if (ClientConfig.USE_GPU_SEMAPHORE_SYNC.get()) {
            initializeGpuSemaphore(handles[3]);
        }
        String adapterName = NativeDx12.interopAdapterName();
        int featureFlags = NativeDx12.interopFeatureFlags();
        DirectRenderApi.updateCapabilities(new DirectRenderCapabilities("Direct3D 12",
                adapterName == null || adapterName.isBlank() ? "unknown" : adapterName,
                (featureFlags & 1) != 0, true, true, (featureFlags & 2) != 0, (featureFlags & 4) != 0,
                (featureFlags & 8) != 0, gpuSemaphoreInterop, true, true));
        DirectStatusOverlay.setStatus("D3D12 / initializing");
        program = createProgram();
        sourceTextureUniform = GL20.glGetUniformLocation(program, "sourceTexture");
        sourceDepthUniform = GL20.glGetUniformLocation(program, "sourceDepth");
        writeDepthUniform = GL20.glGetUniformLocation(program, "writeTerrainDepth");
        lightmapUploaded = false;
        vertexArray = GL30.glGenVertexArrays();
        width = frameWidth;
        height = frameHeight;
        frameIndex = 0;
        startedAtNanos = System.nanoTime();
    }

    /**
     * Polls the last submitted D3D12 frame before operations that replace resources or PSOs.
     * The native operations retain a defensive wait, but the render thread never enters it
     * while the previous frame is still executing.
     */
    private static boolean lastSubmittedFrameReady(String operation) {
        if (!initialized || frameIndex == 0) {
            return true;
        }
        int state = NativeDx12.pollInteropFence(frameIndex);
        if (state < 0) {
            throw new IllegalStateException("D3D12 fence failed before " + operation + ".");
        }
        if (state == 0) {
            DirectStatusOverlay.setStatus("D3D12 / waiting before " + operation);
            return false;
        }
        return true;
    }
    private static void resize(int frameWidth, int frameHeight) {
        GL11.glFinish();
        finishPendingGlSync();
        unregisterTexture();
        long[] sharedTexture = NativeDx12.resizeInterop(frameWidth, frameHeight);
        if (sharedTexture == null || sharedTexture.length != 2 || sharedTexture[0] == 0 || sharedTexture[1] == 0) {
            throw new IllegalStateException("DX12/OpenGL interop resize did not return a shared texture.");
        }
        registerTexture(sharedTexture[0], sharedTexture[1]);
        width = frameWidth;
        height = frameHeight;
    }

    private static void registerTexture(long sharedColor, long sharedDepth) {
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            texture = GL11.glGenTextures();
            interopObject = registerResource(sharedColor, texture);
            depthTexture = GL11.glGenTextures();
            depthInteropObject = registerResource(sharedDepth, depthTexture);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
            GL13.glActiveTexture(active);
        }
    }

    private static long registerResource(long resource, int name) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, name);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        long object = WGLNVDXInterop.wglDXRegisterObjectNV(interopDevice, resource, name,
                GL11.GL_TEXTURE_2D, WGLNVDXInterop.WGL_ACCESS_READ_WRITE_NV);
        if (object == 0) throw new IllegalStateException("Could not register DX12 color/depth for OpenGL.");
        return object;
    }

    private static void unregisterTexture() {
        if (interopDevice != 0) {
            if (interopObject != 0) WGLNVDXInterop.wglDXUnregisterObjectNV(interopDevice, interopObject);
            if (depthInteropObject != 0) WGLNVDXInterop.wglDXUnregisterObjectNV(interopDevice, depthInteropObject);
        }
        interopObject = depthInteropObject = 0;
        if (texture != 0) GL11.glDeleteTextures(texture);
        if (depthTexture != 0) GL11.glDeleteTextures(depthTexture);
        texture = depthTexture = 0;
    }

    public static void lightmapUpdated(com.mojang.blaze3d.platform.NativeImage pixels) {
        LIGHT_PIXELS.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
            LIGHT_PIXELS.putInt((y * 16 + x) * 4, pixels.getPixelRGBA(x, y));
        lightmapDirty = true;
    }

    private static void uploadLightmap() {
        if (!lightmapUploaded || lightmapDirty) {
            NativeDx12.updateLightmap(LIGHT_PIXELS);
            lightmapUploaded = true;
            lightmapDirty = false;
        }
    }

    private static void uploadBlockAtlasIfNeeded(Minecraft minecraft) {
        int atlasTexture = minecraft.getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS).getId();
        if (atlasTexture != blockAtlasId) {
            blockAtlasId = atlasTexture;
            blockAtlasDirty = true;
        }
        if (!blockAtlasDirty) {
            ATLAS_REGIONS.update(atlasTexture, ClientConfig.ANIMATED_ATLAS_REFRESH_MILLIS.get(),
                    ClientConfig.BC7_ENCODER_THREADS.get());
            return;
        }
        ATLAS_REGIONS.reset();
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
        int previousSampler0 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int previousDepthTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousSampler1 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int previousDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean writeDepth = replacementFrame && shouldCompositeBeforeVanilla();
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
            var objects = stack.mallocPointer(2);
            objects.put(0, interopObject).put(1, depthInteropObject);
            if (!WGLNVDXInterop.wglDXLockObjectsNV(interopDevice, objects)) {
                throw new IllegalStateException("WGL_NV_DX_interop2 could not lock the shared texture.");
            }
            boolean drawn = false;
            try {
                setEnabled(GL11.GL_DEPTH_TEST, writeDepth);
                if (writeDepth) GL11.glDepthFunc(GL11.GL_ALWAYS);
                GL11.glEnable(GL11.GL_BLEND);
                GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
                GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                        GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glDisable(GL11.GL_STENCIL_TEST);
                GL11.glDepthMask(writeDepth);
                GL11.glColorMask(true, true, true, true);
                GL11.glViewport(0, 0, width, height);
                GL20.glUseProgram(program);
                GL30.glBindVertexArray(vertexArray);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                GL33.glBindSampler(0, 0);
                GL33.glBindSampler(1, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL20.glUniform1i(sourceTextureUniform, 0);
                GL20.glUniform1i(sourceDepthUniform, 1);
                GL20.glUniform1i(writeDepthUniform, writeDepth ? 1 : 0);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                drawn = true;
            } finally {
                objects.put(0, interopObject).put(1, depthInteropObject);
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
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousDepthTexture);
            GL33.glBindSampler(1, previousSampler1);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL33.glBindSampler(0, previousSampler0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL11.glDepthFunc(previousDepthFunc);
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

    private static void queueCpuGlSync(long value) {
        pendingGlSync = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (pendingGlSync == 0) {
            // A failed sync allocation still needs a real completion before CPU-signaling.
            GL11.glFinish();
            NativeDx12.signalInteropFence(value);
            return;
        }
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
            // finishPendingGlSync deletes the failed GLsync before CPU-signaling the native timeline.
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

    private static void initializeGpuSemaphore(long sharedGlDoneFenceHandle) {
        var capabilities = GL.getCapabilities();
        if (sharedGlDoneFenceHandle == 0
                || !capabilities.GL_EXT_semaphore || !capabilities.GL_EXT_semaphore_win32) {
            disableGpuSemaphore("The driver does not expose a shared D3D12 fence semaphore; using polled GLsync.");
            return;
        }
        var imported = InteropSemaphore.importFence(sharedGlDoneFenceHandle);
        int doneSemaphore = imported.semaphore();
        int error = imported.error();
        if (error != GL11.GL_NO_ERROR) {
            String reason = "The OpenGL driver rejected the shared D3D12 fence import (GL error 0x"
                    + Integer.toHexString(error) + "); using polled GLsync for CPU-to-D3D12 synchronization.";
            disableGpuSemaphore(reason);
            return;
        }
        glDoneSemaphore = doneSemaphore;
        gpuSemaphoreInterop = true;
        Direct.LOGGER.info("Using a shared D3D12 timeline semaphore for OpenGL-to-D3D12 synchronization.");
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
        int error;
        for (int attempt = 0; attempt < 64 && (error=GL11.glGetError()) != GL11.GL_NO_ERROR; attempt++) {
            Direct.LOGGER.warn("Pre-existing OpenGL error before semaphore signaling: 0x{}",Integer.toHexString(error));
        }
    }

    private static void disableGpuSemaphore(String reason) {
        gpuSemaphoreInterop = false;
        var previous=DirectRenderApi.service().capabilities();
        DirectRenderApi.updateCapabilities(new DirectRenderCapabilities(previous.backendName(),previous.adapterName(),
                previous.hardwareAdapter(),previous.nativeTerrain(),previous.shaderPasses(),previous.bindlessDescriptors(),
                previous.renderPasses(),previous.pipelineLibrary(),false,previous.wglInterop(),previous.depthInput()));
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
