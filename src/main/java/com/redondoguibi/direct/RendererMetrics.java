package com.redondoguibi.direct;

/** Optional renderer-cost counters. Does not collect FPS or frame-time samples. */
public final class RendererMetrics {
    static long gpuNanos;
    static long nativeDraws;
    static long nativeFrames;
    static long vanillaDraws;
    static long atlasBytes;
    static long atlasReadNanos;
    static long submitNanos;
    static long compositeNanos;
    private static boolean recording;
    private static long windowStart;
    private static int frameCount;
    private static int skippedLayers;
    private static int vanillaLayers;

    private RendererMetrics() {}

    public static void frame() {
        recording = ClientConfig.PROFILE_RENDERER.get();
        if (!recording) { reset(); return; }
        frameCount++;
        long now = System.nanoTime();
        if (windowStart == 0) windowStart = now;
        if (now - windowStart < 60_000_000_000L) return;
        Direct.LOGGER.info("Direct costs: mode={}, submitMsPerFrame={}, compositeMsPerFrame={}, "
                        + "atlasReadMs={}, atlasBytes={}, vanillaBaseLayers={}, skippedBaseLayers={}, "
                        + "vanillaDraws={}, gpuTerrainAndCopyMs={}, nativeDrawsPerFrame={}",
                !ClientConfig.ENABLE_DX12.get() ? "vanilla" : ClientConfig.REPLACE_VANILLA_TERRAIN.get() ? "replacement" : "overlay-debug",
                submitNanos / 1e6 / frameCount, compositeNanos / 1e6 / frameCount, atlasReadNanos / 1e6, atlasBytes,
                vanillaLayers, skippedLayers, vanillaDraws, gpuNanos / 1e6 / Math.max(1, nativeFrames),
                (double) nativeDraws / Math.max(1, nativeFrames));
        reset();
        windowStart = now;
    }

    private static void reset() {
        frameCount = skippedLayers = vanillaLayers = 0;
        gpuNanos = nativeDraws = nativeFrames = vanillaDraws = 0;
        atlasBytes = atlasReadNanos = submitNanos = compositeNanos = 0;
        windowStart = 0;
    }

    public static void vanillaDraw() {
        if (recording) vanillaDraws++;
    }

    public static void layer(boolean skipped) {
        if (!recording) return;
        if (skipped) skippedLayers++; else vanillaLayers++;
    }
}
