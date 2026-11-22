package com.redondoguibi.direct;

import java.nio.ByteBuffer;

final class NativeDx12 {
    static final int FRAME_RENDERED_CPU_SYNC = 0;
    static final int FRAME_RENDERED_GPU_SYNC = 1;
    static final int FRAME_SKIPPED_BUSY = 2;
    static final int FRAME_SKIPPED_BUDGET = 3;

    private NativeDx12() {
    }

    static long[] initializeInterop(int width, int height, boolean parallelSectionRecording,
            int uploadRingBufferMiB, int terrainArenaPageMiB, int maxTerrainArenaMiB) {
        return initializeInteropCached(width, height, parallelSectionRecording, uploadRingBufferMiB,
                terrainArenaPageMiB, maxTerrainArenaMiB, null);
    }

    /**
     * @param cacheDirectory directory for compiled shader bytecode and the serialized PSO library;
     *        null disables the disk cache. I/O failures never fail initialization.
     */
    static native long[] initializeInteropCached(int width, int height, boolean parallelSectionRecording,
            int uploadRingBufferMiB, int terrainArenaPageMiB, int maxTerrainArenaMiB, String cacheDirectory);

    /** Native statistics of the last initialization: {shader cache hits, shaders compiled, PSO library loaded (0/1)}. */
    static native int[] initializationCacheStats();

    static native long[] resizeInterop(int width, int height);

    static native void configurePackGeometry(String source);
    static native void setPackGeometryOptions(float[] values, boolean sceneInputs, boolean shadows);

    static native void configureRenderPasses(String[] pixelShaderSources);
    static native void configureWorldPasses(String[] sources);
    static native int renderWorldInterop(float[] uniforms,long frameIndex);
    static native int renderWorldForBackend(float[] uniforms, long frameIndex);
    static native boolean isWorldFrameReady();
    /** {scene import ms, passes ms} of the latest completed profiled world frame, consumed once; null if none. */
    static native double[] worldFrameMetrics();

    static native int renderInterop(float[] viewProjection, double cameraX, double cameraY, double cameraZ,
            float renderDistance, long frameIndex, float elapsedSeconds, float[] passUniforms,
            boolean useGpuSynchronization, boolean useExecuteIndirect);

    static native void setProfilingEnabled(boolean enabled);

    /** Skips opaque/cutout face groups that are entirely back-facing (on by default). */
    static native void setFaceCullingEnabled(boolean enabled);

    /** {visible opaque/cutout indices, submitted indices} of the last visible-section selection. */
    static native long[] faceCullingStats();

    static native double[] frameMetrics();

    static native boolean isAtlasCompressed();

    static native int atlasMipLevels();

    static native void encodeAtlasRegion(ByteBuffer input, ByteBuffer output, int width, int height, int threads);

    static native boolean updateAtlasRegion(ByteBuffer pixels, int level, int x, int y, int width, int height);

    static native void updateLightmap(ByteBuffer pixels);

    static native void setVisibleSections(long[] keys, int count, boolean exactCoverage);
    static native void setShadowSections(long[] keys);

    static native boolean waitInteropFrame(long value);

    static native String interopAdapterName();

    /** Diagnostic only: shared RGBA8 texture cleared by DX12, never selected by the renderer. */
    static native long createDirectMemoryProbe();
    static native long acquireColorInteropTarget();
    static native void configureColorAlias(boolean enabled);
    /** Wrapped R32F depth bridge acquired for WGL registration; 0 when the bridge is unavailable. */
    static native long acquireDepthBridgeTarget();
    /** Direct bridge on/off; requires the aliased color target and a registered depth bridge. */
    static native void configureDirectBridge(boolean enabled);

    static native int interopFeatureFlags();

    static native boolean isCurrentFrameSlotReady();

    static native int pollInteropFence(long value);

    static native void signalInteropFence(long value);

    static native boolean hasInteropGpuSyncFault();

    static void updateTerrainSection(long key,int sectionX,int sectionY,int sectionZ,ByteBuffer vertices,int vertexCount,ByteBuffer indices,int[] counts){
        if(vertices.remaining()==(long)vertexCount*16){
            ByteBuffer extended=ByteBuffer.allocateDirect(vertexCount*20).order(java.nio.ByteOrder.nativeOrder());
            var input=vertices.duplicate();for(int i=0;i<vertexCount;i++){var v=input.slice();v.limit(16);extended.put(v);input.position(input.position()+16);extended.putInt(0x00007f00);}extended.flip();vertices=extended;
        }
        if(counts.length==3)counts=new int[]{counts[0],counts[1],counts[2],0};
        updateTerrainSectionV2(key,sectionX,sectionY,sectionZ,vertices.slice(),vertexCount,indices.slice(),counts);
    }
    private static native void updateTerrainSectionV2(long key,int sectionX,int sectionY,int sectionZ,ByteBuffer vertices,int vertexCount,ByteBuffer indices,int[] counts);
    /** Native TerrainBufferPacker.packVertices20 at absolute buffer offsets; thread-safe, no renderer needed. */
    static native void packTerrainVertices20(ByteBuffer destination, int destinationOffset, ByteBuffer source, int sourceOffset,
            int count, int stride, int position, int color, int uv, int light, int normal, byte[] materials);
    static native void setTerrainEffects(float[] effects);
    static native void setMaterialEffects(float[] effects);
    static native int renderTranslucentInterop(float[] matrix,double x,double y,double z,long frameIndex);
    static native int translucentFrameStatus();


    static void updateTerrainAtlas(ByteBuffer[] mipLevels, int width, int height, boolean compressBc7,
            int bc7EncoderThreads) {
        updateTerrainAtlas(mipLevels, width, height, compressBc7, bc7EncoderThreads, false);
    }

    /** With {@code preEncoded}, {@code mipLevels} already hold BC7 blocks (see {@link #encodeAtlasRegion}). */
    static void updateTerrainAtlas(ByteBuffer[] mipLevels, int width, int height, boolean compressBc7,
            int bc7EncoderThreads, boolean preEncoded) {
        updateTerrainAtlasV2(mipLevels, width, height, compressBc7, bc7EncoderThreads, preEncoded);
    }

    private static native void updateTerrainAtlasV2(ByteBuffer[] mipLevels, int width, int height, boolean compressBc7,
            int bc7EncoderThreads, boolean preEncoded);

    static native void removeTerrainSection(long key);

    /** True once if an upload since the previous call did not fit the terrain arena (that section stays vanilla). */
    static native boolean consumeArenaRejection();
    static native long largestTerrainAllocation();

    static native void clearTerrain();

    static native void shutdownInterop();
}
