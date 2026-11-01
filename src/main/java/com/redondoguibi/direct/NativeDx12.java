package com.redondoguibi.direct;

import java.nio.ByteBuffer;

final class NativeDx12 {
    private NativeDx12() {
    }

    static native void initialize(long hwnd, int width, int height, String[] shaders);

    static native void resize(int width, int height);

    static native void render(float timeSeconds, int width, int height, float[][] uniforms, ByteBuffer rgba);

    static native void shutdown();

    static native long[] initializeInterop(int width, int height);

    static native boolean renderInterop(float[] viewProjection, float cameraX, float cameraY, float cameraZ,
            float renderDistance, long frameIndex, boolean useGpuSynchronization);

    static native void waitInteropFence(long value);

    static native void signalInteropFence(long value);

    static native boolean hasInteropGpuSyncFault();

    static native void updateTerrainSection(long key, int sectionX, int sectionY, int sectionZ,
            ByteBuffer vertices, int vertexCount, ByteBuffer indices, int indexCount);

    static native void updateTerrainAtlas(ByteBuffer pixels, int width, int height);

    static native void removeTerrainSection(long key);

    static native void clearTerrain();

    static native void shutdownInterop();
}
