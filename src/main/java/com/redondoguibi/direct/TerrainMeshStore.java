package com.redondoguibi.direct;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

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
    private static final Map<Long, Long> VERSIONS = new HashMap<>(); // guardado por LOCK
    private static final ThreadLocal<long[]> CAPTURE_TOKEN = ThreadLocal.withInitial(() -> new long[2]);
    private static long generation; // guardado por LOCK
    private static volatile boolean captureEnabled;

    private TerrainMeshStore() {
    }

    /** HEAD de SectionCompiler.compile: fixa geração/versão antes da compilação começar. */
    public static void beginCapture(SectionPos sectionPos) {
        long[] token = CAPTURE_TOKEN.get();
        synchronized (LOCK) {
            token[0] = generation;
            token[1] = VERSIONS.getOrDefault(sectionPos.asLong(), 0L);
        }
    }

    public static void capture(SectionPos sectionPos, SectionCompiler.Results results) {
        if (!captureEnabled || results == null) {
            return;
        }
        long[] token = CAPTURE_TOKEN.get();
        long captureGeneration = token[0];
        long captureVersion = token[1];
        long key = sectionPos.asLong();
        BlockPos origin = sectionPos.origin();
        try {
            RenderType[] layers = supportedLayers();
            Map<RenderType, MeshData> rendered = results.renderedLayers;
            long vertexCount = 0;
            long indexCount = 0;
            for (RenderType layer : layers) {
                MeshData mesh = rendered.get(layer);
                if (isSupported(mesh)) {
                    vertexCount += mesh.drawState().vertexCount();
                    indexCount += mesh.drawState().indexCount();
                }
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
                MeshData mesh = rendered.get(layers[layerIndex]);
                if (!isSupported(mesh)) {
                    continue;
                }
                MeshData.DrawState drawState = mesh.drawState();
                ByteBuffer sourceVertices = mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
                ByteBuffer sourceIndices = mesh.indexBuffer().duplicate().order(ByteOrder.nativeOrder());
                VertexFormat format = drawState.format();
                int stride = format.getVertexSize();
                int positionOffset = format.getOffset(VertexFormatElement.POSITION);
                int colorOffset = format.getOffset(VertexFormatElement.COLOR);
                int uvOffset = format.getOffset(VertexFormatElement.UV0);
                for (int vertex = 0; vertex < drawState.vertexCount(); vertex++) {
                    int offset = sourceVertices.position() + vertex * stride;
                    vertices.putShort(packPosition(sourceVertices.getFloat(offset + positionOffset)));
                    vertices.putShort(packPosition(sourceVertices.getFloat(offset + positionOffset + Float.BYTES)));
                    vertices.putShort(packPosition(sourceVertices.getFloat(offset + positionOffset + 2 * Float.BYTES)));
                    vertices.putShort((short) 0);
                    vertices.putInt(sourceVertices.getInt(offset + colorOffset));
                    vertices.putShort(packUnorm16(sourceVertices.getFloat(offset + uvOffset)));
                    vertices.putShort(packUnorm16(sourceVertices.getFloat(offset + uvOffset + Float.BYTES)));
                }
                int indexSize = drawState.indexType().bytes;
                for (int index = 0; index < drawState.indexCount(); index++) {
                    int sourceIndex = sourceIndices.position() + index * indexSize;
                    int value = indexSize == Short.BYTES
                            ? Short.toUnsignedInt(sourceIndices.getShort(sourceIndex))
                            : sourceIndices.getInt(sourceIndex);
                    indices.putInt(value + vertexBase);
                }
                layerIndexCounts[layerIndex] = drawState.indexCount();
                vertexBase += drawState.vertexCount();
            }
            vertices.flip();
            indices.flip();
            publish(captureGeneration, captureVersion, new SectionMesh(key, origin.getX(), origin.getY(),
                    origin.getZ(), vertices, vertexBase, indices, layerIndexCounts));
        } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
            Direct.LOGGER.error("Could not extract vanilla terrain mesh for section {}.", sectionPos, exception);
            publish(captureGeneration, captureVersion, SectionMesh.empty(key));
        }
    }

    /** Seção saiu da distância/foi reposicionada: remove e invalida compilações em andamento. */
    public static void remove(SectionPos sectionPos) {
        if (!captureEnabled) {
            return;
        }
        long key = sectionPos.asLong();
        synchronized (LOCK) {
            VERSIONS.merge(key, 1L, Long::sum);
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
        captureEnabled = enabled;
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
        // A ordem precisa bater com kLayerAlphaCutoff no nativo.
        return new RenderType[] {RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout()};
    }

    private static boolean isSupported(MeshData mesh) {
        return mesh != null && mesh.drawState().format() == DefaultVertexFormat.BLOCK
                && mesh.drawState().mode() == VertexFormat.Mode.QUADS;
    }

    private static void publish(long captureGeneration, long captureVersion, SectionMesh mesh) {
        synchronized (LOCK) {
            if (generation != captureGeneration || VERSIONS.getOrDefault(mesh.key(), 0L) != captureVersion) {
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