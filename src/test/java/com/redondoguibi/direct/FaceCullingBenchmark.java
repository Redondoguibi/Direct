package com.redondoguibi.direct;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;

import org.joml.Matrix4f;

/**
 * Real native frames over a heightmap terrain shaped like vanilla output after hidden-face
 * removal: exposed tops and the side faces between columns of different heights, no bottoms.
 * Alternates face culling on/off/off/on in one process to expose drift between runs.
 * Synthetic: it isolates the terrain pass and does not predict Minecraft FPS.
 */
public final class FaceCullingBenchmark {
    private static int GRID = 16;           // GRID x GRID sections horizontally (args[1])
    private static final int WARMUP = 30, SAMPLES = 120;

    public static void main(String[] args) {
        System.load(args[0]);
        if (args.length > 1) GRID = Integer.parseInt(args[1]);
        double cameraHeight = args.length > 2 ? Double.parseDouble(args[2]) : 30;
        NativeDx12.initializeInterop(1920, 1080, true, 64, 16, 2048);
        try {
            NativeDx12.configureColorAlias(true);
            var atlas = ByteBuffer.allocateDirect(64);
            while (atlas.hasRemaining()) atlas.putInt(-1);
            NativeDx12.updateTerrainAtlas(new ByteBuffer[] {atlas.flip()}, 4, 4, false, 1);
            var light = ByteBuffer.allocateDirect(1024);
            while (light.hasRemaining()) light.putInt(-1);
            NativeDx12.updateLightmap(light.flip());

            long quads = 0;
            long[] keys = new long[GRID * GRID];
            for (int sx = 0; sx < GRID; sx++) {
                for (int sz = 0; sz < GRID; sz++) {
                    long key = sx * GRID + sz + 1;
                    keys[(int) key - 1] = key;
                    quads += uploadSection(key, sx * 16, sz * 16);
                }
            }
            // Player-height camera in the middle of the terrain, looking across it and slightly down.
            double cx = GRID * 8, cy = cameraHeight, cz = GRID * 8;
            float[] matrix = new float[28];
            new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, .05f, 512, true)
                    .lookAt(0, 0, 0, 1, -.35f, .6f, 0, 1, 0).get(matrix);
            matrix[20] = 500; matrix[21] = 600;
            for (int i = 24; i < 28; i++) matrix[i] = 1;
            NativeDx12.setVisibleSections(keys, keys.length, false);
            NativeDx12.setProfilingEnabled(true);

            System.out.printf(Locale.ROOT, "FACES sections=%d quads=%d%n", keys.length, quads);
            long frame = 0;
            for (boolean enabled : new boolean[] {true, false, false, true}) {
                NativeDx12.setFaceCullingEnabled(enabled);
                double[] gpu = new double[SAMPLES];
                double[] cpu = new double[SAMPLES];
                long[] stats = null;
                for (int i = 0; i < WARMUP + SAMPLES; i++, frame++) {
                    long start = System.nanoTime();
                    int status = NativeDx12.renderInterop(matrix, cx, cy, cz, 1024, frame, 0, new float[0], false, true);
                    long submitted = System.nanoTime();
                    if (status != 0 || !NativeDx12.waitInteropFrame(frame + 1)) throw new AssertionError("Frame failed");
                    if (i >= WARMUP) {
                        gpu[i - WARMUP] = NativeDx12.frameMetrics()[0];
                        cpu[i - WARMUP] = (submitted - start) / 1e6;
                    }
                    stats = NativeDx12.faceCullingStats();
                    NativeDx12.signalInteropFence(frame + 1);
                }
                Arrays.sort(gpu);
                Arrays.sort(cpu);
                System.out.printf(Locale.ROOT,
                        "FACES culling=%s visibleIndices=%d submittedIndices=%d fraction=%.3f draws=%.0f gpuP50=%.4f gpuP95=%.4f cpuSubmitP50=%.4f%n",
                        enabled, stats[0], stats[1], (double) stats[1] / Math.max(1, stats[0]),
                        NativeDx12.frameMetrics()[2], gpu[SAMPLES / 2], gpu[SAMPLES * 95 / 100], cpu[SAMPLES / 2]);
            }
        } finally {
            NativeDx12.shutdownInterop();
        }
    }

    private static int height(int x, int z) {
        double h = 7 + 4 * Math.sin(x * .11) + 3 * Math.cos(z * .09) + 2 * Math.sin((x + z) * .23);
        return Math.max(1, Math.min(15, (int) Math.round(h)));
    }

    /** One section of terrain columns at world (originX, 0, originZ); returns its quad count. */
    private static int uploadSection(long key, int originX, int originZ) {
        var faces = new java.util.ArrayList<float[]>(); // {x, y, z, face}: unit face at block (x,y,z)
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int h = height(originX + x, originZ + z);
                faces.add(new float[] {x, h - 1, z, 2}); // +Y top
                int[][] neighbours = {{1, 0, 1}, {-1, 0, 6}, {0, 1, 3}, {0, -1, 4}}; // dx, dz, face
                for (int[] n : neighbours) {
                    int other = height(originX + x + n[0], originZ + z + n[1]);
                    for (int y = other; y < h; y++) faces.add(new float[] {x, y, z, n[2]});
                }
            }
        }
        int quads = faces.size();
        var vertices = ByteBuffer.allocateDirect(quads * 4 * 20).order(ByteOrder.nativeOrder());
        var indices = ByteBuffer.allocateDirect(quads * 24).order(ByteOrder.nativeOrder());
        // Face corners with outward CCW winding: (p1 - p0) x (p2 - p0) = outward normal.
        float[][][] corners = {
            null,
            {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}},   // +X
            {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},   // +Y
            {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},   // +Z
            {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}},   // -Z
            null,
            {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},   // -X
        };
        int vertex = 0;
        for (float[] face : faces) {
            for (float[] c : corners[(int) face[3]]) {
                vertices.putShort((short) Math.round((face[0] + c[0] + 16) * 1024))
                        .putShort((short) Math.round((face[1] + c[1] + 16) * 1024))
                        .putShort((short) Math.round((face[2] + c[2] + 16) * 1024))
                        .putShort((short) 0xf0f0).putInt(-1).putShort((short) 32768).putShort((short) 32768)
                        .putInt(0x01007f00);
            }
            for (int i : new int[] {0, 1, 2, 2, 3, 0}) indices.putInt(vertex + i);
            vertex += 4;
        }
        NativeDx12.updateTerrainSection(key, originX, 0, originZ, vertices.flip(), vertex, indices.flip(),
                new int[] {quads * 6, 0, 0, 0});
        return quads;
    }
}
