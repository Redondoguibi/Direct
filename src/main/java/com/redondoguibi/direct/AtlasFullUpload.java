package com.redondoguibi.direct;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

/**
 * Whole block-atlas transfer without stalling the render thread: the mip chain is read into a
 * pixel-pack buffer behind a fence, then BC7-encoded on a private worker. Until {@link #poll}
 * reports completion, callers keep vanilla terrain instead of waiting.
 */
final class AtlasFullUpload {
    private static final int MAX_LEVELS = 16;
    private final ExecutorService encoder = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Direct atlas full encoder");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private long generation;
    private boolean compress;
    private int baseWidth;
    private int baseHeight;
    private int[] levelWidths = new int[0];
    private int[] levelHeights = new int[0];
    private int readbackBuffer;
    private long readbackFence;
    private CompletableFuture<ByteBuffer[]> encoding;
    private long encodingGeneration;
    private long startedNanos;
    private long renderThreadNanos;

    boolean active() { return readbackFence != 0 || encoding != null; }
    long renderThreadNanos() { return renderThreadNanos; }
    long elapsedNanos() { return System.nanoTime() - startedNanos; }
    /** Level-0 size of the atlas from the last {@link #start}; later region updates use it instead of querying OpenGL. */
    int baseWidth() { return baseWidth; }
    int baseHeight() { return baseHeight; }

    /** Drops any transfer in flight; a running encoder finishes into buffers nobody reads. */
    void reset() {
        generation++;
        if (readbackFence != 0) GL32.glDeleteSync(readbackFence);
        readbackFence = 0;
        encoding = null;
    }

    void close() {
        reset();
        if (readbackBuffer != 0) GL15.glDeleteBuffers(readbackBuffer);
        readbackBuffer = 0;
    }

    /**
     * Queues an asynchronous readback of every mip level of {@code texture}.
     * Returns false when the atlas has no usable size yet.
     */
    boolean start(int texture, boolean compressBc7) {
        reset();
        startedNanos = System.nanoTime();
        renderThreadNanos = 0;
        long started = System.nanoTime();
        int previousActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousPack = GL11.glGetInteger(GL30.GL_PIXEL_PACK_BUFFER_BINDING);
        int previousAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (width <= 0 || height <= 0) return false;
            int maxLevel = Math.min(GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL), MAX_LEVELS - 1);
            int[] widths = new int[maxLevel + 1];
            int[] heights = new int[maxLevel + 1];
            int levels = 0;
            long total = 0;
            for (int level = 0; level <= maxLevel; level++) {
                int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_WIDTH);
                int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_HEIGHT);
                if (w != Math.max(1, width >> level) || h != Math.max(1, height >> level)) break;
                widths[levels] = w;
                heights[levels++] = h;
                total += (long) w * h * 4;
            }
            if (total > Integer.MAX_VALUE) {
                throw new IllegalStateException("Minecraft block atlas exceeds the supported DX12 upload size.");
            }
            levelWidths = java.util.Arrays.copyOf(widths, levels);
            levelHeights = java.util.Arrays.copyOf(heights, levels);
            baseWidth = width;
            baseHeight = height;
            // Must match the native BC7 precondition; otherwise the atlas stays RGBA8.
            compress = compressBc7 && width % 4 == 0 && height % 4 == 0;
            if (readbackBuffer == 0) readbackBuffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, readbackBuffer);
            GL15.glBufferData(GL30.GL_PIXEL_PACK_BUFFER, total, GL15.GL_STREAM_READ);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            long offset = 0;
            for (int level = 0; level < levels; level++) {
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, level, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, offset);
                offset += (long) levelWidths[level] * levelHeights[level] * 4;
            }
            readbackFence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (readbackFence == 0) throw new IllegalStateException("Could not create the atlas readback fence.");
            GL11.glFlush();
            return true;
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, previousAlignment);
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, previousPack);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL13.glActiveTexture(previousActive);
            renderThreadNanos += System.nanoTime() - started;
        }
    }

    /**
     * Advances the transfer without blocking. Returns true once the encoded atlas was handed to
     * the native renderer; false while the readback or encoder is still running.
     */
    boolean poll(int threads) {
        if (readbackFence != 0) {
            int status = GL32.glClientWaitSync(readbackFence, 0, 0);
            if (status == GL32.GL_TIMEOUT_EXPIRED) return false;
            if (status == GL32.GL_WAIT_FAILED) throw new IllegalStateException("Atlas readback synchronization failed.");
            long started = System.nanoTime();
            GL32.glDeleteSync(readbackFence);
            readbackFence = 0;
            ByteBuffer[] pixels = copyReadback();
            renderThreadNanos += System.nanoTime() - started;
            int[] widths = levelWidths;
            int[] heights = levelHeights;
            encodingGeneration = generation;
            encoding = compress
                    ? CompletableFuture.supplyAsync(() -> encode(pixels, widths, heights, threads), encoder)
                    : CompletableFuture.completedFuture(pixels);
            if (!compress) return poll(threads);
            return false;
        }
        if (encoding == null || !encoding.isDone()) return false;
        CompletableFuture<ByteBuffer[]> done = encoding;
        encoding = null;
        if (encodingGeneration != generation) return false;
        long started = System.nanoTime();
        ByteBuffer[] levels = done.join();
        NativeDx12.updateTerrainAtlas(levels, baseWidth, baseHeight, compress, threads, compress);
        if (Phase0Capture.ENABLED) Phase0Capture.atlas(levels, baseWidth, baseHeight, compress);
        renderThreadNanos += System.nanoTime() - started;
        return true;
    }

    private ByteBuffer[] copyReadback() {
        int previousPack = GL11.glGetInteger(GL30.GL_PIXEL_PACK_BUFFER_BINDING);
        GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, readbackBuffer);
        try {
            long total = 0;
            for (int level = 0; level < levelWidths.length; level++) total += (long) levelWidths[level] * levelHeights[level] * 4;
            ByteBuffer mapped = GL30.glMapBufferRange(GL30.GL_PIXEL_PACK_BUFFER, 0, total, GL30.GL_MAP_READ_BIT);
            if (mapped == null) throw new IllegalStateException("Cannot map the completed atlas readback.");
            ByteBuffer[] levels = new ByteBuffer[levelWidths.length];
            try {
                for (int level = 0; level < levels.length; level++) {
                    int size = levelWidths[level] * levelHeights[level] * 4;
                    ByteBuffer slice = mapped.slice();
                    slice.limit(size);
                    levels[level] = ByteBuffer.allocateDirect(size).put(slice).flip();
                    mapped.position(mapped.position() + size);
                }
            } finally {
                if (!GL15.glUnmapBuffer(GL30.GL_PIXEL_PACK_BUFFER)) throw new IllegalStateException("Atlas readback became invalid.");
            }
            return levels;
        } finally {
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, previousPack);
        }
    }

    private static ByteBuffer[] encode(ByteBuffer[] pixels, int[] widths, int[] heights, int threads) {
        ByteBuffer[] encoded = new ByteBuffer[pixels.length];
        for (int level = 0; level < pixels.length; level++) {
            int bytes = ((widths[level] + 3) / 4) * ((heights[level] + 3) / 4) * 16;
            encoded[level] = ByteBuffer.allocateDirect(bytes);
            NativeDx12.encodeAtlasRegion(pixels[level], encoded[level], widths[level], heights[level], threads);
        }
        return encoded;
    }
}
