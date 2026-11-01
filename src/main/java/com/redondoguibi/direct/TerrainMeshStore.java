package com.redondoguibi.direct;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.lwjgl.system.MemoryUtil;

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
    private static final float POSITION_SCALE = 1024.0f;
    private static final float POSITION_ORIGIN = -16.0f;
    private static final int LAYER_COUNT = 3;
    private static final Object LOCK = new Object();
    private static final ConcurrentLinkedQueue<SectionMesh> UPDATES = new ConcurrentLinkedQueue<>();
    private static final Long2LongOpenHashMap VERSIONS = new Long2LongOpenHashMap(); // guardado por LOCK
    private static final ThreadLocal<long[]> CAPTURE_TOKEN = ThreadLocal.withInitial(() -> new long[2]);
    private static RenderType[] layers;
    private static long generation; // guardado por LOCK
    private static volatile boolean captureEnabled;
    private static volatile boolean captureFaulted;

    static {
        VERSIONS.defaultReturnValue(0L);
    }

    private TerrainMeshStore() {
    }

    /** HEAD de SectionCompiler.compile: fixa geração/versão antes da compilação começar. */
    public static void beginCapture(SectionPos sectionPos) {
        if (!captureEnabled) {
            return;
        }
        long[] token = CAPTURE_TOKEN.get();
        synchronized (LOCK) {
            token[0] = generation;
            token[1] = VERSIONS.get(sectionPos.asLong());
        }
    }

    public static void capture(SectionPos sectionPos, SectionCompiler.Results results) {
        if (!captureEnabled || captureFaulted || results == null) {
            return;
        }
        long[] token = CAPTURE_TOKEN.get();
        long captureGeneration = token[0];
        long captureVersion = token[1];
        long key = sectionPos.asLong();
        BlockPos origin = sectionPos.origin();
        try {
            RenderType[] supported = supportedLayers();
            var rendered = results.renderedLayers;
            long vertexCount = 0;
            long indexCount = 0;
            MeshData[] meshes = new MeshData[LAYER_COUNT];
            for (int layerIndex = 0; layerIndex < LAYER_COUNT; layerIndex++) {
                MeshData mesh = rendered.get(supported[layerIndex]);
                if (!isSupported(mesh)) {
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
                publish(captureGeneration, captureVersion, SectionMesh.empty(key));
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
            publish(captureGeneration, captureVersion, new SectionMesh(key, origin.getX(), origin.getY(),
                    origin.getZ(), vertices, vertexBase, indices, layerIndexCounts));
        } catch (RuntimeException exception) {
            captureFaulted = true;
            captureEnabled = false;
            Direct.LOGGER.error("Could not extract vanilla terrain mesh for section {}.", sectionPos, exception);
        }
    }

    /** Seção saiu da distância/foi reposicionada: remove e invalida compilações em andamento. */
    public static void remove(SectionPos sectionPos) {
        if (!captureEnabled) {
            return;
        }
        long key = sectionPos.asLong();
        synchronized (LOCK) {
            VERSIONS.addTo(key, 1L);
            UPDATES.add(SectionMesh.empty(key));
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            generation++;
            VERSIONS.clear();
            UPDATES.clear();
        }
        if (NativeLibrary.isLoaded()) {
            NativeDx12.clearTerrain();
        }
    }

    public static void setCaptureEnabled(boolean enabled) {
        if (enabled) {
            captureFaulted = false;
        }
        captureEnabled = enabled;
    }

    public static boolean hasCaptureFault() {
        return captureFaulted;
    }

    public static void clearCaptureFault() {
        captureFaulted = false;
    }

    public static void uploadPending() {
        SectionMesh update;
        while ((update = UPDATES.poll()) != null) {
            if (update.vertices() == null) {
                NativeDx12.removeTerrainSection(update.key());
            } else {
                NativeDx12.updateTerrainSection(update.key(), update.sectionX(), update.sectionY(),
                        update.sectionZ(), update.vertices(), update.vertexCount(), update.indices(),
                        update.layerIndexCounts());
            }
        }
    }

    /** Posição local da seção em [-16, 48) com precisão de 1/1024 de bloco. */
    private static short packPosition(float value) {
        int packed = Math.round((value - POSITION_ORIGIN) * POSITION_SCALE);
        return (short) Math.max(0, Math.min(0xFFFF, packed));
    }

    private static short packUnorm16(float value) {
        return (short) Math.round(Math.max(0.0f, Math.min(1.0f, value)) * 0xFFFF);
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
        return stride > 0 && positionOffset >= 0 && colorOffset >= 0 && uvOffset >= 0
                && positionOffset + 3 * Float.BYTES <= stride
                && colorOffset + Integer.BYTES <= stride
                && uvOffset + 2 * Float.BYTES <= stride
                && (indexSize == Short.BYTES || indexSize == Integer.BYTES)
                && (long) drawState.vertexCount() * stride <= mesh.vertexBuffer().remaining()
                && (long) drawState.indexCount() * indexSize <= mesh.indexBuffer().remaining();
    }

    private static void packVertices(ByteBuffer destination, ByteBuffer source, MeshData.DrawState drawState) {
        VertexFormat format = drawState.format();
        int stride = format.getVertexSize();
        int positionOffset = format.getOffset(VertexFormatElement.POSITION);
        int colorOffset = format.getOffset(VertexFormatElement.COLOR);
        int uvOffset = format.getOffset(VertexFormatElement.UV0);
        int vertexCount = drawState.vertexCount();
        if (destination.isDirect() && source.isDirect()) {
            long src = MemoryUtil.memAddress(source) + source.position();
            long dest = MemoryUtil.memAddress(destination) + destination.position();
            for (int vertex = 0; vertex < vertexCount; vertex++) {
                long offset = src + (long) vertex * stride;
                MemoryUtil.memPutShort(dest, packPosition(MemoryUtil.memGetFloat(offset + positionOffset)));
                MemoryUtil.memPutShort(dest + 2,
                        packPosition(MemoryUtil.memGetFloat(offset + positionOffset + Float.BYTES)));
                MemoryUtil.memPutShort(dest + 4,
                        packPosition(MemoryUtil.memGetFloat(offset + positionOffset + 2 * Float.BYTES)));
                MemoryUtil.memPutShort(dest + 6, (short) 0);
                MemoryUtil.memPutInt(dest + 8, MemoryUtil.memGetInt(offset + colorOffset));
                MemoryUtil.memPutShort(dest + 12, packUnorm16(MemoryUtil.memGetFloat(offset + uvOffset)));
                MemoryUtil.memPutShort(dest + 14,
                        packUnorm16(MemoryUtil.memGetFloat(offset + uvOffset + Float.BYTES)));
                dest += OUTPUT_VERTEX_SIZE;
            }
            destination.position(destination.position() + vertexCount * OUTPUT_VERTEX_SIZE);
            return;
        }
        ByteBuffer sourceVertices = source.duplicate().order(ByteOrder.nativeOrder());
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int offset = sourceVertices.position() + vertex * stride;
            destination.putShort(packPosition(sourceVertices.getFloat(offset + positionOffset)));
            destination.putShort(packPosition(sourceVertices.getFloat(offset + positionOffset + Float.BYTES)));
            destination.putShort(packPosition(sourceVertices.getFloat(offset + positionOffset + 2 * Float.BYTES)));
            destination.putShort((short) 0);
            destination.putInt(sourceVertices.getInt(offset + colorOffset));
            destination.putShort(packUnorm16(sourceVertices.getFloat(offset + uvOffset)));
            destination.putShort(packUnorm16(sourceVertices.getFloat(offset + uvOffset + Float.BYTES)));
        }
    }

    private static void packIndices(ByteBuffer destination, ByteBuffer source, MeshData.DrawState drawState,
            int vertexBase) {
        int indexCount = drawState.indexCount();
        int indexSize = drawState.indexType().bytes;
        if (destination.isDirect() && source.isDirect()) {
            long src = MemoryUtil.memAddress(source) + source.position();
            long dest = MemoryUtil.memAddress(destination) + destination.position();
            if (indexSize == Short.BYTES) {
                for (int index = 0; index < indexCount; index++) {
                    MemoryUtil.memPutInt(dest, Short.toUnsignedInt(MemoryUtil.memGetShort(src)) + vertexBase);
                    src += Short.BYTES;
                    dest += Integer.BYTES;
                }
            } else {
                for (int index = 0; index < indexCount; index++) {
                    MemoryUtil.memPutInt(dest, MemoryUtil.memGetInt(src) + vertexBase);
                    src += Integer.BYTES;
                    dest += Integer.BYTES;
                }
            }
            destination.position(destination.position() + indexCount * Integer.BYTES);
            return;
        }
        ByteBuffer sourceIndices = source.duplicate().order(ByteOrder.nativeOrder());
        for (int index = 0; index < indexCount; index++) {
            int sourceIndex = sourceIndices.position() + index * indexSize;
            int value = indexSize == Short.BYTES
                    ? Short.toUnsignedInt(sourceIndices.getShort(sourceIndex))
                    : sourceIndices.getInt(sourceIndex);
            destination.putInt(value + vertexBase);
        }
    }

    private static void publish(long captureGeneration, long captureVersion, SectionMesh mesh) {
        synchronized (LOCK) {
            if (generation != captureGeneration || VERSIONS.get(mesh.key()) != captureVersion) {
                return;
            }
            UPDATES.add(mesh);
        }
    }

    private record SectionMesh(long key, int sectionX, int sectionY, int sectionZ,
            ByteBuffer vertices, int vertexCount, ByteBuffer indices, int[] layerIndexCounts) {
        static SectionMesh empty(long key) {
            return new SectionMesh(key, 0, 0, 0, null, 0, null, null);
        }
    }
}
