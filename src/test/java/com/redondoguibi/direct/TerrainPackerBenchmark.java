package com.redondoguibi.direct;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Java vs C++ V2 vertex packing for section-sized layers, single thread, after JIT warm-up.
 * Alternates Java/native/native/Java so drift between blocks shows. Measures the converter only,
 * not chunk meshing as a whole and not FPS.
 */
public final class TerrainPackerBenchmark {
    public static void main(String[] args) {
        System.load(args[0]);
        int vertices = args.length > 1 ? Integer.parseInt(args[1]) : 8192;
        SplittableRandom random = new SplittableRandom(7);
        ByteBuffer source = NativeTerrainPackerTest.mesh(vertices, random, false);
        byte[] materials = NativeTerrainPackerTest.materials(vertices, random);
        ByteBuffer destination = ByteBuffer.allocateDirect(vertices * 20).order(ByteOrder.nativeOrder());
        String[] order = {"java", "native", "native", "java"};
        for (int warm = 0; warm < 3000; warm++) { run(true, destination, source, vertices, materials); run(false, destination, source, vertices, materials); }
        for (String mode : order) {
            double[] samples = new double[200];
            for (int s = 0; s < samples.length; s++) {
                long start = System.nanoTime();
                for (int r = 0; r < 20; r++) run(mode.equals("java"), destination, source, vertices, materials);
                samples[s] = (System.nanoTime() - start) / 20.0 / vertices;
            }
            Arrays.sort(samples);
            System.out.printf(Locale.ROOT, "PACK mode=%s vertices=%d nsPerVertexP50=%.3f p95=%.3f%n", mode, vertices, samples[100], samples[190]);
        }
    }

    private static void run(boolean java, ByteBuffer destination, ByteBuffer source, int count, byte[] materials) {
        destination.clear();
        if (java) {
            TerrainBufferPacker.packVertices20Java(destination, source, count, NativeTerrainPackerTest.STRIDE, NativeTerrainPackerTest.POSITION,
                    NativeTerrainPackerTest.COLOR, NativeTerrainPackerTest.UV, NativeTerrainPackerTest.LIGHT, NativeTerrainPackerTest.NORMAL, materials);
        } else {
            NativeDx12.packTerrainVertices20(destination, 0, source, 0, count, NativeTerrainPackerTest.STRIDE, NativeTerrainPackerTest.POSITION,
                    NativeTerrainPackerTest.COLOR, NativeTerrainPackerTest.UV, NativeTerrainPackerTest.LIGHT, NativeTerrainPackerTest.NORMAL, materials);
        }
    }
}
