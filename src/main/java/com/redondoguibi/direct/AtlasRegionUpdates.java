package com.redondoguibi.direct;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

/**
 * Keeps the DX12 block atlas in step with animated sprites. A changed sprite rectangle is grown, per mip level, to a
 * patch of whole BC7 blocks. In the OpenGL mode every changed patch is read back from the GL atlas. In the CPU mode a
 * patch is read back from OpenGL only once, as its baseline; later sprite uploads are copied into the cached patches
 * from the same CPU pixels vanilla passes to {@code glTexSubImage2D}, so a running animation needs no OpenGL readback.
 * A source that cannot be copied (closed, not RGBA8, out of bounds, or marked without pixels) falls back to a readback
 * of its patches. Compression always runs on private pixel copies off the render thread.
 */
final class AtlasRegionUpdates {
    /** CPU pixels of one sprite upload, per mip level: tightly packed RGBA8 rows of {@link #width(int)} texels. */
    interface SpriteSource {
        int levels();
        /** Base address of the level's pixels, or 0 when they cannot be copied (closed or not RGBA8). */
        long address(int level);
        int width(int level);
        int height(int level);
    }

    /** Destination of finished patches; the native renderer outside tests. */
    interface AtlasSink {
        boolean compressed();
        int mipLevels();
        void encode(ByteBuffer pixels, ByteBuffer encoded, int width, int height, int threads);
        boolean upload(ByteBuffer data, int level, int x, int y, int width, int height);

        AtlasSink NATIVE = new AtlasSink() {
            @Override public boolean compressed() { return NativeDx12.isAtlasCompressed(); }
            @Override public int mipLevels() { return NativeDx12.atlasMipLevels(); }
            @Override public void encode(ByteBuffer pixels, ByteBuffer encoded, int width, int height, int threads) {
                NativeDx12.encodeAtlasRegion(pixels, encoded, width, height, threads);
            }
            @Override public boolean upload(ByteBuffer data, int level, int x, int y, int width, int height) {
                return NativeDx12.updateAtlasRegion(data, level, x, y, width, height);
            }
        };
    }

    record Region(int x, int y, int width, int height) {}
    /** Latest upload at one atlas position; {@code source} is null for a plain mark without CPU pixels. */
    private record Upload(int x, int y, int frameX, int frameY, int width, int height, SpriteSource source) {}

    /** Identity matters: overlapping patches are distinct objects even when their pixels are equal. */
    private static final class Patch {
        final int level;
        final Region region;
        final ByteBuffer pixels;
        final ByteBuffer encoded;
        /** CPU mode: {@link #pixels} currently equal the OpenGL atlas under {@link #region}. */
        boolean baseline;

        Patch(int level, Region region, ByteBuffer pixels, ByteBuffer encoded) {
            this.level = level;
            this.region = region;
            this.pixels = pixels;
            this.encoded = encoded;
        }
    }

    private final java.util.concurrent.ExecutorService encoder = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Direct atlas encoder");
        thread.setDaemon(true);
        return thread;
    });
    private final AtlasSink sink;
    /** Guarded by itself: the atlas clear hook drops CPU sources before vanilla closes their images. */
    private final Map<Long, Upload> dirty = new LinkedHashMap<>();
    private final Map<String, Patch> buffers = new HashMap<>();
    private final List<List<Patch>> patchesByLevel = new ArrayList<>();
    private final Set<Patch> toEncode = new LinkedHashSet<>();
    private CompletableFuture<List<Patch>> pending;
    private long generation;
    private long pendingGeneration;
    private long lastCapture;
    private int framebuffer;
    private int readbackBuffer;
    private long readbackFence;
    private List<Patch> readbackPatches;
    private long readbackGeneration;
    private boolean readbackCompressed;
    private boolean readbackBaseline;
    private int mode = -1;
    private long glReadbacks;

    AtlasRegionUpdates() { this(AtlasSink.NATIVE); }

    AtlasRegionUpdates(AtlasSink sink) { this.sink = sink; }

    void reset() {
        if (readbackFence != 0) GL32.glDeleteSync(readbackFence);
        if (readbackBuffer != 0) GL15.glDeleteBuffers(readbackBuffer);
        readbackFence=0;readbackBuffer=0;readbackPatches=null;
        generation++;
        synchronized (dirty) { dirty.clear(); }
        buffers.clear();
        patchesByLevel.clear();
        toEncode.clear();
        lastCapture = 0;
        // A running native encoder owns its buffers until it finishes; never reuse them after reset.
    }

    void close() {
        reset();
        if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
        framebuffer = 0;
    }

    /** Marks a changed rectangle without CPU pixels; its patches are always read back from OpenGL. */
    void mark(int x, int y, int width, int height) {
        markUpload(x, y, 0, 0, width, height, null);
    }

    /**
     * Records a sprite upload: {@code width x height} texels from {@code (frameX, frameY)} of {@code source} were
     * written at {@code (x, y)}, each mip level shifted right by the level, as {@code SpriteContents.upload} does.
     */
    void markUpload(int x, int y, int frameX, int frameY, int width, int height, SpriteSource source) {
        synchronized (dirty) {
            dirty.put((long) x << 32 | Integer.toUnsignedLong(y), new Upload(x, y, frameX, frameY, width, height, source));
        }
    }

    /** Drops recorded CPU sources; called before vanilla closes the atlas sprite images. */
    void forgetSources() {
        synchronized (dirty) { dirty.clear(); }
    }

    boolean hasPendingWork() {
        synchronized (dirty) {
            return readbackFence != 0 || pending != null || !dirty.isEmpty() || !toEncode.isEmpty();
        }
    }

    /** Readback batches issued since creation; the CPU mode issues one per new patch set, not per animation frame. */
    long glReadbacks() { return glReadbacks; }

    /** OpenGL mode, used by the original interop test. */
    void update(int atlas, int intervalMillis, int threads) {
        update(atlas, 0, 0, intervalMillis, threads, false);
    }

    /**
     * @param atlasWidth  level-0 width of the atlas, used by the CPU mode instead of querying OpenGL
     * @param atlasHeight level-0 height of the atlas
     * @param cpuMirror   copy sprite uploads from their CPU pixels after a one-time baseline readback
     */
    void update(int atlas, int atlasWidth, int atlasHeight, int intervalMillis, int threads, boolean cpuMirror) {
        int nextMode = cpuMirror ? 1 : 0;
        if (mode != nextMode) {
            if (mode != -1) {
                // Cached patches carry the previous mode's state; start over without dropping queued uploads.
                Map<Long, Upload> queued;
                synchronized (dirty) { queued = new LinkedHashMap<>(dirty); }
                reset();
                synchronized (dirty) { dirty.putAll(queued); }
            }
            mode = nextMode;
        }
        if(readbackFence!=0){
            int status=GL32.glClientWaitSync(readbackFence,0,0);
            if(status==GL32.GL_TIMEOUT_EXPIRED)return;
            if(status==GL32.GL_WAIT_FAILED)throw new IllegalStateException("Atlas readback synchronization failed");
            int oldPack=GL11.glGetInteger(GL30.GL_PIXEL_PACK_BUFFER_BINDING);
            long mapStart=System.nanoTime();
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER,readbackBuffer);
            try{
                long bytes=0;for(Patch patch:readbackPatches)bytes+=(long)patch.region.width*patch.region.height*4;
                ByteBuffer mapped=GL30.glMapBufferRange(GL30.GL_PIXEL_PACK_BUFFER,0,bytes,GL30.GL_MAP_READ_BIT);
                if(mapped==null)throw new IllegalStateException("Cannot map completed atlas readback");
                try{for(Patch patch:readbackPatches){int size=patch.region.width*patch.region.height*4;ByteBuffer slice=mapped.slice();slice.limit(size);patch.pixels.clear();patch.pixels.put(slice);patch.pixels.flip();mapped.position(mapped.position()+size);}}
                finally{if(!GL15.glUnmapBuffer(GL30.GL_PIXEL_PACK_BUFFER))throw new IllegalStateException("Atlas readback became invalid");}
            }finally{RendererMetrics.atlasReadNanos+=System.nanoTime()-mapStart;GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER,oldPack);GL32.glDeleteSync(readbackFence);readbackFence=0;}
            List<Patch> ready=readbackPatches;readbackPatches=null;boolean compressed=readbackCompressed;
            if (readbackBaseline) {
                if (readbackGeneration == generation) {
                    // Baselines include every upload issued before the readback; queued uploads are copied over them
                    // again below (the same latest pixels), and the result is encoded together with other changes.
                    for (Patch patch : ready) patch.baseline = true;
                    toEncode.addAll(ready);
                    lastCapture = 0;
                }
            } else {
                encode(ready, compressed, readbackGeneration, threads);
            }
        }
        if (pending != null) {
            if (!pending.isDone()) return;
            var patches = pendingGeneration == generation ? pending.join() : List.<Patch>of();
            pending = null;
            if (pendingGeneration == generation) {
                for (int i = 0; i < patches.size(); i++) {
                    Patch patch = patches.get(i);
                    Region r = patch.region;
                    if (!sink.upload(patch.encoded, patch.level, r.x, r.y, r.width, r.height)) {
                        pending = CompletableFuture.completedFuture(patches.subList(i, patches.size()));
                        return;
                    }
                    RendererMetrics.atlasBytes += patch.encoded.remaining();
                }
            }
        }
        if (cpuMirror) updateFromCpu(atlas, atlasWidth, atlasHeight, intervalMillis, threads);
        else updateFromGl(atlas, intervalMillis);
    }

    private void encode(List<Patch> ready, boolean compressed, long readyGeneration, int threads) {
        pendingGeneration=readyGeneration;
        pending=CompletableFuture.supplyAsync(()->{for(Patch patch:ready)if(compressed)sink.encode(patch.pixels,patch.encoded,patch.region.width,patch.region.height,threads);return ready;},encoder);
    }

    private void updateFromCpu(int atlas, int atlasWidth, int atlasHeight, int intervalMillis, int threads) {
        long now = System.nanoTime();
        boolean compressed = false;
        synchronized (dirty) {
            if (toEncode.isEmpty() && (dirty.isEmpty() || now - lastCapture < intervalMillis * 1_000_000L)) return;
            lastCapture = now;
            try {
                compressed = sink.compressed();
                int levels = sink.mipLevels();
                Set<Patch> missing = new LinkedHashSet<>();
                for (Iterator<Upload> iterator = dirty.values().iterator(); iterator.hasNext(); ) {
                    Upload upload = iterator.next();
                    boolean copyable = copyable(upload, levels);
                    for (int level = 0; level < levels && writesLevel(upload, level); level++) {
                        int width = Math.max(1, atlasWidth >> level);
                        int height = Math.max(1, atlasHeight >> level);
                        Region region = alignedRegion(upload.x, upload.y, upload.width, upload.height, level, width, height, compressed);
                        if (region == null) continue;
                        Patch patch = patch(level, region, compressed);
                        if (!copyable) patch.baseline = false;
                        if (!patch.baseline) missing.add(patch);
                    }
                    // The readback issued below follows this upload in GL order, so it already carries its pixels.
                    if (!copyable) iterator.remove();
                }
                if (!missing.isEmpty()) {
                    toEncode.removeAll(missing);
                    readBack(atlas, new ArrayList<>(missing), compressed, true);
                    return;
                }
                for (Upload upload : dirty.values()) {
                    for (int level = 0; level < levels && writesLevel(upload, level); level++) {
                        SpriteSource source = upload.source;
                        int width = upload.width >> level;
                        int height = upload.height >> level;
                        // Neighbouring patches can cover the same texels at coarse mips; each one must stay current.
                        for (Patch patch : patchesByLevel.get(level)) {
                            if (overlay(source.address(level), source.width(level), source.height(level),
                                    upload.frameX >> level, upload.frameY >> level, width, height,
                                    upload.x >> level, upload.y >> level, patch.pixels, patch.region)) {
                                toEncode.add(patch);
                            }
                        }
                    }
                }
                dirty.clear();
            } finally {
                RendererMetrics.atlasReadNanos += System.nanoTime() - now;
            }
        }
        if (toEncode.isEmpty()) return;
        List<Patch> ready = new ArrayList<>(toEncode);
        toEncode.clear();
        encode(ready, compressed, generation, threads);
    }

    private void updateFromGl(int atlas, int intervalMillis) {
        long now = System.nanoTime();
        List<Upload> uploads;
        synchronized (dirty) {
            if (dirty.isEmpty() || now - lastCapture < intervalMillis * 1_000_000L) return;
            uploads = new ArrayList<>(dirty.values());
            dirty.clear();
        }
        lastCapture = now;
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        List<Patch> patches = new ArrayList<>();
        long started = System.nanoTime();
        boolean compressed;
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
            compressed = sink.compressed();
            int levels = sink.mipLevels();
            for (int level = 0; level < levels; level++) {
                int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_WIDTH);
                int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_HEIGHT);
                for (Upload source : uploads) {
                    Region r = alignedRegion(source.x, source.y, source.width, source.height, level, width, height, compressed);
                    if (r == null) continue;
                    Patch patch = patch(level, r, compressed);
                    if (patches.contains(patch)) continue;
                    patches.add(patch);
                }
            }
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
            RendererMetrics.atlasReadNanos += System.nanoTime() - started;
        }
        if (!patches.isEmpty()) readBack(atlas, patches, compressed, false);
    }

    /** The patch of whole compression blocks covering a sprite rectangle at {@code level}, or null if empty. */
    static Region alignedRegion(int x, int y, int width, int height, int level, int levelWidth, int levelHeight, boolean compressed) {
        int align = compressed ? 4 : 1;
        int left = (x >> level) / align * align;
        int top = (y >> level) / align * align;
        int right = Math.min(levelWidth, (((x + width + (1 << level) - 1) >> level) + align - 1) / align * align);
        int bottom = Math.min(levelHeight, (((y + height + (1 << level) - 1) >> level) + align - 1) / align * align);
        if (right <= left || bottom <= top) return null;
        return new Region(left, top, right - left, bottom - top);
    }

    /**
     * Copies the part of a {@code width x height} source rectangle written at {@code (atlasX, atlasY)} that falls
     * inside {@code region} into {@code patch} (RGBA8 rows of {@code region.width} texels). The source is RGBA8 rows of
     * {@code sourceWidth} texels, read from {@code (sourceX, sourceY)} as GL unpack skip pixels/rows do.
     *
     * @return whether any texel of the patch was written
     */
    static boolean overlay(long source, int sourceWidth, int sourceHeight, int sourceX, int sourceY, int width, int height,
                           int atlasX, int atlasY, ByteBuffer patch, Region region) {
        int left = Math.max(atlasX, region.x);
        int top = Math.max(atlasY, region.y);
        int right = Math.min(atlasX + width, region.x + region.width);
        int bottom = Math.min(atlasY + height, region.y + region.height);
        if (right <= left || bottom <= top) return false;
        if (source == 0 || sourceX < 0 || sourceY < 0 || sourceX + width > sourceWidth || sourceY + height > sourceHeight)
            throw new IllegalArgumentException("Sprite source rectangle lies outside its image");
        if (patch.capacity() < region.width * region.height * 4)
            throw new IllegalArgumentException("Atlas patch buffer is smaller than its region");
        long destination = MemoryUtil.memAddress0(patch);
        long rowBytes = (right - left) * 4L;
        for (int row = top; row < bottom; row++) {
            long from = source + ((long) (sourceY + row - atlasY) * sourceWidth + (sourceX + left - atlasX)) * 4L;
            long to = destination + ((long) (row - region.y) * region.width + (left - region.x)) * 4L;
            MemoryUtil.memCopy(from, to, rowBytes);
        }
        return true;
    }

    /** Whether vanilla's upload loop writes {@code level}: it stops at the first level where the sprite is empty. */
    private static boolean writesLevel(Upload upload, int level) {
        if (upload.source == null) return true;
        return level < upload.source.levels() && (upload.width >> level) > 0 && (upload.height >> level) > 0;
    }

    private static boolean copyable(Upload upload, int levels) {
        SpriteSource source = upload.source;
        if (source == null) return false;
        for (int level = 0; level < levels && writesLevel(upload, level); level++) {
            int x = upload.frameX >> level;
            int y = upload.frameY >> level;
            if (source.address(level) == 0 || x < 0 || y < 0
                    || x + (upload.width >> level) > source.width(level)
                    || y + (upload.height >> level) > source.height(level)) return false;
        }
        return true;
    }

    private Patch patch(int level, Region region, boolean compressed) {
        String key = level + ":" + region;
        Patch patch = buffers.get(key);
        if (patch == null) {
            int bytes = compressed ? ((region.width + 3) / 4) * ((region.height + 3) / 4) * 16 : region.width * region.height * 4;
            ByteBuffer pixels = ByteBuffer.allocateDirect(region.width * region.height * 4);
            patch = new Patch(level, region, pixels, compressed ? ByteBuffer.allocateDirect(bytes) : pixels);
            buffers.put(key, patch);
            while (patchesByLevel.size() <= level) patchesByLevel.add(new ArrayList<>());
            patchesByLevel.get(level).add(patch);
        }
        return patch;
    }

    /** Queues an asynchronous OpenGL readback of {@code patches} into a pixel-pack buffer behind a fence. */
    private void readBack(int atlas, List<Patch> patches, boolean compressed, boolean baseline) {
        int oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldPack = GL11.glGetInteger(GL30.GL_PIXEL_PACK_BUFFER_BINDING);
        int oldAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int oldRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int oldSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int oldSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        long started = System.nanoTime();
        try {
            if (framebuffer == 0) framebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            patches.sort(java.util.Comparator.comparingInt(patch -> patch.level));
            long bytes=0;for(Patch patch:patches)bytes+=(long)patch.region.width*patch.region.height*4;
            if(readbackBuffer==0)readbackBuffer=GL15.glGenBuffers();
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER,readbackBuffer);
            GL15.glBufferData(GL30.GL_PIXEL_PACK_BUFFER,bytes,GL15.GL_STREAM_READ);
            int previousLevel=-1;long offset=0;
            for(Patch patch:patches){
                if(previousLevel!=patch.level){
                    GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,atlas,patch.level);
                    GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
                    if (GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
                        throw new IllegalStateException("Atlas mip cannot be read as a framebuffer.");
                    previousLevel=patch.level;
                }
                Region r=patch.region;GL11.glReadPixels(r.x,r.y,r.width,r.height,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,offset);
                offset+=(long)r.width*r.height*4;
            }
            readbackFence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
            if(readbackFence==0)throw new IllegalStateException("Could not create atlas readback fence");
            GL11.glFlush();readbackPatches=patches;readbackGeneration=generation;readbackCompressed=compressed;readbackBaseline=baseline;
            glReadbacks++;
            RendererMetrics.atlasGlReads++;
        } finally {
            RendererMetrics.atlasReadNanos += System.nanoTime() - started;
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, oldAlignment);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, oldRowLength);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, oldSkipRows);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, oldSkipPixels);
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, oldPack);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldRead);
        }
    }
}
