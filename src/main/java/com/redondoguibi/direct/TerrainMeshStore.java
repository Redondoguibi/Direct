package com.redondoguibi.direct;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

public final class TerrainMeshStore {
    private static final int OUTPUT_VERTEX_SIZE = 24;
    private static final Object LOCK = new Object();
    private static final ConcurrentLinkedQueue<SectionMesh> UPDATES = new ConcurrentLinkedQueue<>();
    private static final Map<Long, SectionMesh> CURRENT = new HashMap<>();
    private static volatile long generation;
    private static volatile boolean captureEnabled;

    private TerrainMeshStore() {
    }

    public static void capture(SectionPos sectionPos, SectionCompiler.Results results) {
        if (!captureEnabled || !ClientConfig.ENABLE_DX12.get()) {
            return;
        }
        long captureGeneration = generation;
        try {
            long vertexCount = 0;
            long indexCount = 0;
            Map<RenderType, MeshData> layers = results.renderedLayers;
            for (RenderType layer : supportedLayers()) {
                MeshData mesh = layers.get(layer);
                if (isSupported(mesh)) {
                    vertexCount += mesh.drawState().vertexCount();
                    indexCount += mesh.drawState().indexCount();
                }
            }
            if (vertexCount == 0 || indexCount == 0
                    || vertexCount > Integer.MAX_VALUE / OUTPUT_VERTEX_SIZE
                    || indexCount > Integer.MAX_VALUE / Integer.BYTES) {
                BlockPos origin = sectionPos.origin();
                publish(captureGeneration, new SectionMesh(sectionPos.asLong(), origin.getX(), origin.getY(),
                        origin.getZ(), null, 0, null, 0));
                return;
            }

            ByteBuffer vertices = ByteBuffer.allocateDirect((int) vertexCount * OUTPUT_VERTEX_SIZE)
                    .order(ByteOrder.nativeOrder());
            ByteBuffer indices = ByteBuffer.allocateDirect((int) indexCount * Integer.BYTES)
                    .order(ByteOrder.nativeOrder());
            BlockPos origin = sectionPos.origin();
            int vertexBase = 0;
            for (RenderType layer : supportedLayers()) {
                MeshData mesh = layers.get(layer);
                if (!isSupported(mesh)) {
                    continue;
                }
                MeshData.DrawState drawState = mesh.drawState();
                ByteBuffer sourceVertices = mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
                ByteBuffer sourceIndices = mesh.indexBuffer().duplicate().order(ByteOrder.nativeOrder());
                int stride = drawState.format().getVertexSize();
                int positionOffset = drawState.format().getOffset(VertexFormatElement.POSITION);
                int colorOffset = drawState.format().getOffset(VertexFormatElement.COLOR);
                int uvOffset = drawState.format().getOffset(VertexFormatElement.UV0);
                for (int vertex = 0; vertex < drawState.vertexCount(); vertex++) {
                    int offset = sourceVertices.position() + vertex * stride;
                    vertices.putFloat(sourceVertices.getFloat(offset + positionOffset) + origin.getX());
                    vertices.putFloat(sourceVertices.getFloat(offset + positionOffset + Float.BYTES) + origin.getY());
                    vertices.putFloat(sourceVertices.getFloat(offset + positionOffset + 2 * Float.BYTES) + origin.getZ());
                    vertices.putInt(sourceVertices.getInt(offset + colorOffset));
                    vertices.putFloat(sourceVertices.getFloat(offset + uvOffset));
                    vertices.putFloat(sourceVertices.getFloat(offset + uvOffset + Float.BYTES));
                }

                int indexSize = drawState.indexType().bytes;
                for (int index = 0; index < drawState.indexCount(); index++) {
                    int sourceIndex = sourceIndices.position() + index * indexSize;
                    int value = indexSize == Short.BYTES
                            ? Short.toUnsignedInt(sourceIndices.getShort(sourceIndex))
                            : sourceIndices.getInt(sourceIndex);
                    indices.putInt(value + vertexBase);
                }
                vertexBase += drawState.vertexCount();
            }
            vertices.flip();
            indices.flip();
            if (vertices.hasRemaining() && indices.hasRemaining()) {
                publish(captureGeneration, new SectionMesh(sectionPos.asLong(), origin.getX(), origin.getY(),
                        origin.getZ(), vertices, vertexBase, indices, indices.remaining() / Integer.BYTES));
            } else {
                publish(captureGeneration, new SectionMesh(sectionPos.asLong(), origin.getX(), origin.getY(),
                        origin.getZ(), null, 0, null, 0));
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
            Direct.LOGGER.error("Could not extract vanilla terrain mesh for section {}.", sectionPos, exception);
            BlockPos origin = sectionPos.origin();
            publish(captureGeneration, new SectionMesh(sectionPos.asLong(), origin.getX(), origin.getY(),
                    origin.getZ(), null, 0, null, 0));
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            generation++;
            CURRENT.clear();
            UPDATES.clear();
        }
        NativeDx12.clearTerrain();
    }

    public static void setCaptureEnabled(boolean enabled) {
        captureEnabled = enabled;
    }

    public static void resendAll() {
        synchronized (LOCK) {
            UPDATES.clear();
            UPDATES.addAll(CURRENT.values());
        }
    }

    public static void uploadPending() {
        SectionMesh update;
        while ((update = UPDATES.poll()) != null) {
            if (update.vertices == null) {
                NativeDx12.removeTerrainSection(update.key);
            } else {
                NativeDx12.updateTerrainSection(update.key, update.sectionX, update.sectionY, update.sectionZ,
                        update.vertices, update.vertexCount, update.indices, update.indexCount);
            }
        }
    }

    private static RenderType[] supportedLayers() {
        return new RenderType[] {RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout()};
    }

    private static boolean isSupported(MeshData mesh) {
        return mesh != null && mesh.drawState().format() == DefaultVertexFormat.BLOCK
                && mesh.drawState().mode() == com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS;
    }

    private static void publish(long captureGeneration, SectionMesh mesh) {
        synchronized (LOCK) {
            if (generation != captureGeneration) {
                return;
            }
            if (mesh.vertices == null) {
                CURRENT.remove(mesh.key);
            } else {
                CURRENT.put(mesh.key, mesh);
            }
            UPDATES.add(mesh);
        }
    }

    private record SectionMesh(long key, int sectionX, int sectionY, int sectionZ,
            ByteBuffer vertices, int vertexCount, ByteBuffer indices, int indexCount) {
    }
}
