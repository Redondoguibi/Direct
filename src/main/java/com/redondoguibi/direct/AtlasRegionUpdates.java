package com.redondoguibi.direct;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

/** Render-thread readback of changed sprite rectangles; CPU compression runs on private pixel copies. */
final class AtlasRegionUpdates {
    private record Region(int x, int y, int width, int height) {}
    private record Patch(int level, Region region, ByteBuffer pixels, ByteBuffer encoded) {}
    private final java.util.concurrent.ExecutorService encoder = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Direct atlas encoder");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Long, Region> dirty = new LinkedHashMap<>();
    private final Map<String, Patch> buffers = new java.util.HashMap<>();
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

    void reset() {
        if (readbackFence != 0) GL32.glDeleteSync(readbackFence);
        if (readbackBuffer != 0) GL15.glDeleteBuffers(readbackBuffer);
        readbackFence=0;readbackBuffer=0;readbackPatches=null;
        generation++;
        dirty.clear();
        buffers.clear();
        lastCapture = 0;
        // A running native encoder owns its buffers until it finishes; never reuse them after reset.
    }

    void close() {
        reset();
        if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
        framebuffer = 0;
    }

    void mark(int x, int y, int width, int height) {
        dirty.put((long) x << 32 | Integer.toUnsignedLong(y), new Region(x, y, width, height));
    }
    boolean hasPendingWork(){return readbackFence!=0||pending!=null||!dirty.isEmpty();}

    void update(int atlas, int intervalMillis, int threads) {
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
            pendingGeneration=readbackGeneration;
            pending=CompletableFuture.supplyAsync(()->{for(Patch patch:ready)if(compressed)NativeDx12.encodeAtlasRegion(patch.pixels,patch.encoded,patch.region.width,patch.region.height,threads);return ready;},encoder);
        }
        if (pending != null) {
            if (!pending.isDone()) return;
            var patches = pendingGeneration == generation ? pending.join() : List.<Patch>of();
            pending = null;
            if (pendingGeneration == generation) {
                for (int i = 0; i < patches.size(); i++) {
                    Patch patch = patches.get(i);
                    Region r = patch.region;
                    if (!NativeDx12.updateAtlasRegion(patch.encoded, patch.level, r.x, r.y, r.width, r.height)) {
                        pending = CompletableFuture.completedFuture(patches.subList(i, patches.size()));
                        return;
                    }
                    RendererMetrics.atlasBytes += patch.encoded.remaining();
                }
            }
        }
        long now = System.nanoTime();
        if (dirty.isEmpty() || now - lastCapture < intervalMillis * 1_000_000L) return;
        lastCapture = now;
        int oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldPack = GL11.glGetInteger(GL30.GL_PIXEL_PACK_BUFFER_BINDING);
        int oldAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int oldRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int oldSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int oldSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        List<Patch> patches = new ArrayList<>();
        long started = System.nanoTime();
        try {
            if (framebuffer == 0) framebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
            boolean compressed = NativeDx12.isAtlasCompressed();
            int levels = NativeDx12.atlasMipLevels();
            for (int level = 0; level < levels; level++) {
                int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_WIDTH);
                int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_HEIGHT);
                GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, atlas, level);
                GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
                if (GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException("Atlas mip cannot be read as a framebuffer.");
                for (Region source : dirty.values()) {
                    int align = compressed ? 4 : 1;
                    int x = (source.x >> level) / align * align;
                    int y = (source.y >> level) / align * align;
                    int right = Math.min(width, (((source.x + source.width + (1 << level) - 1) >> level) + align - 1) / align * align);
                    int top = Math.min(height, (((source.y + source.height + (1 << level) - 1) >> level) + align - 1) / align * align);
                    if (right <= x || top <= y) continue;
                    Region r = new Region(x, y, right - x, top - y);
                    String key = level + ":" + r;
                    int bytes = compressed ? ((r.width + 3) / 4) * ((r.height + 3) / 4) * 16 : r.width * r.height * 4;
                    Patch patch = buffers.get(key);
                    if (patch == null) {
                        ByteBuffer pixels = ByteBuffer.allocateDirect(r.width * r.height * 4);
                        patch = new Patch(level, r, pixels, compressed ? ByteBuffer.allocateDirect(bytes) : pixels);
                        buffers.put(key, patch);
                    }
                    if (patches.contains(patch)) continue;
                    patches.add(patch);
                }
            }
            if(!patches.isEmpty()){
                long bytes=0;for(Patch patch:patches)bytes+=(long)patch.region.width*patch.region.height*4;
                if(readbackBuffer==0)readbackBuffer=GL15.glGenBuffers();
                GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER,readbackBuffer);
                GL15.glBufferData(GL30.GL_PIXEL_PACK_BUFFER,bytes,GL15.GL_STREAM_READ);
                int previousLevel=-1;long offset=0;
                for(Patch patch:patches){
                    if(previousLevel!=patch.level){GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,atlas,patch.level);previousLevel=patch.level;}
                    Region r=patch.region;GL11.glReadPixels(r.x,r.y,r.width,r.height,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,offset);
                    offset+=(long)r.width*r.height*4;
                }
                readbackFence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
                if(readbackFence==0)throw new IllegalStateException("Could not create atlas readback fence");
                GL11.glFlush();readbackPatches=patches;readbackGeneration=generation;readbackCompressed=compressed;
            }
            dirty.clear();
        } finally {
            RendererMetrics.atlasReadNanos += System.nanoTime() - started;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, oldAlignment);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, oldRowLength);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, oldSkipRows);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, oldSkipPixels);
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, oldPack);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldRead);
        }
    }
}
