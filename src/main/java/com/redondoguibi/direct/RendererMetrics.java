package com.redondoguibi.direct;

/** Opt-in CPU/GPU costs and bounded frame-time samples; no per-frame disk IO. */
public final class RendererMetrics {
    static long gpuNanos;
    static long nativeDraws;
    static long nativeFrames;
    static long vanillaDraws;
    static long atlasBytes;
    static long atlasReadNanos;
    /** OpenGL readback batches of animated atlas patches; the CPU mode issues them only for new patches. */
    static long atlasGlReads;
    static long submitNanos;
    static long compositeNanos;
    static long dxWaitNanos,glWaitNanos,uploadNanos,uploadBytes,uploadSections;
    static long gpuArenaBytes,uploadRingBytes;
    static long discardedBeforeWait;
    static long ownershipFrames,ownedSectionSamples,fallbackSectionSamples,mixedFrames;
    static long translucentCaptureNanos,translucentSubmitNanos,translucentWaitNanos,translucentPresentNanos,translucentFrames;
    static long translucentGpuNanos,translucentDraws;
    static long faceVisibleIndices,faceSubmittedIndices;
    static long worldFrames, worldCaptureNanos, worldRenderNanos, worldPresentNanos;
    static long worldGpuFrames, worldGpuImportNanos, worldGpuPassNanos, worldHeldLocks;
    /** Terrain composite split: WGL lock, GL state and draw, GL sync/flush, timestamp readback. */
    static long compositeLockNanos, compositeDrawNanos, compositeSyncNanos, compositeMetricsNanos;
    /** Wall time inside LevelRenderer.renderLevel; the rest of the frame is hand, GUI, swap and ticks. */
    static long levelNanos;
    private static long levelStart;
    private static final FrameTimeWindow frameTimes=new FrameTimeWindow(16384);
    private static long previousFrame;
    private static int mode=-1;
    private static boolean reportedWriteFailure;
    static volatile boolean recording;
    private static long windowStart;
    private static int frameCount;
    private static int skippedLayers;
    private static int vanillaLayers;

    private RendererMetrics() {}

    public static void frame() {
        boolean wasRecording=recording;
        recording = ClientConfig.PROFILE_RENDERER.get();
        if (!recording) { reset();previousFrame=0;return; }
        if(!wasRecording){reset();previousFrame=0;}
        long now = System.nanoTime();
        int nextMode=!ClientConfig.ENABLE_DX12.get()?0:ClientConfig.REPLACE_VANILLA_TERRAIN.get()?1:2;
        if(nextMode!=mode){reset();previousFrame=0;mode=nextMode;}
        if(previousFrame!=0)frameTimes.add(now-previousFrame);
        previousFrame=now;frameCount++;
        levelStart=now;
        if (windowStart == 0) windowStart = now;
        if (now - windowStart < 60_000_000_000L) return;
        Direct.LOGGER.info("Direct costs: mode={}, submitMsPerFrame={}, compositeMsPerFrame={}, "
                        + "atlasReadMs={}, atlasBytes={}, vanillaBaseLayers={}, skippedBaseLayers={}, "
                        + "vanillaDraws={}, gpuTerrainAndCopyMs={}, nativeDrawsPerFrame={}, atlasGlReads={}",
                !ClientConfig.ENABLE_DX12.get() ? "vanilla" : ClientConfig.REPLACE_VANILLA_TERRAIN.get() ? "replacement" : "overlay-debug",
                submitNanos / 1e6 / frameCount, compositeNanos / 1e6 / frameCount, atlasReadNanos / 1e6, atlasBytes,
                vanillaLayers, skippedLayers, vanillaDraws, gpuNanos / 1e6 / Math.max(1, nativeFrames),
                (double) nativeDraws / Math.max(1, nativeFrames), atlasGlReads);
        var times=frameTimes.summary();
        String label=mode==0?"vanilla":mode==1?"replacement":"overlay-debug";
        Direct.LOGGER.info("Direct frame times: mode={}, frames={}, samples={}, fps={}, meanMs={}, p50Ms={}, p95Ms={}, p99Ms={}, maxMs={}, dxWaitMsPerFrame={}, glWaitMsPerFrame={}, chunkUploadMs={}, chunkUploadBytes={}, chunkUploads={}",
                label,times.frames(),times.samples(),times.fps(),times.meanMs(),times.p50Ms(),times.p95Ms(),times.p99Ms(),times.maxMs(),dxWaitNanos/1e6/frameCount,glWaitNanos/1e6/frameCount,uploadNanos/1e6,uploadBytes,uploadSections);
        writeCsv(label,times);
        if (worldFrames > 0) {
            double nsPerMs = 1e6 * worldFrames;
            long gpuFrames = Math.max(1, worldGpuFrames);
            Direct.LOGGER.info("Direct world post: frames={}, captureMsPerPassChain={}, submitAndWaitMsPerChain={}, presentMsPerChain={}, gpuImportMs={}, gpuPassesMs={}, heldCompositeLocks={}, depthBridge={}",
                    worldFrames, worldCaptureNanos / nsPerMs, worldRenderNanos / nsPerMs, worldPresentNanos / nsPerMs,
                    worldGpuImportNanos / 1e6 / gpuFrames, worldGpuPassNanos / 1e6 / gpuFrames, worldHeldLocks, Dx12GlInterop.directBridge());
        }
        Direct.LOGGER.info("Direct ownership: selections={}, mixedFrames={}, nativeSectionsPerSelection={}, vanillaSectionsPerSelection={}, arenaRejectionsTotal={}",
                ownershipFrames,mixedFrames,(double)ownedSectionSamples/Math.max(1,ownershipFrames),(double)fallbackSectionSamples/Math.max(1,ownershipFrames),TerrainMeshStore.arenaRejections());
        Direct.LOGGER.info("Direct translucency: frames={}, captureMsPerFrame={}, sortSubmitMsPerFrame={}, waitMsPerFrame={}, presentMsPerFrame={}, gpuMsPerNativeFrame={}, drawsPerNativeFrame={}",
                translucentFrames,translucentCaptureNanos/1e6/frameCount,translucentSubmitNanos/1e6/frameCount,
                translucentWaitNanos/1e6/frameCount,translucentPresentNanos/1e6/frameCount,
                translucentGpuNanos/1e6/Math.max(1,translucentFrames),(double)translucentDraws/Math.max(1,translucentFrames));
        Direct.LOGGER.info("Direct invalid frames discarded before GPU wait: {}",discardedBeforeWait);
        Direct.LOGGER.info("Direct frame split: levelMsPerFrame={}, outsideLevelMsPerFrame={}, compositeLockMs={}, compositeDrawMs={}, compositeSyncMs={}, compositeMetricsMs={}",
                levelNanos/1e6/frameCount, Math.max(0, times.meanMs() - levelNanos/1e6/frameCount),
                compositeLockNanos/1e6/frameCount, (compositeDrawNanos-compositeLockNanos)/1e6/frameCount, compositeSyncNanos/1e6/frameCount, compositeMetricsNanos/1e6/frameCount);
        // Index counts, not GPU time: vertex work avoided before the rasterizer would cull it.
        Direct.LOGGER.info("Direct face culling: enabled={}, submittedOpaqueCutoutIndexFraction={}, visibleIndicesPerNativeFrame={}",
                ClientConfig.FACE_CULLING.get(),faceVisibleIndices==0?1.0:(double)faceSubmittedIndices/faceVisibleIndices,
                (double)faceVisibleIndices/Math.max(1,nativeFrames));
        reset();
        windowStart = now;
    }

    private static void reset() {
        frameCount = skippedLayers = vanillaLayers = 0;
        gpuNanos = nativeDraws = nativeFrames = vanillaDraws = 0;
        atlasBytes = atlasReadNanos = atlasGlReads = submitNanos = compositeNanos = 0;
        dxWaitNanos=glWaitNanos=uploadNanos=uploadBytes=uploadSections=0;
        gpuArenaBytes=uploadRingBytes=0;
        discardedBeforeWait=0;
        ownershipFrames=ownedSectionSamples=fallbackSectionSamples=mixedFrames=0;
        translucentCaptureNanos=translucentSubmitNanos=translucentWaitNanos=translucentPresentNanos=translucentFrames=0;
        translucentGpuNanos=translucentDraws=0;
        faceVisibleIndices=faceSubmittedIndices=0;
        worldFrames = worldCaptureNanos = worldRenderNanos = worldPresentNanos = 0;
        worldGpuFrames = worldGpuImportNanos = worldGpuPassNanos = worldHeldLocks = 0;
        compositeLockNanos = compositeDrawNanos = compositeSyncNanos = compositeMetricsNanos = levelNanos = 0;
        frameTimes.clear();
        windowStart = 0;
    }
    private static void writeCsv(String label,FrameTimeWindow.Summary s){
        try{
            var mc=net.minecraft.client.Minecraft.getInstance();
            var directory=mc.gameDirectory.toPath().resolve("logs");java.nio.file.Files.createDirectories(directory);
            var file=directory.resolve("direct-performance.csv");
            if(!java.nio.file.Files.exists(file))java.nio.file.Files.writeString(file,"utc,mode,width,height,renderDistance,vsync,frames,percentileSamples,fps,meanMs,p50Ms,p95Ms,p99Ms,maxMs,submitMs,compositeMs,dxWaitMs,glWaitMs,uploadMs,uploadBytes,uploadSections,nativeFrames,skippedLayers,vanillaLayers,gpuMs,gpuArenaBytes,uploadRingBytes,pendingBytes,pendingSections,activeCaptures\n",java.nio.file.StandardOpenOption.CREATE_NEW);
            long[] pending=TerrainMeshStore.pendingMetrics();
            String row=String.format(java.util.Locale.ROOT,"%s,%s,%d,%d,%d,%s,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%d,%.3f,%d,%d,%d,%d,%d%n",
                    java.time.Instant.now(),label,mc.getMainRenderTarget().width,mc.getMainRenderTarget().height,mc.options.getEffectiveRenderDistance(),mc.options.enableVsync().get(),s.frames(),s.samples(),s.fps(),s.meanMs(),s.p50Ms(),s.p95Ms(),s.p99Ms(),s.maxMs(),submitNanos/1e6/frameCount,compositeNanos/1e6/frameCount,dxWaitNanos/1e6/frameCount,glWaitNanos/1e6/frameCount,uploadNanos/1e6,uploadBytes,uploadSections,nativeFrames,skippedLayers,vanillaLayers,gpuNanos/1e6/Math.max(1,nativeFrames),gpuArenaBytes,uploadRingBytes,pending[0],pending[1],pending[2]);
            java.nio.file.Files.writeString(file,row,java.nio.file.StandardOpenOption.APPEND);
            var translucentFile=directory.resolve("direct-translucency-performance.csv");
            if(!java.nio.file.Files.exists(translucentFile))java.nio.file.Files.writeString(translucentFile,
                    "utc,mode,frames,nativeTranslucentFrames,captureMs,sortSubmitMs,waitMs,presentMs,gpuMsPerNativeFrame,drawsPerNativeFrame\n",java.nio.file.StandardOpenOption.CREATE_NEW);
            String translucentRow=String.format(java.util.Locale.ROOT,"%s,%s,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f%n",
                    java.time.Instant.now(),label,frameCount,translucentFrames,translucentCaptureNanos/1e6/frameCount,translucentSubmitNanos/1e6/frameCount,
                    translucentWaitNanos/1e6/frameCount,translucentPresentNanos/1e6/frameCount,translucentGpuNanos/1e6/Math.max(1,translucentFrames),(double)translucentDraws/Math.max(1,translucentFrames));
            java.nio.file.Files.writeString(translucentFile,translucentRow,java.nio.file.StandardOpenOption.APPEND);
        }catch(java.io.IOException ex){if(!reportedWriteFailure){Direct.LOGGER.warn("Could not write Direct performance CSV",ex);reportedWriteFailure=true;}}
    }

    public static void levelEnd() {
        if (recording && levelStart != 0) levelNanos += System.nanoTime() - levelStart;
    }

    public static void vanillaDraw() {
        if (recording) vanillaDraws++;
    }

    public static void layer(boolean skipped) {
        if (!recording) return;
        if (skipped) skippedLayers++; else vanillaLayers++;
    }
}
