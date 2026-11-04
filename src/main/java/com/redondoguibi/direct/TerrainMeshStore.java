package com.redondoguibi.direct;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

public final class TerrainMeshStore {
    // Precisa bater com o input layout nativo: posição uint16 x4, cor RGBA8, UV unorm16 x2.
    private static final int OUTPUT_VERTEX_SIZE = 20;
    private static final int LAYER_COUNT = 4;
    private static final Object LOCK = new Object();
    // Coalesce pending work by section; guarded by LOCK with VERSIONS and generation.
    private static final ArrayDeque<Long> PENDING_KEYS = new ArrayDeque<>();
    private static final Map<Long, SectionMesh> PENDING = new HashMap<>();
    // Retains invalidation versions only while that section has captures in flight; the last finish evicts it.
    private static final Long2LongOpenHashMap VERSIONS = new Long2LongOpenHashMap(); // guarded by LOCK
    private static final Map<Long, Integer> IN_FLIGHT = new HashMap<>();
    private static final ThreadLocal<CaptureToken> CAPTURE_TOKEN = ThreadLocal.withInitial(CaptureToken::new);
    private static final java.util.Set<Long> NATIVE_PRESENT=new java.util.HashSet<>();
    private static final TerrainOwnership<SectionMesh> OWNERSHIP = new TerrainOwnership<>();
    private static RenderType[] layers;
    private static long generation; // guarded by LOCK
    private static long pendingBytes; // guarded by LOCK
    private static int activeCaptures; // guarded by LOCK
    private static long successfulCaptures; // guarded by LOCK
    private static long lastCaptureActivityNanos; // guarded by LOCK
    private static boolean reportedPendingBudgetOverrun; // guarded by LOCK
    private static volatile boolean captureEnabled;
    private static volatile boolean captureFaulted;
    private static volatile boolean captureReady;
    private static volatile boolean replacementSafe = true;

    static {
        VERSIONS.defaultReturnValue(0L);
    }

    private TerrainMeshStore() {
    }

    /** HEAD of SectionCompiler.compile: captures an epoch/version token and tracks in-flight work. */
    public static void beginCapture(SectionPos sectionPos) {
        CaptureToken token = CAPTURE_TOKEN.get();
        if (token.active) {
            finishCapture(token);
        }
        token.active = false;
        if (!captureEnabled || captureFaulted) {
            return;
        }
        long key = sectionPos.asLong();
        synchronized (LOCK) {
            token.key = key;
            token.slot = OWNERSHIP.begin(key);
            token.generation = generation;
            token.version = VERSIONS.get(key);
            token.active = true;
            IN_FLIGHT.merge(key, 1, Integer::sum);
            activeCaptures++;
            captureReady = false;
            lastCaptureActivityNanos = System.nanoTime();
        }
    }

    public static void capture(SectionPos sectionPos, SectionCompiler.Results results,net.minecraft.client.renderer.chunk.RenderChunkRegion region) {
        CaptureToken token = CAPTURE_TOKEN.get();
        if (!token.active) {
            return;
        }
        boolean processed = false;
        long key = sectionPos.asLong();
        BlockPos origin = sectionPos.origin();
        try {
            if (!captureEnabled || captureFaulted || results == null) {
                return;
            }
            RenderType[] supported = supportedLayers();
            var rendered = results.renderedLayers;
            long vertexCount = 0;
            long indexCount = 0;
            MeshData[] meshes = new MeshData[LAYER_COUNT];
            for (int layerIndex = 0; layerIndex < LAYER_COUNT; layerIndex++) {
                MeshData mesh = rendered.get(supported[layerIndex]);
                if (mesh == null) {
                    continue;
                }
                if (!isSupported(mesh)) {
                    // Keep vanilla rendering available when another mod injects an incompatible base-layer mesh.
                    return;
                }
                if (!hasValidBuffers(mesh)) {
                    throw new IllegalStateException("Vanilla terrain mesh has inconsistent buffer bounds.");
                }
                meshes[layerIndex] = mesh;
                vertexCount += mesh.drawState().vertexCount();
                indexCount += mesh.drawState().indexCount();
            }
            if (vertexCount == 0 || indexCount == 0
                    || vertexCount > Integer.MAX_VALUE / OUTPUT_VERTEX_SIZE
                    || indexCount > Integer.MAX_VALUE / Integer.BYTES) {
                // Empty compilation is retired by the vanilla commit hook.
                processed = true;
                return;
            }

            ByteBuffer vertices = ByteBuffer.allocateDirect((int) vertexCount * OUTPUT_VERTEX_SIZE)
                    .order(ByteOrder.nativeOrder());
            ByteBuffer indices = ByteBuffer.allocateDirect((int) indexCount * Integer.BYTES)
                    .order(ByteOrder.nativeOrder());
            int[] layerIndexCounts = new int[LAYER_COUNT];
            int vertexBase = 0;
            for (int layerIndex = 0; layerIndex < LAYER_COUNT; layerIndex++) {
                MeshData mesh = meshes[layerIndex];
                if (mesh == null) {
                    continue;
                }
                MeshData.DrawState drawState = mesh.drawState();
                packVerticesV2(vertices, mesh.vertexBuffer(), drawState, origin, region);
                packIndices(indices, mesh.indexBuffer(), drawState, vertexBase);
                layerIndexCounts[layerIndex] = drawState.indexCount();
                vertexBase += drawState.vertexCount();
            }
            vertices.flip();
            indices.flip();
            synchronized (LOCK) {
                if(generation==token.generation&&VERSIONS.get(key)==token.version)
                    OWNERSHIP.stage(token.slot,new SectionMesh(key,origin.getX(),origin.getY(),origin.getZ(),vertices,vertexBase,indices,layerIndexCounts,null),rendered.values());
            }
            processed = true;
        } catch (RuntimeException exception) {
            captureFaulted = true;
            captureEnabled = false;
            captureReady = false;
            synchronized (LOCK) {
                LOCK.notifyAll();
            }
            Direct.LOGGER.error("Could not extract vanilla terrain mesh for section {}.", sectionPos, exception);
        } finally {
            synchronized (LOCK) {
                if (processed && token.generation == generation) {
                    successfulCaptures++;
                }
                lastCaptureActivityNanos = System.nanoTime();
            }
            finishCapture(token);
        }
    }
    /** Seção saiu da distância/foi reposicionada: remove e invalida compilações em andamento. */
    public static void remove(SectionPos sectionPos) {
        if (!captureEnabled) {
            return;
        }
        long key = sectionPos.asLong();
        synchronized (LOCK) {
            captureReady = false;
            OWNERSHIP.remove(key);
            lastCaptureActivityNanos = System.nanoTime();
            VERSIONS.addTo(key, 1L);
            if(NATIVE_PRESENT.contains(key)||PENDING.containsKey(key))enqueuePendingLocked(SectionMesh.empty(key));
            if (IN_FLIGHT.getOrDefault(key, 0) == 0) {
                VERSIONS.remove(key);
            }
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            generation++;
            VERSIONS.clear();
            PENDING_KEYS.clear();
            PENDING.clear();
            OWNERSHIP.clear();NATIVE_PRESENT.clear();
            pendingBytes = 0L;
            successfulCaptures = 0L;
            replacementSafe = true;
            captureReady = false;
            lastCaptureActivityNanos = System.nanoTime();
            reportedPendingBudgetOverrun = false;
            LOCK.notifyAll();
        }
        if (NativeLibrary.isLoaded()) {
            NativeDx12.clearTerrain();
        }
    }

    public static void setCaptureEnabled(boolean enabled) {
        if (enabled) {
            captureFaulted = false;
            replacementSafe = true;
        }
        captureReady = false;
        captureEnabled = enabled;
        synchronized (LOCK) {
            if (!enabled) {
                LOCK.notifyAll();
            }
        }
    }

    /** True only after captures are drained and the terrain set has been quiet for a short grace period. */
    public static boolean isCaptureReady() {
        return captureReady && replacementSafe && captureEnabled && !captureFaulted;
    }

    public static boolean canReplaceVisible(long[] keys, int count) {
        synchronized (LOCK) {
            if (!captureEnabled || captureFaulted || !replacementSafe) return false;
            for (int i = 0; i < count; i++) {
                long key = keys[i];
                if (!OWNERSHIP.current(key)) return false;
            }
            return true;
        }
    }

    public static boolean hasCaptureFault() {
        return captureFaulted;
    }
    static long[] pendingMetrics(){synchronized(LOCK){return new long[]{pendingBytes,PENDING.size(),activeCaptures};}}

    /** Closes a capture if SectionCompiler.compile exits exceptionally before its return hook. */
    public static void finishCaptureForCurrentThread() {
        CaptureToken token = CAPTURE_TOKEN.get();
        if (token.active) {
            finishCapture(token);
        }
    }

    public static void clearCaptureFault() {
        captureFaulted = false;
    }

    public static void uploadPending() {
        long uploadStart=RendererMetrics.recording?System.nanoTime():0;
        final int maxUpdates = ClientConfig.MAX_MESH_UPLOADS_PER_FRAME.get();
        final long maxBytes = ClientConfig.MAX_MESH_UPLOAD_MIB_PER_FRAME.get() * 1024L * 1024L;
        int uploaded = 0;
        long uploadedBytes = 0L;

        while (uploaded < maxUpdates) {
            SectionMesh update;
            synchronized (LOCK) {
                Long key = PENDING_KEYS.peekFirst();
                if (key == null) {
                    break;
                }
                SectionMesh candidate = PENDING.get(key);
                long candidateBytes = candidate == null ? 0L : candidate.byteSize();
                // Soft budget: allow one oversized section so it can never block the queue forever.
                if (uploaded > 0 && candidateBytes > maxBytes - uploadedBytes) {
                    break;
                }
                PENDING_KEYS.removeFirst();
                update = PENDING.remove(key);
                if (update != null) {
                    pendingBytes = Math.max(0L, pendingBytes - update.byteSize());
                    if(update.vertices()!=null&&!OWNERSHIP.accepts(key,update.receipt()))update=null;
                }
                LOCK.notifyAll();
            }
            if (update == null) {
                continue;
            }
            if (update.vertices() == null) {
                NativeDx12.removeTerrainSection(update.key());
            } else {
                NativeDx12.updateTerrainSection(update.key(), update.sectionX(), update.sectionY(),
                        update.sectionZ(), update.vertices(), update.vertexCount(), update.indices(),
                        update.layerIndexCounts());
            }
            synchronized (LOCK) {
                if(update.vertices()==null)NATIVE_PRESENT.remove(update.key());else NATIVE_PRESENT.add(update.key());
                OWNERSHIP.nativeUploaded(update.key(),update.receipt());
            }
            uploaded++;
            uploadedBytes += update.byteSize();
        }

        if(RendererMetrics.recording){RendererMetrics.uploadNanos+=System.nanoTime()-uploadStart;RendererMetrics.uploadSections+=uploaded;RendererMetrics.uploadBytes+=uploadedBytes;}
        synchronized (LOCK) {
            long quietNanos = System.nanoTime() - lastCaptureActivityNanos;
            if (captureEnabled && !captureFaulted && successfulCaptures > 0 && PENDING.isEmpty()
                    && activeCaptures == 0 && quietNanos >= 750_000_000L) {
                captureReady = true;
            }
            if (pendingBytes < (long) ClientConfig.MAX_PENDING_MESH_MIB.get() * 1024L * 1024L) {
                reportedPendingBudgetOverrun = false;
            }
        }
    }

    private static RenderType[] supportedLayers() {
        RenderType[] cached = layers;
        if (cached == null) {
            // A ordem precisa bater com kLayerAlphaCutoff no nativo.
            cached = new RenderType[] {RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout(), RenderType.translucent()};
            layers = cached;
        }
        return cached;
    }

    private static boolean isSupported(MeshData mesh) {
        return mesh != null && mesh.drawState().format() == DefaultVertexFormat.BLOCK
                && mesh.drawState().mode() == VertexFormat.Mode.QUADS;
    }

    private static boolean hasValidBuffers(MeshData mesh) {
        MeshData.DrawState drawState = mesh.drawState();
        VertexFormat format = drawState.format();
        int stride = format.getVertexSize();
        int indexSize = drawState.indexType().bytes;
        int positionOffset = format.getOffset(VertexFormatElement.POSITION);
        int colorOffset = format.getOffset(VertexFormatElement.COLOR);
        int uvOffset = format.getOffset(VertexFormatElement.UV0);
        ByteBuffer indexBuffer = mesh.indexBuffer();
        boolean validIndices = indexBuffer == null
                ? TerrainIndexPacker.hasValidSequentialQuadIndices(drawState.vertexCount(), drawState.indexCount())
                : (long) drawState.indexCount() * indexSize <= indexBuffer.remaining();
        return drawState.vertexCount() >= 0 && drawState.indexCount() >= 0
                && stride > 0 && positionOffset >= 0 && colorOffset >= 0 && uvOffset >= 0
                && positionOffset + 3 * Float.BYTES <= stride
                && colorOffset + Integer.BYTES <= stride
                && uvOffset + 2 * Float.BYTES <= stride
                && (indexSize == Short.BYTES || indexSize == Integer.BYTES)
                && (long) drawState.vertexCount() * stride <= mesh.vertexBuffer().remaining()
                && validIndices;
    }

    static void packVertices(ByteBuffer destination, ByteBuffer source, MeshData.DrawState drawState) {
        VertexFormat format = drawState.format();
        TerrainBufferPacker.packVertices(destination, source, drawState.vertexCount(), format.getVertexSize(),
                format.getOffset(VertexFormatElement.POSITION), format.getOffset(VertexFormatElement.COLOR),
                format.getOffset(VertexFormatElement.UV0), format.getOffset(VertexFormatElement.UV2));
    }

    private static void packVerticesV2(ByteBuffer destination,ByteBuffer source,MeshData.DrawState drawState,BlockPos origin,net.minecraft.client.renderer.chunk.RenderChunkRegion region){
        var format=drawState.format();int stride=format.getVertexSize(),position=format.getOffset(VertexFormatElement.POSITION),normal=format.getOffset(VertexFormatElement.NORMAL),uv=format.getOffset(VertexFormatElement.UV0);
        byte[] materials=new byte[(drawState.vertexCount()+3)/4];
        var cache=new java.util.IdentityHashMap<net.minecraft.world.level.block.state.BlockState,Byte>();
        ByteBuffer input=source.duplicate().order(ByteOrder.nativeOrder());var block=new BlockPos.MutableBlockPos();
        for(int q=0;q<materials.length;q++){
            int first=q*4,n=Math.min(4,drawState.vertexCount()-first);float x=0,y=0,z=0,u=0,v=0;
            for(int i=0;i<n;i++){int base=input.position()+(first+i)*stride,at=base+position;x+=input.getFloat(at);y+=input.getFloat(at+4);z+=input.getFloat(at+8);u+=input.getFloat(base+uv);v+=input.getFloat(base+uv+4);}
            // Sprite identity also covers flowing/waterlogged faces without classifying the
            // surrounding glass/wood model as water. Resource reload publishes a new UV snapshot.
            if(TerrainMaterials.waterSprite(u/n,v/n)){materials[q]=TerrainMaterials.WATER;continue;}
            int at=input.position()+first*stride+normal;
            block.set(origin.getX()+(int)Math.floor(x/n-input.get(at)/127f*.02f),origin.getY()+(int)Math.floor(y/n-input.get(at+1)/127f*.02f),origin.getZ()+(int)Math.floor(z/n-input.get(at+2)/127f*.02f));
            var state=region.getBlockState(block);materials[q]=cache.computeIfAbsent(state,k->(byte)TerrainMaterials.classify(k));
        }
        TerrainBufferPacker.packVertices20(destination,source,drawState.vertexCount(),stride,position,format.getOffset(VertexFormatElement.COLOR),format.getOffset(VertexFormatElement.UV0),format.getOffset(VertexFormatElement.UV2),normal,materials);
    }

    private static void packIndices(ByteBuffer destination, ByteBuffer source, MeshData.DrawState drawState,
            int vertexBase) {
        int indexCount = drawState.indexCount();
        int indexSize = drawState.indexType().bytes;
        if (source == null) {
            TerrainIndexPacker.packSequentialQuadIndices(destination, indexCount, vertexBase);
            return;
        }
        TerrainBufferPacker.packIndices(destination, source, indexCount, indexSize, vertexBase);
    }

    public static void vanillaUploaded(MeshData source,com.mojang.blaze3d.vertex.VertexBuffer buffer){
        synchronized(LOCK){OWNERSHIP.uploaded(source,buffer);}
    }
    public static void vanillaCommitted(net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection section){
        if(!captureEnabled||captureFaulted)return;
        long key=SectionPos.of(section.getOrigin()).asLong();
        var compiled=section.getCompiled();
        var buffers=new java.util.ArrayList<com.mojang.blaze3d.vertex.VertexBuffer>();
        for(var layer:supportedLayers())if(!compiled.isEmpty(layer))buffers.add(section.getBuffer(layer));
        synchronized(LOCK){
            var receipt=OWNERSHIP.commit(key,buffers);
            var mesh=receipt==null?null:receipt.take();
            if(mesh!=null)enqueuePendingLocked(new SectionMesh(key,mesh.sectionX(),mesh.sectionY(),mesh.sectionZ(),mesh.vertices(),mesh.vertexCount(),mesh.indices(),mesh.layerIndexCounts(),receipt));
            else if(receipt==null&&(NATIVE_PRESENT.contains(key)||PENDING.containsKey(key)))enqueuePendingLocked(SectionMesh.empty(key));
        }
    }
    static boolean owns(long key){synchronized(LOCK){return captureEnabled&&!captureFaulted&&OWNERSHIP.current(key);}}
    static long[] shadowKeys(){synchronized(LOCK){return OWNERSHIP.nativeKeys();}}

    /** Must be called while holding LOCK. */
    private static void enqueuePendingLocked(SectionMesh mesh) {
        long key = mesh.key();
        SectionMesh previous = PENDING.get(key);
        if (previous == null) {
            PENDING_KEYS.addLast(key);
        } else {
            pendingBytes -= previous.byteSize();
        }
        PENDING.put(key, mesh);
        pendingBytes += mesh.byteSize();
        long budget=(long)ClientConfig.MAX_PENDING_MESH_MIB.get()*1024*1024;
        if(pendingBytes>budget&&!reportedPendingBudgetOverrun){
            Direct.LOGGER.warn("Committed Direct terrain queue exceeds its soft budget ({} MiB); keeping per-section fallback while uploads drain.",ClientConfig.MAX_PENDING_MESH_MIB.get());
            reportedPendingBudgetOverrun=true;
        }
    }

    private static void finishCapture(CaptureToken token) {
        synchronized (LOCK) {
            if (!token.active) {
                return;
            }
            token.active = false;
            activeCaptures = Math.max(0, activeCaptures - 1);
            int inFlight = IN_FLIGHT.getOrDefault(token.key, 0);
            if (inFlight <= 1) {
                IN_FLIGHT.remove(token.key);
                VERSIONS.remove(token.key);
            } else {
                IN_FLIGHT.put(token.key, inFlight - 1);
            }
            lastCaptureActivityNanos = System.nanoTime();
            LOCK.notifyAll();
        }
    }

    private static final class CaptureToken {
        long key;
        long generation;
        long version;
        boolean active;
        TerrainOwnership.Slot slot;
    }

    private record SectionMesh(long key, int sectionX, int sectionY, int sectionZ,
            ByteBuffer vertices, int vertexCount, ByteBuffer indices, int[] layerIndexCounts,Object receipt) {
        long byteSize() {
            return vertices == null ? 0L : (long) vertices.remaining() + indices.remaining();
        }

        static SectionMesh empty(long key) {
            return new SectionMesh(key, 0, 0, 0, null, 0, null, null,null);
        }
    }
}
