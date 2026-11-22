package com.redondoguibi.direct;

import java.io.IOException;
import java.nio.ByteBuffer;
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
    private static final AtlasRegionUpdates ATLAS_REGIONS = new AtlasRegionUpdates();
    private static final AtlasFullUpload FULL_ATLAS = new AtlasFullUpload();
    private static final InteropSceneCapture SCENE_CAPTURE=new InteropSceneCapture();
    private static final long RETRY_DELAY_NANOS = 5_000_000_000L;
    private static final int MAX_FAILURES = 3;
    private static final int RECOVERY_FRAMES = 120;
    private static int consecutiveFailures;
    private static int successfulFrames;
    private static String lastFailure = "";
    private static volatile boolean restartRequested;
    private static boolean cleanupNeedsRestart;
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

    // GL e D3D projetam NDC +Y para o topo da viewport; apenas o intervalo Z precisa de conversÃƒÂ£o.
    // O winding CW do D3D ÃƒÂ© configurado no PSO para o sistema de coordenadas da viewport.
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
    private static long[] submittedVisibleKeys = new long[256];
    private static int submittedVisibleCount = -1;
    private static boolean submittedExactCoverage;
    private static int[] visibleSectionIndices = new int[256];
    private static byte[] visibleLayerMasks = new byte[256];
    private static boolean[] visibleOwnership = new boolean[256];
    // Visible, non-empty sections still drawn by vanilla this frame (adoption candidates).
    private static long[] fallbackKeys = new long[256];
    private static int[] fallbackSectionIndices = new int[256];
    private static int fallbackCount;
    private static int[] adoptionSelection = new int[32];
    private static long adoptedSections;
    // Activation timing, reported once per activation.
    private static long activationStartedNanos;
    private static boolean activationReported;
    private static long activationInitNanos;
    private static long activationAtlasNanos;
    private static int visibleCount;
    private static int visibleTranslucentCount;
    private static List<SectionRenderDispatcher.RenderSection> currentVisibleSections=List.of();
    private static int fallbackSections;
    private static int fallbackLayerMask;
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
    private static long pendingGlSince;
    private static long pendingGlFenceValue;
    private static long pendingReadyValue;
    private static long pendingFrameIndex;
    private static boolean pendingFrameUsesGpuSemaphore;
    private static int blockAtlasId = -1;
    private static long[] submittedShadowKeys;
    private static final float[] materialScratch = new float[4];
    private static final float[] geometryScratch = new float[10];
    private static final float[] packGeometryOptions = new float[32];
    private static String requestedPackGeometry = "";
    private static String configuredPackGeometry = "";
    private static String packGeometryFailure = "";
    private static boolean packGeometryActive, packOptionsDirty;
    private static boolean packTranslucent, packSceneInputs, packShadows;

    public static void shaderPackGeometry(String source, float[] options, boolean translucent, boolean sceneInputs, boolean shadows) {
        RenderSystem.assertOnRenderThread();
        if (source == null || source.length() > 1048576 || options == null || options.length != packGeometryOptions.length
                || (sceneInputs && !translucent))
            throw new IllegalArgumentException("Invalid shaderpack geometry layout");
        for (float value : options) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Invalid geometry option");
        }
        if (!requestedPackGeometry.equals(source)) {
            requestedPackGeometry = source;
            packGeometryFailure = "";
        }
        System.arraycopy(options, 0, packGeometryOptions, 0, options.length);
        packOptionsDirty = true;
        packTranslucent = translucent;
        packSceneInputs = sceneInputs;
        packShadows = shadows;
    }

    public static String shaderPackGeometryFailure() { return packGeometryFailure; }

    private static void configurePackGeometry() {
        String source = shouldCompositeBeforeVanilla() ? requestedPackGeometry : "";
        if (!source.equals(configuredPackGeometry)) {
            try {
                NativeDx12.configurePackGeometry(source);
                packGeometryActive = !source.isEmpty();
                packOptionsDirty = packGeometryActive;
                packGeometryFailure = "";
            } catch (IllegalArgumentException ex) {
                NativeDx12.configurePackGeometry("");
                packGeometryActive = false;
                packGeometryFailure = ex.getMessage();
                Direct.LOGGER.warn("Rejected shaderpack terrain programs", ex);
            }
            configuredPackGeometry = source;
        }
        if (packGeometryActive && packOptionsDirty) {
            NativeDx12.setPackGeometryOptions(packGeometryOptions, packSceneInputs, packShadows);
            packOptionsDirty = false;
        }
    }

    private static String[] worldSources = NO_PASS_SOURCES;
    private static String[] rejectedWorldSources;
    private static String worldFailure="";
    public static String worldFailure(){return worldFailure;}

    /** Complete-world post processing; all effect calculations run in native HLSL. */
    public static boolean renderWorld(String[] sources,float[] uniforms){
        RenderSystem.assertOnRenderThread();
        try{
            boolean rendered=renderWorldFrame(sources,uniforms);
            if(rendered)worldRanThisFrame=true;
            return rendered;
        }finally{
            // A frame that skipped the capture must still return the images before D3D12 uses them.
            releaseHeldSceneLock();
        }
    }

    private static boolean renderWorldFrame(String[] sources,float[] uniforms){
        if(sources==null||sources.length<1||sources.length>DirectRenderApi.MAX_WORLD_PASSES||uniforms==null||uniforms.length!=sources.length*DirectRenderApi.MAX_UNIFORM_FLOATS)
            throw new IllegalArgumentException("Invalid world pass layout");
        for(String source:sources)if(source==null||source.isBlank()||source.length()>1048576)throw new IllegalArgumentException("Invalid world shader source");
        for(float value:uniforms)if(!Float.isFinite(value))throw new IllegalArgumentException("Non-finite world uniform");
        if(java.util.Arrays.equals(rejectedWorldSources,sources))return false;
        if (rejectedWorldSources != null) {
            rejectedWorldSources = null;
            worldFailure = "";
        }
        if(!initialized||disabled||!ClientConfig.ENABLE_DX12.get()||!ClientConfig.REPLACE_VANILLA_TERRAIN.get())return false;
        var mc=Minecraft.getInstance();var target=mc.getMainRenderTarget();
        if(Minecraft.useShaderTransparency()||target.width!=width||target.height!=height||GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)!=target.frameBufferId)return false;
        try(MemoryStack stack=MemoryStack.stackPush()){
            // DX12 alone: an earlier stage of this frame may still run on the GPU; the renderer's queue orders them.
            if(!backendOnly()&&!lastSubmittedFrameReady("world post"))return false;
            if(!NativeDx12.isWorldFrameReady())return false;
            if(!java.util.Arrays.equals(worldSources,sources)){
                if(backendOnly()&&frameIndex>0&&!NativeDx12.waitInteropFrame(frameIndex))throw new IllegalStateException("World reconfiguration wait failed");
                NativeDx12.configureWorldPasses(sources);worldSources=sources.clone();
            }
            // DX12 backend alone: the passes read the backend's scene; nothing goes through OpenGL.
            if(backendOnly())return backendWorld(target.frameBufferId,uniforms);
            var objects=stack.pointers(interopObject,depthInteropObject);
            boolean profile = RendererMetrics.recording;
            long start = profile ? System.nanoTime() : 0;
            lockSceneForCapture(objects);
            try{SCENE_CAPTURE.capture(target.frameBufferId,target.getDepthTextureId(),texture,depthTexture,width,height);}
            finally{if(!WGLNVDXInterop.wglDXUnlockObjectsNV(interopDevice,objects))throw new IllegalStateException("Cannot unlock world input");}
            finishGlWork();
            long captured = profile ? System.nanoTime() : 0;
            if(frameIndex>=Long.MAX_VALUE-1)throw new IllegalStateException("World timeline exhausted");
            int result=NativeDx12.renderWorldInterop(uniforms,frameIndex);
            if(result==NativeDx12.FRAME_SKIPPED_BUSY)return false;
            if(result!=0)throw new IllegalStateException("World render failed: "+result);
            long value=++frameIndex;
            if(!NativeDx12.waitInteropFrame(value))throw new IllegalStateException("World output incomplete");
            long rendered = profile ? System.nanoTime() : 0;
            sceneAccess(WGLNVDXInterop.WGL_ACCESS_READ_ONLY_NV);
            var colorOnly = stack.pointers(interopObject);
            if(!WGLNVDXInterop.wglDXLockObjectsNV(interopDevice,colorOnly))throw new IllegalStateException("Cannot lock world output");
            try{SCENE_CAPTURE.presentColor(target.frameBufferId,texture,width,height);}
            finally{if(!WGLNVDXInterop.wglDXUnlockObjectsNV(interopDevice,colorOnly))throw new IllegalStateException("Cannot unlock world output");}
            queueCpuGlSync(value);
            if (profile) {
                // GPU time of the latest completed world frame (the one just waited for).
                double[] gpu = NativeDx12.worldFrameMetrics();
                if (gpu != null && gpu[0] >= 0 && gpu[1] >= 0) {
                    RendererMetrics.worldGpuFrames++;
                    RendererMetrics.worldGpuImportNanos += (long) (gpu[0] * 1e6);
                    RendererMetrics.worldGpuPassNanos += (long) (gpu[1] * 1e6);
                }
                RendererMetrics.worldFrames++;
                RendererMetrics.worldCaptureNanos += captured - start;
                RendererMetrics.worldRenderNanos += rendered - captured;
                RendererMetrics.worldPresentNanos += System.nanoTime() - rendered;
            }
            if (Phase0Capture.ENABLED && packGeometryActive && !packTranslucent && !packSceneInputs && !packShadows
                    && replacementFrame && configuredPasses.isEmpty() && mc.screen == null && mc.getOverlay() == null) {
                Phase0Capture.frame(width, height, submittedVisibleKeys, submittedVisibleCount, fallbackSections, LIGHT_PIXELS,
                        MATRIX, cameraX, cameraY, cameraZ, mc.options.getEffectiveRenderDistance() * 16.0f,
                        configuredPackGeometry, packGeometryOptions, geometryScratch, materialScratch, sources, uniforms);
            }
            worldFailure="";
            // Shadow mode: the same passes again on the DX12 backend's own scene, for the comparison.
            if(com.redondoguibi.direct.backend.Dx12Shadow.ENABLED)backendWorld(target.frameBufferId,uniforms);
            return true;
        }catch(IllegalArgumentException exception){worldFailure=exception.getMessage();rejectedWorldSources=sources.clone();Direct.LOGGER.error("Rejected world shader",exception);return false;}
        catch(RuntimeException|UnsatisfiedLinkError exception){worldFailure=exception.getMessage();rendererFailed("world post",exception);return false;}
    }

    private Dx12GlInterop() {
    }

    /** Chamado pelo LevelRendererMixin com as matrizes reais do frame. */
    public static void captureLevelMatrices(Camera camera, Matrix4f viewRotation, Matrix4f projection) {
        worldRanLastFrame = worldRanThisFrame;
        worldRanThisFrame = false;
        if(!ClientConfig.ENABLE_DX12.get()){matricesCaptured=false;return;}
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
        if(!(packGeometryActive&&packTranslucent)&&!ClientConfig.DX12_TRANSLUCENCY.get()&&effects.water()==0&&effects.reflections()==0&&DirectRenderApi.terrainSettings().materials().waterAbsorption()==0)return false;
        if(Minecraft.useShaderTransparency()||GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)!=target.frameBufferId)return false;
        if(!PENDING_VIEW.matches(CAPTURED_VIEW,CAPTURED_PROJECTION,cameraX,cameraY,cameraZ)||!translucentCovered)return false;
        if(visibleTranslucentCount==0)return true;
        try(MemoryStack stack=MemoryStack.stackPush()){
            if(frameIndex>=Long.MAX_VALUE-1)throw new IllegalStateException("Interop frame timeline exhausted");
            int readiness=NativeDx12.translucentFrameStatus();
            if(readiness==NativeDx12.FRAME_SKIPPED_BUSY)return false;
            if(readiness==NativeDx12.FRAME_SKIPPED_BUDGET){
                if(!reportedTranslucentBudget){Direct.LOGGER.warn("DX12 translucent index budget exceeded; keeping vanilla transparency and DX12 opaque terrain. Increase uploadRingBufferMiB if needed.");reportedTranslucentBudget=true;}
                DirectStatusOverlay.setStatus("translucency_budget");return false;
            }
            if(readiness!=NativeDx12.FRAME_RENDERED_CPU_SYNC)throw new IllegalStateException("Translucent preflight failed");
            // DX12 backend alone: the translucent terrain is drawn over the backend's scene; nothing goes through OpenGL.
            if(backendOnly())return backendTranslucent(target.frameBufferId);
            long captureStart=System.nanoTime();
            var objects=stack.pointers(interopObject,depthInteropObject);
            lockSceneForCapture(objects);
            try{SCENE_CAPTURE.capture(target.frameBufferId,target.getDepthTextureId(),texture,depthTexture,width,height);}
            finally{if(!WGLNVDXInterop.wglDXUnlockObjectsNV(interopDevice,objects))throw new IllegalStateException("Cannot unlock scene capture");}
            // Imported scene writes must complete before D3D12 can sample them. The shared
            // consumption fence alone only covers the preceding base-layer draw.
            finishGlWork();
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
            // The world pass captures these same two images after vanilla finishes the scene.
            boolean holdForWorld = worldRanLastFrame;
            sceneAccess(holdForWorld ? WGLNVDXInterop.WGL_ACCESS_READ_WRITE_NV : WGLNVDXInterop.WGL_ACCESS_READ_ONLY_NV);
            if(!WGLNVDXInterop.wglDXLockObjectsNV(interopDevice,objects))throw new IllegalStateException("Cannot lock transparent output");
            try {
                SCENE_CAPTURE.present(target.frameBufferId, texture, depthTexture, width, height);
                heldSceneLock = holdForWorld;
                heldSceneWritable = holdForWorld;
            } finally {
                if (!heldSceneLock && !WGLNVDXInterop.wglDXUnlockObjectsNV(interopDevice, objects))
                    throw new IllegalStateException("Cannot unlock transparent output");
            }
            queueCpuGlSync(value);
            if(RendererMetrics.recording){RendererMetrics.translucentPresentNanos+=System.nanoTime()-presentStart;RendererMetrics.translucentFrames++;}
            // Shadow mode: the same translucent terrain again over the DX12 backend's own scene, for the comparison.
            if(com.redondoguibi.direct.backend.Dx12Shadow.ENABLED)backendTranslucent(target.frameBufferId);
            return true;
        }catch(RuntimeException|UnsatisfiedLinkError ex){
            rendererFailed("translucency", ex);
            return false;
        }
    }

    // ---- Shader-pack stages on the DX12 backend's scene (-Ddirect.phase1.shadow) ------------------------------------
    // The backend shares this renderer's device. Instead of the OpenGL capture and presentation, it writes its scene
    // into the pack's input images (exportScene), the native stage runs unchanged, and its output replaces the backend's
    // scene (importScene). The stage is still awaited on the CPU, like the OpenGL path, so the frame slots stay free.

    /** -Ddirect.phase1.skipGl with present: the DX12 backend alone renders and shows the frame. */
    private static boolean backendOnly() {
        return com.redondoguibi.direct.backend.Dx12Shadow.SKIP_GL && com.redondoguibi.direct.backend.Dx12Shadow.presenting();
    }

    private static void finishSceneForBackend() {
        // Even in DX12-only mode GLFW's context still receives vanilla texture uploads.
        // Drain them before switching queues so the driver doesn't accumulate GL work.
        releaseHeldSceneLock();
        finishGlWork();
    }

    private static boolean backendTranslucent(int framebuffer) {
        if (NativeDx12.translucentFrameStatus() != NativeDx12.FRAME_RENDERED_CPU_SYNC) return false;
        finishSceneForBackend();
        if (!com.redondoguibi.direct.backend.Dx12Shadow.exportScene(framebuffer)) return false;
        int result = NativeDx12.renderTranslucentInterop(MATRIX, cameraX, cameraY, cameraZ, frameIndex);
        if (result == NativeDx12.FRAME_SKIPPED_BUSY || result == NativeDx12.FRAME_SKIPPED_BUDGET) return false;
        if (result != NativeDx12.FRAME_RENDERED_CPU_SYNC) throw new IllegalStateException("Translucent submission failed");
        long value = ++frameIndex;
        long waitStart = System.nanoTime();
        // Shadow mode keeps OpenGL's ordering; DX12 alone: the import waits for the frame on the GPU.
        if (!backendOnly() && !NativeDx12.waitInteropFrame(value)) throw new IllegalStateException("Translucent frame incomplete");
        com.redondoguibi.direct.backend.Dx12Shadow.stageWait(System.nanoTime() - waitStart);
        boolean imported = com.redondoguibi.direct.backend.Dx12Shadow.importScene(framebuffer, value, true);
        consumedByBackend(value);
        return imported;
    }

    private static boolean backendWorld(int framebuffer, float[] uniforms) {
        if ((!backendOnly() && !lastSubmittedFrameReady("world post")) || !NativeDx12.isWorldFrameReady()) return false;
        finishSceneForBackend();
        if (!com.redondoguibi.direct.backend.Dx12Shadow.exportScene(framebuffer)) return false;
        int result = backendOnly() ? NativeDx12.renderWorldForBackend(uniforms, frameIndex)
                : NativeDx12.renderWorldInterop(uniforms, frameIndex);
        if (result == NativeDx12.FRAME_SKIPPED_BUSY) return false;
        if (result != 0) throw new IllegalStateException("World render failed: " + result);
        long value = ++frameIndex;
        long waitStart = System.nanoTime();
        if (!backendOnly() && !NativeDx12.waitInteropFrame(value)) throw new IllegalStateException("World output incomplete");
        com.redondoguibi.direct.backend.Dx12Shadow.stageWait(System.nanoTime() - waitStart);
        if (RendererMetrics.recording) {
            double[] gpu = NativeDx12.worldFrameMetrics();
            if (gpu != null && gpu[0] >= 0 && gpu[1] >= 0) com.redondoguibi.direct.backend.Dx12Shadow.packGpu(gpu[0], gpu[1]);
        }
        boolean imported = com.redondoguibi.direct.backend.Dx12Shadow.importScene(framebuffer, value, false);
        consumedByBackend(value);
        if (imported) worldFailure = "";
        return imported;
    }

    /**
     * The next terrain frame waits until every submitted frame value was consumed (glDoneFence). The backend executes
     * its import before that frame is submitted (its end-of-frame flush waits), so the value is released now.
     */
    private static void consumedByBackend(long value) {
        NativeDx12.signalInteropFence(value);
    }

    public static void captureVisibleSections(List<SectionRenderDispatcher.RenderSection> sections) {
        currentVisibleSections=sections;
        // Stage only. Ownership is evaluated once, after native uploads, for this frame.
        lastFrameSucceeded=false;
        ownedBuffers.clear();
    }

    private static void submitVisibleSections() {
        if (visibleCount == submittedVisibleCount && replacementFrame == submittedExactCoverage
                && java.util.Arrays.equals(visibleKeys, 0, visibleCount, submittedVisibleKeys, 0, visibleCount)) return;

        NativeDx12.setVisibleSections(visibleKeys, visibleCount, replacementFrame);
        // The JNI call copies the prefix. Swap scratch arrays so compaction next frame cannot overwrite it.
        long[] previous = submittedVisibleKeys;
        submittedVisibleKeys = visibleKeys;
        visibleKeys = previous.length < submittedVisibleKeys.length ? new long[submittedVisibleKeys.length] : previous;
        submittedVisibleCount = visibleCount;
        submittedExactCoverage = replacementFrame;
    }

    private static void selectUploadedSections() {
        var sections=currentVisibleSections;
        visibleCount=0;visibleTranslucentCount=0;fallbackSections=fallbackLayerMask=0;translucentCovered=true;ownedBuffers.clear();
        if(!ClientConfig.ENABLE_DX12.get())return;
        fallbackCount=0;
        if(visibleKeys.length<sections.size()){
            // The visible list grows gradually as distant chunks arrive.
            int capacity = Math.max(sections.size(), visibleKeys.length + visibleKeys.length / 2);
            visibleKeys = new long[capacity];
            visibleSectionIndices = new int[capacity];
            visibleLayerMasks = new byte[capacity];
            visibleOwnership = new boolean[capacity];
            fallbackKeys = new long[capacity];
            fallbackSectionIndices = new int[capacity];
        }
        int candidates=0;
        for(int i=0;i<sections.size();i++){
            var section=sections.get(i);
            var compiled=section.getCompiled();
            int mask=(!compiled.isEmpty(RenderType.solid())?1:0)
                    |(!compiled.isEmpty(RenderType.cutoutMipped())?2:0)
                    |(!compiled.isEmpty(RenderType.cutout())?4:0)
                    |(!compiled.isEmpty(RenderType.translucent())?8:0);
            if(mask==0)continue;
            visibleKeys[candidates]=SectionPos.of(section.getOrigin()).asLong();
            visibleSectionIndices[candidates]=i;
            visibleLayerMasks[candidates++]=(byte)mask;
        }
        TerrainMeshStore.copyOwnership(visibleKeys,candidates,visibleOwnership);
        for(int i=0;i<candidates;i++){
            int mask=visibleLayerMasks[i];
            boolean translucent=(mask&8)!=0;
            boolean owned=visibleOwnership[i];
            if(translucent){visibleTranslucentCount++;if(!owned)translucentCovered=false;}
            if(!owned){
                fallbackSections++;
                fallbackLayerMask |= mask;
                // Read before compaction overwrites lower indices; i >= visibleCount always holds.
                fallbackKeys[fallbackCount]=visibleKeys[i];
                fallbackSectionIndices[fallbackCount++]=visibleSectionIndices[i];
                continue;
            }
            // Compact in place, preserving vanilla order (including coplanar tie order).
            visibleKeys[visibleCount++]=visibleKeys[i];
            var section=sections.get(visibleSectionIndices[i]);
            if((mask&1)!=0)ownedBuffers.add(section.getBuffer(RenderType.solid()));
            if((mask&2)!=0)ownedBuffers.add(section.getBuffer(RenderType.cutoutMipped()));
            if((mask&4)!=0)ownedBuffers.add(section.getBuffer(RenderType.cutout()));
        }
    }

    /**
     * Requests asynchronous rebuilds for visible sections that were compiled before capture began.
     * Vanilla keeps drawing their current mesh until the rebuilt one commits, so the world never
     * empties; the visible list is near-first, so the closest terrain is adopted first.
     */
    private static void adoptFallbackSections(){
        if(fallbackCount==0||TerrainMeshStore.arenaBackoffActive())return;
        var sections=currentVisibleSections;
        int clean=0;
        for(int i=0;i<fallbackCount;i++){
            var section=sections.get(fallbackSectionIndices[i]);
            // Already dirty sections are rebuilt (and captured) by vanilla this frame.
            if(section.isDirty())continue;
            fallbackKeys[clean]=fallbackKeys[i];
            fallbackSectionIndices[clean++]=fallbackSectionIndices[i];
        }
        int limit=ClientConfig.MAX_SECTION_ADOPTIONS_PER_FRAME.get();
        if(adoptionSelection.length<limit)adoptionSelection=new int[limit];
        int chosen=TerrainMeshStore.requestAdoption(fallbackKeys,clean,limit,adoptionSelection);
        for(int i=0;i<chosen;i++)sections.get(fallbackSectionIndices[adoptionSelection[i]]).setDirty(false);
        adoptedSections+=chosen;
    }

    /**
     * World render stages after which recorded OpenGL work is handed to the GPU. Measured on the
     * Intel UHD 630 driver (QueuePriorityBenchmark): without intermediate flushes the driver holds
     * the frame tail until the swap, and the next DX12 terrain frame waits behind it.
     */
    static final java.util.Set<net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage> FLUSH_STAGES = java.util.Set.of(
            net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_SKY,
            net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_ENTITIES,
            net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES,
            net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS,
            net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_PARTICLES,
            net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_WEATHER,
            net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL);

    /** Only while the DX12 backend runs: vanilla keeps the driver's own submission behavior. */
    static void flushAfterStage() {
        if (!initialized || disabled || !ClientConfig.ENABLE_DX12.get() || !ClientConfig.FLUSH_OPENGL_STAGES.get()) return;
        GL11.glFlush();
    }

    /** Frozen ownership for this frame; never suppress a buffer before successful composition. */
    public static boolean shouldSkipVanillaBuffer(com.mojang.blaze3d.vertex.VertexBuffer buffer){
        return lastFrameSucceeded&&replacementFrame&&ownedBuffers.contains(buffer);
    }

    public static boolean shouldSkipVanillaSection(SectionRenderDispatcher.RenderSection section, RenderType layer) {
        return lastFrameSucceeded && replacementFrame && ownedBuffers.contains(section.getBuffer(layer));
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
            // DX12 alone: the backend waits for the terrain frame on the GPU.
            boolean completed=backendOnly()||NativeDx12.waitInteropFrame(pendingReadyValue);
            if(RendererMetrics.recording)RendererMetrics.dxWaitNanos+=System.nanoTime()-waitStart;
            if(backendOnly())com.redondoguibi.direct.backend.Dx12Shadow.stageWait(System.nanoTime()-waitStart);
            if(!completed)
                throw new IllegalStateException("D3D12 did not complete the current terrain frame.");
            if (ClientConfig.PROFILE_RENDERER.get()) {
                long metricsStart = System.nanoTime();
                double[] metrics = NativeDx12.frameMetrics();
                RendererMetrics.compositeMetricsNanos += System.nanoTime() - metricsStart;
                if (metrics != null && metrics[0] >= 0) {
                    RendererMetrics.gpuNanos += (long) (metrics[0] * 1e6);
                    RendererMetrics.nativeDraws += (long) metrics[2];
                    RendererMetrics.nativeFrames++;
                    if(metrics.length>=5){RendererMetrics.gpuArenaBytes=(long)metrics[3];RendererMetrics.uploadRingBytes=(long)metrics[4];}
                    if(metrics.length>=7){RendererMetrics.faceVisibleIndices+=(long)metrics[5];RendererMetrics.faceSubmittedIndices+=(long)metrics[6];}
                }
            }
            if (TerrainMeshStore.hasCaptureFault()) {
                discardPendingFrame();
                replacementFrame = false;
                return;
            }
            resolvePendingFrame(true);
        } catch (RuntimeException | UnsatisfiedLinkError exception) {
            rendererFailed("composition", exception);
        } finally {
            RendererMetrics.compositeNanos += System.nanoTime() - started;
        }
    }

    public static void renderTerrainOverlay() {
        lastFrameSucceeded = false;
        releaseHeldSceneLock();
        if (!reportedHook) {
            Direct.LOGGER.info("DX12/OpenGL interop terrain composite hook reached.");
            reportedHook = true;
        }
        boolean captured = matricesCaptured;
        matricesCaptured = false;
        // A failed teardown must not become another per-frame or per-tick retry loop.
        if (cleanupNeedsRestart) return;
        if (!ClientConfig.ENABLE_DX12.get()) {
            TerrainMeshStore.clearCaptureFault();
            if (initialized || terrainCaptureEnabled) {
                disableTerrainCapture();
                shutdown();
            }
            disabled = false;
            retryAfterNanos = 0;
            consecutiveFailures = successfulFrames = 0;
            DirectStatusOverlay.setStatus("disabled");
            return;
        }
        if (disabled) return;
        if (TerrainMeshStore.hasCaptureFault()) {
            rendererFailed("terrain capture", new IllegalStateException("Terrain capture failed; see the preceding capture error in latest.log."));
            TerrainMeshStore.clearCaptureFault();
            return;
        }
        if (!initialized && retryAfterNanos != 0 && System.nanoTime() - retryAfterNanos < 0) {
            long seconds = Math.max(1, (retryAfterNanos - System.nanoTime() + 999_999_999L) / 1_000_000_000L);
            DirectStatusOverlay.setStatus("retrying", seconds, consecutiveFailures, MAX_FAILURES);
            return;
        }

        try {
            if (!initialized) {
                var reason = DirectEnvironment.unavailableReason();
                if (reason != null) {
                    disabled = true;
                    lastFailure = reason.getString();
                    DirectStatusOverlay.setStatus("unavailable", reason);
                    return;
                }
            }
            if (!pollCpuGlSync()) {
                if (System.nanoTime()-pendingGlSince>=5_000_000_000L)
                    throw new IllegalStateException("OpenGL did not release the shared terrain image.");
                // The previous GL consumer still owns the shared image. Render vanilla this
                // frame instead of serializing the render thread behind a multi-second wait.
                DirectStatusOverlay.setStatus("waiting_gl");
                return;
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
                    DirectStatusOverlay.setStatus("wgl");
                    lastFailure = "WGL_NV_DX_interop2 is unavailable on the active OpenGL driver.";
                    disabled = true;
                    disableTerrainCapture();
                    return;
                }
                wglInteropAvailable = true;
            }

            Minecraft minecraft = Minecraft.getInstance();
            if (!terrainCaptureEnabled) {
                // No allChanged(): rebuilding the whole ViewArea at once froze the game. Sections
                // compiled before capture keep their vanilla draw and are adopted a few per frame.
                TerrainMeshStore.setCaptureEnabled(true);
                terrainCaptureEnabled = true;
                activationStartedNanos = System.nanoTime();
                activationReported = false;
                adoptedSections = 0;
                activationAtlasNanos = 0;
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
                DirectStatusOverlay.setStatus("waiting_gl");
                return;
            }
            if (!captured) {
                return;
            }
            boolean nativeOnly = backendOnly();
            long waitStart = nativeOnly ? System.nanoTime() : 0;
            boolean slotReady = NativeDx12.isCurrentFrameSlotReady();
            if (nativeOnly) com.redondoguibi.direct.backend.Dx12Shadow.stageWait(System.nanoTime() - waitStart);
            if (!slotReady) {
                DirectStatusOverlay.setStatus("waiting_gpu");
                return;
            }
            // Hybrid still needs the previous hand-off to GL. Backend-only submissions
            // are ordered on the GPU and native updates protect mutable bindings.
            if (!nativeOnly && !lastSubmittedFrameReady("terrain resource updates")) return;
            configurePackGeometry();
            TerrainMeshStore.uploadPending();
            selectUploadedSections();
            adoptFallbackSections();
            if(RendererMetrics.recording){RendererMetrics.ownershipFrames++;RendererMetrics.ownedSectionSamples+=visibleCount;RendererMetrics.fallbackSectionSamples+=fallbackSections;if(visibleCount>0&&(fallbackLayerMask&7)!=0)RendererMetrics.mixedFrames++;}
            replacementFrame = shouldCompositeBeforeVanilla() && ClientConfig.TERRAIN_RENDER_DISTANCE_PERCENT.get() == 100;
            if (shouldCompositeBeforeVanilla() && (!replacementFrame||visibleCount==0)) {
                DirectStatusOverlay.setStatus(replacementFrame ? "uploads" : "distance");
                return;
            }
            if (!uploadBlockAtlasIfNeeded(minecraft)) {
                DirectStatusOverlay.setStatus("atlas");
                return;
            }
            uploadLightmap();
            submitVisibleSections();
            if(DirectRenderApi.terrainEffects().shadows()>0){
                var keys=TerrainMeshStore.shadowKeys();
                if(keys!=submittedShadowKeys){NativeDx12.setShadowSections(keys);submittedShadowKeys=keys;}
            }

            CLIP_CORRECTION.mul(CAPTURED_PROJECTION, VIEW_PROJECTION).mul(CAPTURED_VIEW);
            VIEW_PROJECTION.get(MATRIX);
            float[] fog = RenderSystem.getShaderFogColor();
            System.arraycopy(fog, 0, MATRIX, 16, 4);
            float fogMultiplier=minecraft.gameRenderer.getMainCamera().getFluidInCamera()==net.minecraft.world.level.material.FogType.NONE?DirectRenderApi.shaderFogDistance():1;
            MATRIX[20] = RenderSystem.getShaderFogStart()*fogMultiplier;
            MATRIX[21] = RenderSystem.getShaderFogEnd()*fogMultiplier;
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
                if (!nativeOnly && !lastSubmittedFrameReady("pass reconfiguration")) {
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
            NativeDx12.setFaceCullingEnabled(ClientConfig.FACE_CULLING.get());
            TerrainBufferPacker.nativePacking = ClientConfig.NATIVE_VERTEX_PACKING.get();
            var terrainSettings=DirectRenderApi.terrainSettings();
            var effects=terrainSettings.geometry();
            var materials=terrainSettings.materials();
            materialScratch[0]=materials.specular();materialScratch[1]=materials.wetness();materialScratch[2]=materials.emissive();materialScratch[3]=materials.waterAbsorption();
            NativeDx12.setMaterialEffects(materialScratch);
            float angle=minecraft.level.getSunAngle(0);
            geometryScratch[0]=minecraft.level.dimensionType().hasSkyLight()?effects.shadows():0;
            geometryScratch[1]=effects.water();geometryScratch[2]=effects.reflections();geometryScratch[3]=effects.wind();geometryScratch[4]=effects.quality();
            geometryScratch[5]=-(float)Math.sin(angle);geometryScratch[6]=minecraft.level.dimensionType().hasSkyLight()?(float)Math.cos(angle):0;
            geometryScratch[7]=0;geometryScratch[8]=(minecraft.level.getGameTime()%240000)/20f;geometryScratch[9]=minecraft.level.getRainLevel(0);
            NativeDx12.setTerrainEffects(geometryScratch);
            boolean useGpuSync = gpuSemaphoreInterop && ClientConfig.USE_GPU_SEMAPHORE_SYNC.get();
            long submittedAt = System.nanoTime();
            int renderResult = NativeDx12.renderInterop(MATRIX, cameraX, cameraY, cameraZ,
                    renderDistance, frameIndex,
                    elapsedSeconds, passUniformScratch, useGpuSync, ClientConfig.USE_EXECUTE_INDIRECT.get());
            RendererMetrics.submitNanos += System.nanoTime() - submittedAt;
            if (renderResult == NativeDx12.FRAME_SKIPPED_BUSY) {
                DirectStatusOverlay.setStatus("waiting_gpu");
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
            if (replacementFrame) DirectStatusOverlay.setStatus("submitted");
        } catch (IOException | RuntimeException | UnsatisfiedLinkError exception) {
            rendererFailed("terrain rendering", exception);
        }
    }

    /** Schedule teardown at a client tick boundary, never halfway through a terrain frame. */
    public static void requestRestart() {
        restartRequested = true;
    }

    static String lastFailure() {
        return lastFailure;
    }

    private static void rendererFailed(String stage, Throwable exception) {
        try { releaseHeldSceneLock(); } catch (RuntimeException ignored) { heldSceneLock = false; }
        lastFailure = stage + ": " + exception.getClass().getSimpleName() + ": " + exception.getMessage();
        Direct.LOGGER.error("Direct failed during {}; retaining vanilla terrain.", stage, exception);
        lastFrameSucceeded = false;
        successfulFrames = 0;
        consecutiveFailures++;
        boolean cleanupFailed = false;
        try {
            disableTerrainCapture();
        } catch (RuntimeException | LinkageError cleanup) {
            cleanupFailed = true;
            Direct.LOGGER.error("Could not release Direct terrain captures.", cleanup);
        }
        try {
            shutdown();
        } catch (RuntimeException | LinkageError cleanup) {
            cleanupFailed = true;
            Direct.LOGGER.error("Could not shut down the Direct backend.", cleanup);
        }
        DirectRenderApi.updateCapabilities(DirectRenderCapabilities.unavailable());
        cleanupNeedsRestart = cleanupFailed;
        disabled = cleanupFailed || consecutiveFailures >= MAX_FAILURES;
        retryAfterNanos = disabled ? 0 : System.nanoTime() + RETRY_DELAY_NANOS * consecutiveFailures;
        DirectStatusOverlay.setStatus(disabled ? "suspended" : "retrying", 5 * consecutiveFailures,
                consecutiveFailures, MAX_FAILURES);
    }

    /** Release world-owned meshes/targets even when no renderLevel hook runs in the menu. */
    public static void idle() {
        if(!RenderSystem.isOnRenderThread()){RenderSystem.recordRenderCall(Dx12GlInterop::idle);return;}
        boolean restart = restartRequested;
        restartRequested = false;
        if (cleanupNeedsRestart && !restart) return;
        boolean noWorld = Minecraft.getInstance().level == null;
        if (!restart && !noWorld && ClientConfig.ENABLE_DX12.get()) return;
        if (initialized || terrainCaptureEnabled || restart) {
            try {
                disableTerrainCapture();
                shutdown();
            } catch (RuntimeException | LinkageError exception) {
                rendererFailed("restart / world exit", exception);
                return;
            }
        }
        renderedLevel=null;matricesCaptured=false;replacementFrame=false;
        if (restart || !ClientConfig.ENABLE_DX12.get()) {
            cleanupNeedsRestart = false;
            disabled = false;
            consecutiveFailures = successfulFrames = 0;
            retryAfterNanos = 0;
            TerrainMeshStore.clearCaptureFault();
            DirectStatusOverlay.setStatus(ClientConfig.ENABLE_DX12.get() ? "initializing" : "disabled");
        }
    }

    public static void shutdown() {
        try { releaseHeldSceneLock(); } catch (RuntimeException exception) { Direct.LOGGER.warn("Held scene lock release failed during shutdown", exception); }
        worldRanThisFrame = worldRanLastFrame = false;
        configuredPackGeometry = "";
        packGeometryFailure = "";
        packGeometryActive = false;
        worldSources=NO_PASS_SOURCES;
        rejectedWorldSources=null;
        submittedShadowKeys = null;
        configuredPassRevision = -1L;
        configuredPasses = List.of();
        lastFrameSucceeded = false;
        ownedBuffers.clear();
        currentVisibleSections=List.of();
        if (pendingGlSync != 0 || glDoneSemaphore != 0 || interopObject != 0) finishGlWork();
        if (glDoneSemaphore != 0) {
            EXTSemaphore.glDeleteSemaphoresEXT(glDoneSemaphore);
            glDoneSemaphore = 0;
        }
        gpuSemaphoreInterop = false;
        wglInteropAvailable = false;
        ATLAS_REGIONS.close();
        FULL_ATLAS.close();
        blockAtlasDirty = true;
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

    private static final int ATLAS_SCOPE_NONE = 0, ATLAS_SCOPE_BLOCK = 1, ATLAS_SCOPE_OTHER = 2;
    /** Which atlas is cycling its animations on the render thread; avoids a GL binding query per sprite upload. */
    private static int atlasScope = ATLAS_SCOPE_NONE;

    public static int enterAtlasAnimationScope(boolean blockAtlas) {
        int previous = atlasScope;
        atlasScope = blockAtlas ? ATLAS_SCOPE_BLOCK : ATLAS_SCOPE_OTHER;
        return previous;
    }

    public static void exitAtlasAnimationScope(int previous) { atlasScope = previous; }

    /** Vanilla is about to close the block atlas sprite images: drop references to them. */
    public static void blockAtlasCleared() { ATLAS_REGIONS.forgetSources(); }

    /**
     * A sprite upload wrote {@code width x height} texels from {@code (frameX, frameY)} of {@code levels} at
     * {@code (x, y)}. Inside {@link net.minecraft.client.renderer.texture.TextureAtlas#cycleAnimationFrames} the
     * target atlas is known; other callers fall back to the bound GL texture, as before.
     */
    public static void blockAtlasRegionUpdated(int x, int y, int frameX, int frameY, int width, int height,
                                               com.mojang.blaze3d.platform.NativeImage[] levels) {
        if (!initialized || blockAtlasDirty || atlasScope == ATLAS_SCOPE_OTHER) return;
        if (atlasScope == ATLAS_SCOPE_NONE && GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) != blockAtlasId) return;
        ATLAS_REGIONS.markUpload(x, y, frameX, frameY, width, height, new SpriteImages(levels));
    }

    /** The CPU images vanilla passes to glTexSubImage2D; unusable levels report address 0 and are read back instead. */
    private record SpriteImages(com.mojang.blaze3d.platform.NativeImage[] images) implements AtlasRegionUpdates.SpriteSource {
        @Override public int levels() { return images.length; }
        @Override public long address(int level) {
            var image = images[level];
            if (image == null || image.format() != com.mojang.blaze3d.platform.NativeImage.Format.RGBA) return 0;
            return ((Object) image) instanceof com.redondoguibi.direct.mixin.NativeImageAccessor accessor ? accessor.direct$pixels() : 0;
        }
        @Override public int width(int level) { return images[level] == null ? 0 : images[level].getWidth(); }
        @Override public int height(int level) { return images[level] == null ? 0 : images[level].getHeight(); }
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
        if ((state == 0 && !backendOnly()) || !captured
                || !PENDING_VIEW.matches(CAPTURED_VIEW, CAPTURED_PROJECTION, cameraX, cameraY, cameraZ)) {
            // No GL sampling occurred. Release the producer even for an incomplete/stale
            // image; this signals GL consumption, not D3D12 rendering completion.
            NativeDx12.signalInteropFence(readyValue);
            return;
        }
        // DX12 backend (-Ddirect.phase1.shadow): the same composite, recorded in DX12 from the shared device.
        boolean backendComposite = com.redondoguibi.direct.backend.Dx12Shadow.ENABLED
                && com.redondoguibi.direct.backend.Dx12Shadow.compositeTerrain(readyValue, replacementFrame && shouldCompositeBeforeVanilla());
        if (backendComposite && com.redondoguibi.direct.backend.Dx12Shadow.SKIP_GL
                && com.redondoguibi.direct.backend.Dx12Shadow.presenting()) {
            // DX12 alone renders: no WGL lock and no OpenGL draw. The backend executes its composite at the end of
            // this frame, before the next terrain frame is submitted, so the image may be released now.
            NativeDx12.signalInteropFence(readyValue);
        } else {
            drawImportedTexture(useGpuSemaphore, frame);
        }
        lastFrameSucceeded = true;
        if (replacementFrame) reportActivation();
        // A successful submit is not proof of recovery: count only imported frames.
        if (successfulFrames < RECOVERY_FRAMES && ++successfulFrames == RECOVERY_FRAMES) consecutiveFailures = 0;
        DirectStatusOverlay.setStatus(replacementFrame ? (fallbackSections > 0 ? "mixed" : "active") : "overlay",
                net.minecraft.network.chat.Component.translatable("direct.sync." + (useGpuSemaphore ? "gpu" : "cpu")), fallbackSections);
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
    /** For the backend world probe: Direct draws the whole visible terrain (no section left on vanilla). */
    public static boolean terrainReplaced() {
        return initialized && !disabled && lastFrameSucceeded && replacementFrame && fallbackSections == 0;
    }

    public static boolean shouldSkipVanillaLayer(net.minecraft.client.renderer.RenderType layer) {
        if (!ClientConfig.REPLACE_VANILLA_TERRAIN.get() || !initialized || disabled
                || ClientConfig.TERRAIN_RENDER_DISTANCE_PERCENT.get() < 100
                || !lastFrameSucceeded || !replacementFrame) {
            return false;
        }
        if (layer == RenderType.solid()) return (fallbackLayerMask & 1) == 0;
        if (layer == RenderType.cutoutMipped()) return (fallbackLayerMask & 2) == 0;
        return layer == RenderType.cutout() && (fallbackLayerMask & 4) == 0;
    }

    private static void disableTerrainCapture() {
        TerrainMeshStore.setCaptureEnabled(false);
        if (terrainCaptureEnabled) {
            TerrainMeshStore.clear();
            terrainCaptureEnabled = false;
        }
    }

    private static void initialize(int frameWidth, int frameHeight) throws IOException {
        submittedVisibleCount = -1;
        long initStarted = System.nanoTime();
        NativeLibrary.load();
        long[] handles = NativeDx12.initializeInteropCached(frameWidth, frameHeight,
                ClientConfig.PARALLEL_SECTION_RECORDING.get(), ClientConfig.UPLOAD_RING_BUFFER_MIB.get(),
                ClientConfig.TERRAIN_ARENA_PAGE_MIB.get(), ClientConfig.MAX_TERRAIN_ARENA_MIB.get(),
                cacheDirectory());
        if (handles == null || handles.length != 5 || handles[0] == 0 || handles[1] == 0 || handles[4] == 0) {
            throw new IllegalStateException("DX12 did not expose its D3D11 device and shared texture.");
        }
        initialized = true;
        interopDevice = WGLNVDXInterop.wglDXOpenDeviceNV(handles[0]);
        if (interopDevice == 0) {
            throw new IllegalStateException("WGL_NV_DX_interop2 could not open the D3D11On12 device.");
        }
        registerTexture(handles[1], handles[4], frameWidth, frameHeight);
        if (ClientConfig.USE_GPU_SEMAPHORE_SYNC.get()) {
            initializeGpuSemaphore(handles[3]);
        }
        String adapterName = NativeDx12.interopAdapterName();
        int featureFlags = NativeDx12.interopFeatureFlags();
        DirectRenderApi.updateCapabilities(new DirectRenderCapabilities("Direct3D 12",
                adapterName == null || adapterName.isBlank() ? "unknown" : adapterName,
                (featureFlags & 1) != 0, true, true, (featureFlags & 2) != 0, (featureFlags & 4) != 0,
                (featureFlags & 8) != 0, gpuSemaphoreInterop, true, true));
        DirectStatusOverlay.setStatus("initializing");
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
        activationInitNanos = startedAtNanos - initStarted;
        int[] cache = NativeDx12.initializationCacheStats();
        Direct.LOGGER.info("Direct native renderer initialized in {} ms (shaders: {} cached, {} compiled; PSO library {}).",
                activationInitNanos / 1_000_000L, cache[0], cache[1], cache[2] != 0 ? "loaded from disk" : "rebuilt");
    }

    /** Disk cache for shader bytecode and the PSO library; null (no cache) when unavailable. */
    private static String cacheDirectory() {
        try {
            var directory = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("cache").resolve("direct");
            java.nio.file.Files.createDirectories(directory);
            return directory.toAbsolutePath().toString();
        } catch (IOException | RuntimeException exception) {
            Direct.LOGGER.warn("Direct shader cache is unavailable; shaders compile on every activation.", exception);
            return null;
        }
    }

    /** One summary line per activation, once the first replacement frame was imported. */
    private static void reportActivation() {
        if (activationReported || activationStartedNanos == 0) return;
        activationReported = true;
        Direct.LOGGER.info("Direct activation: first DX12 frame after {} ms (native init {} ms, atlas {} ms, {} sections adopted, {} still on vanilla).",
                (System.nanoTime() - activationStartedNanos) / 1_000_000L, activationInitNanos / 1_000_000L,
                activationAtlasNanos / 1_000_000L, adoptedSections, fallbackSections);
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
            DirectStatusOverlay.setStatus("waiting_gpu");
            return false;
        }
        return true;
    }
    private static void resize(int frameWidth, int frameHeight) {
        releaseHeldSceneLock();
        finishGlWork();
        unregisterTexture();
        long[] sharedTexture = NativeDx12.resizeInterop(frameWidth, frameHeight);
        if (sharedTexture == null || sharedTexture.length != 2 || sharedTexture[0] == 0 || sharedTexture[1] == 0) {
            throw new IllegalStateException("DX12/OpenGL interop resize did not return a shared texture.");
        }
        registerTexture(sharedTexture[0], sharedTexture[1], frameWidth, frameHeight);
        width = frameWidth;
        height = frameHeight;
    }

    private static void registerTexture(long sharedColor, long sharedDepth, int frameWidth, int frameHeight) {
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            var color = InteropColorTarget.create(interopDevice, sharedColor, frameWidth, frameHeight,
                    ClientConfig.PREFER_DIRECT_COLOR_INTEROP.get());
            texture = color.texture;
            interopObject = color.object;
            if (color.fallbackReason != null) {
                Direct.LOGGER.warn("Native terrain color sharing rejected; using copied color: {}", color.fallbackReason);
            }
            depthTexture = GL11.glGenTextures();
            directBridge = false;
            if (color.direct && ClientConfig.DIRECT_BRIDGE.get()) {
                String rejection = registerDirectBridge(frameWidth, frameHeight);
                if (rejection != null) Direct.LOGGER.warn("Direct depth bridge rejected; using copied depth: {}", rejection);
            }
            if (!directBridge) depthInteropObject = registerResource(sharedDepth, depthTexture);
            Direct.LOGGER.info("DX12 terrain color interop: {}; depth uses {}.",
                    color.direct ? "shared native target (no color export copy)" : "copied target",
                    directBridge ? "the native R32F bridge (no D3D11 depth copies)" : "the copied bridge");
            interopAccess = WGLNVDXInterop.WGL_ACCESS_READ_WRITE_NV;
            fixedInteropAccess = false;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
            GL13.glActiveTexture(active);
        }
    }

    private static int interopAccess;
    private static boolean fixedInteropAccess;
    private static boolean directBridge;

    static boolean directBridge() { return directBridge; }

    /**
     * Registers the native R32F depth bridge and validates its storage, then selects it in the native renderer
     * (see {@code ClientConfig.DIRECT_BRIDGE}). Returns the rejection, or null on success; on rejection the GL
     * name is fresh and the native renderer keeps the copied depth path.
     */
    private static String registerDirectBridge(int frameWidth, int frameHeight) {
        long object = 0;
        try {
            long bridge = NativeDx12.acquireDepthBridgeTarget();
            if (bridge == 0) return "the native depth bridge is unavailable";
            object = registerResource(bridge, depthTexture);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var objects = stack.pointers(object);
                if (!WGLNVDXInterop.wglDXLockObjectsNV(interopDevice, objects)) throw new IllegalStateException("cannot lock the depth bridge");
                try {
                    int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                    int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                    int format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
                    if (w != frameWidth || h != frameHeight || format != GL30.GL_R32F)
                        throw new IllegalStateException("depth bridge storage is " + w + "x" + h + " format 0x" + Integer.toHexString(format));
                } finally {
                    if (!WGLNVDXInterop.wglDXUnlockObjectsNV(interopDevice, objects)) throw new IllegalStateException("cannot unlock the depth bridge");
                }
            }
            NativeDx12.configureDirectBridge(true);
            depthInteropObject = object;
            directBridge = true;
            return null;
        } catch (RuntimeException exception) {
            if (object != 0 && !WGLNVDXInterop.wglDXUnregisterObjectNV(interopDevice, object))
                throw new IllegalStateException("Cannot unregister the rejected depth bridge", exception);
            GL11.glDeleteTextures(depthTexture);
            depthTexture = GL11.glGenTextures();
            NativeDx12.configureDirectBridge(false);
            return exception.toString();
        }
    }

    // Capture overwrites both full-size textures; presentation only samples them. Tell WGL
    // before locking, so drivers with separate GL storage need only transfer in one direction.
    private static void sceneAccess(int access) {
        if (interopAccess == access || fixedInteropAccess) return;
        if (WGLNVDXInterop.wglDXObjectAccessNV(interopObject, access)
                && WGLNVDXInterop.wglDXObjectAccessNV(depthInteropObject, access)) {
            interopAccess = access;
            return;
        }
        if (!WGLNVDXInterop.wglDXObjectAccessNV(interopObject, WGLNVDXInterop.WGL_ACCESS_READ_WRITE_NV)
                || !WGLNVDXInterop.wglDXObjectAccessNV(depthInteropObject, WGLNVDXInterop.WGL_ACCESS_READ_WRITE_NV)) {
            throw new IllegalStateException("Cannot restore shared texture access");
        }
        fixedInteropAccess = true;
        Direct.LOGGER.info("Using read/write WGL locks for this render target");
    }

    /**
     * Held from the terrain composite until the world capture when a world pack ran last frame: one WGL
     * lock (a synchronous D3D/GL transfer, about 2 ms on Intel UHD 630) less per frame. D3D12 does not use
     * the images in between; every D3D12 submission and teardown path releases a held lock first.
     * {@link #heldSceneWritable} tells whether the held lock may be taken over by a capture that writes them.
     */
    private static boolean heldSceneLock;
    private static boolean heldSceneWritable;
    private static boolean worldRanThisFrame, worldRanLastFrame;

    /** Vanilla sky/clouds are skipped only while a world pack that draws them actually ran last frame. */
    public static boolean skipVanillaSky() { return worldRanLastFrame && initialized && !disabled && DirectRenderApi.worldReplacesSky(); }
    public static boolean skipVanillaClouds() { return worldRanLastFrame && initialized && !disabled && DirectRenderApi.worldReplacesClouds(); }

    private static void lockSceneForCapture(org.lwjgl.PointerBuffer objects) {
        if (heldSceneLock && heldSceneWritable) {
            heldSceneLock = false; // the capture's unlock now owns it
            heldSceneWritable = false;
            if (RendererMetrics.recording) RendererMetrics.worldHeldLocks++;
            return;
        }
        // A read-only hold (no capture was expected) cannot be written: return it and lock for writing.
        releaseHeldSceneLock();
        sceneAccess(WGLNVDXInterop.WGL_ACCESS_WRITE_DISCARD_NV);
        if (!WGLNVDXInterop.wglDXLockObjectsNV(interopDevice, objects)) throw new IllegalStateException("Cannot lock scene capture");
    }

    private static void releaseHeldSceneLock() {
        if (!heldSceneLock) return;
        heldSceneLock = false;
        heldSceneWritable = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!WGLNVDXInterop.wglDXUnlockObjectsNV(interopDevice, stack.pointers(interopObject, depthInteropObject)))
                throw new IllegalStateException("Cannot release the held scene lock");
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

    /**
     * Keeps the native atlas in sync with Minecraft's block atlas. A full transfer (activation,
     * resource reload) never blocks the render thread: it is read back behind a fence and BC7
     * encoded on a worker. Returns false while that transfer runs; the caller then keeps vanilla
     * terrain for the frame instead of drawing with a missing or stale atlas.
     */
    private static boolean uploadBlockAtlasIfNeeded(Minecraft minecraft) {
        int atlasTexture = minecraft.getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS).getId();
        if (atlasTexture != blockAtlasId) {
            blockAtlasId = atlasTexture;
            blockAtlasDirty = true;
        }
        int threads = ClientConfig.BC7_ENCODER_THREADS.get();
        if (blockAtlasDirty) {
            blockAtlasDirty = false;
            ATLAS_REGIONS.reset();
            if (!FULL_ATLAS.start(atlasTexture, ClientConfig.COMPRESS_BLOCK_ATLAS.get())) {
                blockAtlasDirty = true; // atlas has no size yet; retry next frame
                return false;
            }
        }
        if (FULL_ATLAS.active()) {
            if (!FULL_ATLAS.poll(threads)) return false;
            activationAtlasNanos = FULL_ATLAS.elapsedNanos();
            Direct.LOGGER.info("Direct block atlas transferred in {} ms ({} ms on the render thread).",
                    activationAtlasNanos / 1_000_000L, FULL_ATLAS.renderThreadNanos() / 1_000_000L);
            return true;
        }
        ATLAS_REGIONS.update(atlasTexture, FULL_ATLAS.baseWidth(), FULL_ATLAS.baseHeight(),
                ClientConfig.ANIMATED_ATLAS_REFRESH_MILLIS.get(), threads, ClientConfig.CPU_ATLAS_ANIMATION.get());
        return true;
    }

    private static void drawImportedTexture(boolean useGpuSemaphore, long frame) {
        long drawStart = System.nanoTime();
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
            // A world pack captures this same scene later in the frame: keep the lock instead of a second one.
            // Holding it in every frame was measured (2026-11-22): 5 ms less CPU wait, no FPS change (GPU-bound).
            boolean hold = worldRanLastFrame && writeDepth && !heldSceneLock;
            boolean writable = true;
            sceneAccess(hold ? WGLNVDXInterop.WGL_ACCESS_READ_WRITE_NV : WGLNVDXInterop.WGL_ACCESS_READ_ONLY_NV);
            long lockStart = System.nanoTime();
            if (!WGLNVDXInterop.wglDXLockObjectsNV(interopDevice, objects)) {
                throw new IllegalStateException("WGL_NV_DX_interop2 could not lock the shared texture.");
            }
            if (RendererMetrics.recording) RendererMetrics.compositeLockNanos += System.nanoTime() - lockStart;
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
                boolean unlocked = hold && drawn || WGLNVDXInterop.wglDXUnlockObjectsNV(interopDevice, objects);
                if (hold && drawn) { heldSceneLock = true; heldSceneWritable = writable; }
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
        long syncStart = System.nanoTime();
        // Includes the lock; the log subtracts it.
        if (RendererMetrics.recording) RendererMetrics.compositeDrawNanos += syncStart - drawStart;

        if (useGpuSemaphore) {
            if (!signalGlDoneFence(frame + 1)) {
                disableGpuSemaphore("OpenGL rejected the D3D12 timeline semaphore signal; using a polled GL sync.");
                queueCpuGlSync(frame + 1);
            }
        } else {
            queueCpuGlSync(frame + 1);
        }
        if (RendererMetrics.recording) RendererMetrics.compositeSyncNanos += System.nanoTime() - syncStart;
    }

    private static void queueCpuGlSync(long value) {
        pendingGlSince = System.nanoTime();
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
            signalCompletedGlSync();
            return true;
        }
        if (result == GL32.GL_WAIT_FAILED) {
            // finishGlWork deletes the failed GLsync before CPU-signaling the native timeline.
            finishGlWork();
            return true;
        }
        return false;
    }

    // Capture and the preceding composite are ordered in the same GL context. One finish
    // after WGL unlock covers both before native code reads the captured scene.
    private static void finishGlWork() {
        GL11.glFinish();
        if (pendingGlSync != 0) signalCompletedGlSync();
    }

    private static void signalCompletedGlSync() {
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
