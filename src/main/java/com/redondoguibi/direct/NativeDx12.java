package com.redondoguibi.direct;

import java.nio.ByteBuffer;

final class NativeDx12 {
    static final int FRAME_RENDERED_CPU_SYNC = 0;
    static final int FRAME_RENDERED_GPU_SYNC = 1;
    static final int FRAME_SKIPPED_BUSY = 2;
    static final int FRAME_SKIPPED_BUDGET = 3;

    private NativeDx12() {
    }

    static native long[] initializeInterop(int width, int height, boolean parallelSectionRecording,
            int uploadRingBufferMiB, int terrainArenaPageMiB, int maxTerrainArenaMiB);

    static native long[] resizeInterop(int width, int height);

    static native void configureRenderPasses(String[] pixelShaderSources);

    static native int renderInterop(float[] viewProjection, double cameraX, double cameraY, double cameraZ,
            float renderDistance, long frameIndex, float elapsedSeconds, float[] passUniforms,
            boolean useGpuSynchronization, boolean useExecuteIndirect);

    static native void setProfilingEnabled(boolean enabled);

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
    static native void setTerrainEffects(float[] effects);
    static native int renderTranslucentInterop(float[] matrix,double x,double y,double z,long frameIndex);
    static native int translucentFrameStatus();


    static native void updateTerrainAtlas(ByteBuffer[] mipLevels, int width, int height, boolean compressBc7,
            int bc7EncoderThreads);

    static native void removeTerrainSection(long key);

    static native void clearTerrain();

    static native void shutdownInterop();
}
