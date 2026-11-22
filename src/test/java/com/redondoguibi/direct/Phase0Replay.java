package com.redondoguibi.direct;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/** Standalone replay. The JVM loads the Direct DLL without creating a Minecraft client or a GL context. */
public final class Phase0Replay {
    private static final int WINDOW_SECONDS = 60;

    public static void main(String[] args) throws IOException {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(Path.of(args[1]))))) {
            if (in.readInt() != 0x44583031) throw new IOException("Expected DX01 capture");
            int w = in.readInt(), h = in.readInt();
            // The harness asks for 1920x1080; Windows clamps the window to the work area (1920x1061 here). Every
            // stack in docs/comparacao-sodium-iris.md ran at that same size, so the replay keeps the captured one.
            if (w < 1280 || h < 720) throw new IOException("Unexpected capture size " + w + "x" + h);
            System.out.printf(Locale.ROOT, "size=%dx%d%n", w, h);
            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
            float distance = in.readFloat();
            boolean cull = in.readBoolean(), indirect = in.readBoolean();
            Phase0FrameNative.initialize(w, h, in.readInt(), in.readInt(), in.readInt());
            try {
                var matrix = floats(in, 28);
                NativeDx12.configurePackGeometry(string(in));
                NativeDx12.setPackGeometryOptions(floats(in, 32), false, false);
                NativeDx12.setTerrainEffects(floats(in, 10));
                NativeDx12.setMaterialEffects(floats(in, 4));
                String[] passes = new String[count(in, 16)];
                for (int i = 0; i < passes.length; i++) passes[i] = string(in);
                NativeDx12.configureWorldPasses(passes);
                float[] uniforms = floats(in, 252 * passes.length);
                int aw = in.readInt(), ah = in.readInt();
                boolean bc7 = in.readBoolean();
                var levels = new ByteBuffer[count(in, 32)];
                for (int i = 0; i < levels.length; i++) levels[i] = buffer(in);
                NativeDx12.updateTerrainAtlas(levels, aw, ah, bc7, 1, bc7);
                NativeDx12.updateLightmap(buffer(in));
                long expectedIndices = in.readLong();
                long[] keys = new long[count(in, 65536)];
                for (int i = 0; i < keys.length; i++) {
                    keys[i] = in.readLong();
                    int sx = in.readInt(), sy = in.readInt(), sz = in.readInt();
                    var layers = new int[4];
                    for (int j = 0; j < layers.length; j++) layers[j] = in.readInt();
                    var vertices = buffer(in);
                    var indices = buffer(in);
                    if (vertices.remaining() % 20 != 0 || Arrays.stream(layers).asLongStream().sum() * 4 != indices.remaining())
                        throw new IOException("Damaged section " + keys[i]);
                    NativeDx12.updateTerrainSection(keys[i], sx, sy, sz, vertices, vertices.remaining()/20, indices, layers);
                    if (NativeDx12.consumeArenaRejection()) throw new IOException("Replay terrain arena exhausted");
                }
                if (in.read() != -1) throw new IOException("Unexpected trailing capture data");
                NativeDx12.setFaceCullingEnabled(cull);
                NativeDx12.setVisibleSections(keys, keys.length, true);
                System.out.printf(Locale.ROOT, "adapter=%s camera=%.3f,%.3f,%.3f sections=%d indices=%d passes=%d distance=%.1f%n",
                        NativeDx12.interopAdapterName(), x, y, z, keys.length, expectedIndices, passes.length, distance);
                System.out.println("window,frames,wallMs,gpuMeanMs,gpuP50Ms,gpuP95Ms,terrainMs,worldMs,guiMs");
                for (int window = 1; window <= 3; window++) {
                    var times = new ArrayList<Double>();
                    var totals = new double[4];
                    long start = System.nanoTime(), elapsed;
                    do {
                        double[] frame = Phase0FrameNative.frame(matrix, x, y, z, distance, uniforms, indirect);
                        if ((long)frame[5] != expectedIndices || expectedIndices <= 0)
                            throw new IOException("Submitted indices differ from capture: " + frame[5] + " vs " + expectedIndices);
                        for (int i = 0; i < totals.length; i++) totals[i] += frame[i];
                        times.add(frame[0]);
                        elapsed = System.nanoTime() - start;
                    } while (elapsed < WINDOW_SECONDS * 1_000_000_000L);
                    times.sort(Double::compare);
                    int n = times.size();
                    System.out.printf(Locale.ROOT, "%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f%n",
                            window, n, elapsed / 1e6 / n, totals[0] / n, times.get(n / 2),
                            times.get((int)Math.ceil(.95 * n) - 1), totals[1] / n, totals[2] / n, totals[3] / n);
                }
                long covered = Phase0FrameNative.coveredDepthPixels();
                if (covered <= 0) throw new IOException("Replay produced no terrain depth");
                System.out.println("coveredDepthPixels=" + covered);
            } finally {
                NativeDx12.shutdownInterop();
            }
        }
    }

    private static int count(DataInputStream in, int max) throws IOException {
        int value = in.readInt();
        if (value < 1 || value > max) throw new IOException("Invalid capture size " + value);
        return value;
    }

    private static byte[] bytes(DataInputStream in, int limit) throws IOException {
        byte[] bytes = new byte[count(in, limit)];
        in.readFully(bytes);
        return bytes;
    }

    private static ByteBuffer buffer(DataInputStream in) throws IOException {
        byte[] data = bytes(in, 128 << 20);
        return ByteBuffer.allocateDirect(data.length).order(ByteOrder.nativeOrder()).put(data).flip();
    }

    private static String string(DataInputStream in) throws IOException {
        return new String(bytes(in, 4 << 20), StandardCharsets.UTF_8);
    }

    private static float[] floats(DataInputStream in, int expected) throws IOException {
        if (in.readInt() != expected) throw new IOException("Invalid uniform count");
        var values = new float[expected];
        for (int i = 0; i < expected; i++) values[i] = in.readFloat();
        return values;
    }
}
