package com.redondoguibi.direct;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/** Diagnostic snapshot of inputs already accepted by the hybrid renderer. Accessed on the render thread. */
final class Phase0Capture {
    static final boolean ENABLED = Boolean.getBoolean("direct.phase0.capture");
    private static final Map<Long, Section> sections = new HashMap<>();
    private record Section(int x, int y, int z, ByteBuffer vertices, ByteBuffer indices, int[] counts) {}
    private static ByteBuffer[] atlas;
    private static int atlasWidth, atlasHeight;
    private static boolean bc7, finished;
    private static long firstFrame;

    static void section(long key, int x, int y, int z, ByteBuffer vertices, ByteBuffer indices, int[] counts) {
        if (finished) return;
        if (vertices == null) sections.remove(key);
        else sections.put(key, new Section(x, y, z, vertices.asReadOnlyBuffer(), indices.asReadOnlyBuffer(), counts.clone()));
    }

    static void clear() {
        sections.clear();
        firstFrame = 0;
    }

    static void atlas(ByteBuffer[] levels, int w, int h, boolean compressed) {
        if (finished) return;
        atlasWidth = w;
        atlasHeight = h;
        bc7 = compressed;
        atlas = new ByteBuffer[levels.length];
        for (int i = 0; i < levels.length; i++) {
            atlas[i] = ByteBuffer.allocate(levels[i].remaining()).put(levels[i].duplicate()).flip();
        }
    }

    static void patch(ByteBuffer pixels, int level, int x, int y, int w, int h) {
        if (finished || atlas == null) return;
        int unit = bc7 ? 4 : 1;
        int bytes = bc7 ? 16 : 4;
        int row = ((Math.max(1, atlasWidth >> level) + unit - 1) / unit) * bytes;
        int length = ((w + unit - 1) / unit) * bytes;
        int offset = (y / unit) * row + (x / unit) * bytes;
        for (int i = 0; i < (h + unit - 1) / unit; i++) {
            atlas[level].put(offset + row * i, pixels, pixels.position() + length * i, length);
        }
    }

    static void frame(int w, int h, long[] keys, int count, int fallback, ByteBuffer light,
            float[] matrix, double x, double y, double z, float distance, String geometry, float[] options,
            float[] effects, float[] materials, String[] passes, float[] uniforms) {
        if (finished || atlas == null || count == 0 || fallback != 0 || geometry.isEmpty()) return;
        if (firstFrame == 0) firstFrame = System.nanoTime();
        if (System.nanoTime() - firstFrame < 60_000_000_000L) return;
        var directory = Path.of("logs");
        var output = directory.resolve("phase0-scene.bin");
        Path temp = null;
        try {
            if (Files.exists(output)) throw new IOException("Use a fresh benchmark case");
            Files.createDirectories(directory);
            temp = Files.createTempFile(directory, "phase0-scene-", ".tmp");
            try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                out.writeInt(0x44583031); // DX01
                out.writeInt(w);
                out.writeInt(h);
                out.writeDouble(x);
                out.writeDouble(y);
                out.writeDouble(z);
                out.writeFloat(distance);
                out.writeBoolean(ClientConfig.FACE_CULLING.get());
                out.writeBoolean(ClientConfig.USE_EXECUTE_INDIRECT.get());
                out.writeInt(ClientConfig.UPLOAD_RING_BUFFER_MIB.get());
                out.writeInt(ClientConfig.TERRAIN_ARENA_PAGE_MIB.get());
                out.writeInt(ClientConfig.MAX_TERRAIN_ARENA_MIB.get());
                floats(out, matrix);
                string(out, geometry);
                floats(out, options);
                floats(out, effects);
                floats(out, materials);
                out.writeInt(passes.length);
                for (var pass : passes) string(out, pass);
                floats(out, uniforms);
                out.writeInt(atlasWidth);
                out.writeInt(atlasHeight);
                out.writeBoolean(bc7);
                out.writeInt(atlas.length);
                for (var mip : atlas) buffer(out, mip);
                buffer(out, light);
                out.writeLong(NativeDx12.faceCullingStats()[1]);
                out.writeInt(count);
                for (int i = 0; i < count; i++) {
                    var mesh = sections.get(keys[i]);
                    if (mesh == null) throw new IOException("Missing visible section " + keys[i]);
                    out.writeLong(keys[i]);
                    out.writeInt(mesh.x);
                    out.writeInt(mesh.y);
                    out.writeInt(mesh.z);
                    for (int indexCount : mesh.counts) out.writeInt(indexCount);
                    buffer(out, mesh.vertices);
                    buffer(out, mesh.indices);
                }
            }
            Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE);
            Direct.LOGGER.info("Phase 0 captured {} sections at {}, {}, {} into {}", count, x, y, z, output);
        } catch (IOException ex) {
            Direct.LOGGER.error("Phase 0 scene capture failed", ex);
        } finally {
            finished = true;
            sections.clear();
            atlas = null;
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ex) { Direct.LOGGER.warn("Phase 0 cleanup", ex); }
        }
    }

    private static void floats(DataOutputStream out, float[] array) throws IOException {
        out.writeInt(array.length);
        for (float value : array) out.writeFloat(value);
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        buffer(out, ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void buffer(DataOutputStream out, ByteBuffer data) throws IOException {
        out.writeInt(data.remaining());
        var bytes = data.duplicate();
        var channel = Channels.newChannel(out);
        while (bytes.hasRemaining()) channel.write(bytes);
    }
}
