package com.redondoguibi.direct;

import java.nio.ByteBuffer;

final class NativeDx12 {
    private NativeDx12() {
    }

    static native long[] initializeInterop(int width, int height);

    static native long resizeInterop(int width, int height);

    static native void configureRenderPasses(String[] pixelShaderSources);

    static native boolean renderInterop(float[] viewProjection, double cameraX, double cameraY, double cameraZ,
            float renderDistance, long frameIndex, float elapsedSeconds, float[] passUniforms,
            boolean useGpuSynchronization);

    static native String interopAdapterName();

    static native int interopFeatureFlags();

    static native void waitInteropFence(long value);

    static native void signalInteropFence(long value);

    static native boolean hasInteropGpuSyncFault();

    static native void updateTerrainSection(long key, int sectionX, int sectionY, int sectionZ,
            ByteBuffer vertices, int vertexCount, ByteBuffer indices, int[] layerIndexCounts);

    static native void updateTerrainAtlas(ByteBuffer[] mipLevels, int width, int height, boolean compressBc7);

    static native void removeTerrainSection(long key);

    static native void clearTerrain();

    static native void shutdownInterop();
}
