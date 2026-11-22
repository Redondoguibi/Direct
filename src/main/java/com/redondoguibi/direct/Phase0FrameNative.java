package com.redondoguibi.direct;

/** Opt-in, standalone D3D12 replay of a captured terrain and world submission. */
final class Phase0FrameNative {
    private Phase0FrameNative() {}

    static native void initialize(int width, int height, int uploadMiB, int pageMiB, int arenaMiB);

    /**
     * One completed GPU sample in milliseconds (whole, terrain, world pass, simple GUI),
     * followed by visible sections, submitted terrain indices and terrain draw ranges.
     */
    static native double[] frame(float[] terrain28, double x, double y, double z, float renderDistance,
            float[] worldUniforms, boolean indirect);
    /** Untimed diagnostic for the most recent frame, sampled once outside benchmark windows. */
    static native long coveredDepthPixels();
}
