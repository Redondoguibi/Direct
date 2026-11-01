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
    private static final int OUTPUT_VERTEX_SIZE = 16;
    private static final int LAYER_COUNT = 3;
    private static final Object LOCK = new Object();
    // Coalesce pending work by section; guarded by LOCK with VERSIONS and generation.
    private static final ArrayDeque<Long> PENDING_KEYS = new ArrayDeque<>();
    private static final Map<Long, SectionMesh> PENDING = new HashMap<>();
    // Retains invalidation versions only while that section has captures in flight; the last finish evicts it.
    private static final Long2LongOpenHashMap VERSIONS = new Long2LongOpenHashMap(); // guarded by LOCK
    private static final Map<Long, Integer> IN_FLIGHT = new HashMap<>();
    private static final ThreadLocal<CaptureToken> CAPTURE_TOKEN = ThreadLocal.withInitial(CaptureToken::new);
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
            token.generation = generation;
            token.version = VERSIONS.get(key);
            token.active = true;
            IN_FLIGHT.merge(key, 1, Integer::sum);
            activeCaptures++;
            captureReady = false;
            lastCaptureActivityNanos = System.nanoTime();
        }
    }

    public static void capture(SectionPos sectionPos, SectionCompiler.Results results) {
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
                    replacementSafe = false;
                    continue;
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
                publish(token.generation, token.version, SectionMesh.empty(key));
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
                packVertices(vertices, mesh.vertexBuffer(), drawState);
                packIndices(indices, mesh.indexBuffer(), drawState, vertexBase);
                layerIndexCounts[layerIndex] = drawState.indexCount();
                vertexBase += drawState.vertexCount();
            }
            vertices.flip();
            indices.flip();
            publish(token.generation, token.version, new SectionMesh(key, origin.getX(), origin.getY(),
                    origin.getZ(), vertices, vertexBase, indices, layerIndexCounts));
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
            lastCaptureActivityNanos = System.nanoTime();
            VERSIONS.addTo(key, 1L);
            enqueuePendingLocked(SectionMesh.empty(key));
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

    public static boolean hasCaptureFault() {
        return captureFaulted;
    }

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
            uploaded++;
            uploadedBytes += update.byteSize();
        }

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
            cached = new RenderType[] {RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout()};
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

    private static void packVertices(ByteBuffer destination, ByteBuffer source, MeshData.DrawState drawState) {
        VertexFormat format = drawState.format();
        TerrainBufferPacker.packVertices(destination, source, drawState.vertexCount(), format.getVertexSize(),
                format.getOffset(VertexFormatElement.POSITION), format.getOffset(VertexFormatElement.COLOR),
                format.getOffset(VertexFormatElement.UV0));
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

    private static void publish(long captureGeneration, long captureVersion, SectionMesh mesh) {
        synchronized (LOCK) {
            if (!captureEnabled || captureFaulted || generation != captureGeneration
                    || VERSIONS.get(mesh.key()) != captureVersion) {
                return;
            }

            long budgetBytes = (long) ClientConfig.MAX_PENDING_MESH_MIB.get() * 1024L * 1024L;
            long newBytes = mesh.byteSize();
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (!RenderSystem.isOnRenderThread() && captureEnabled && generation == captureGeneration) {
                SectionMesh previous = PENDING.get(mesh.key());
                long projected = pendingBytes - (previous == null ? 0L : previous.byteSize()) + newBytes;
                if (projected <= budgetBytes || System.nanoTime() >= deadline) {
                    break;
                }
                try {
                    LOCK.wait(25L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!captureEnabled || captureFaulted || generation != captureGeneration
                    || VERSIONS.get(mesh.key()) != captureVersion) {
                return;
            }
            long previousBytes = PENDING.containsKey(mesh.key()) ? PENDING.get(mesh.key()).byteSize() : 0L;
            if (pendingBytes - previousBytes + newBytes > budgetBytes && !reportedPendingBudgetOverrun) {
                Direct.LOGGER.warn("Direct pending terrain uploads exceeded the configured soft memory budget ({} MiB).",
                        ClientConfig.MAX_PENDING_MESH_MIB.get());
                reportedPendingBudgetOverrun = true;
            }
            enqueuePendingLocked(mesh);
        }
    }

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
    }

    private record SectionMesh(long key, int sectionX, int sectionY, int sectionZ,
            ByteBuffer vertices, int vertexCount, ByteBuffer indices, int[] layerIndexCounts) {
        long byteSize() {
            return vertices == null ? 0L : (long) vertices.remaining() + indices.remaining();
        }

        static SectionMesh empty(long key) {
            return new SectionMesh(key, 0, 0, 0, null, 0, null, null);
        }
    }
}
