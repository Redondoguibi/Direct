package com.redondoguibi.direct.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DirectRenderServiceTest {
    private final DirectRenderService service = DirectRenderService.getInstance();

    @AfterEach
    void clearPasses() {
        for (DirectRenderPass pass : service.passes()) {
            service.unregisterPass(pass.id());
        }
    }

    @Test
    void ordersPassesAndRemovesThem() {
        service.registerPass(pass("example.late", 10));
        service.registerPass(pass("example.first", -2));

        assertEquals(List.of("example.first", "example.late"),
                service.passes().stream().map(DirectRenderPass::id).toList());
        assertTrue(service.unregisterPass("example.first"));
        assertFalse(service.unregisterPass("example.first"));
        assertEquals(List.of("example.late"), service.passes().stream().map(DirectRenderPass::id).toList());
    }

    @Test
    void reRegisteringSamePassMarksNativePipelinesDirty() {
        DirectRenderApi.consumeDirty();
        DirectRenderPass pass = pass("example.reload", 0);
        service.registerPass(pass);
        DirectRenderApi.consumeDirty();

        service.registerPass(pass);

        assertTrue(DirectRenderApi.consumeDirty());
    }

    @Test
    void rejectsInvalidIdsAndTooManyUniforms() {
        assertThrows(IllegalArgumentException.class, () -> service.registerPass(pass("../escape", 0)));
        DirectRenderPass invalidUniforms = new DirectRenderPass() {
            @Override
            public String id() {
                return "example.uniforms";
            }

            @Override
            public String pixelShaderSource() {
                return "float4 main(float4 position : SV_Position) : SV_Target0 { return 1; }";
            }

            @Override
            public float[] uniforms(DirectFrameContext frame) {
                return new float[DirectRenderApi.MAX_UNIFORM_FLOATS + 1];
            }
        };
        assertThrows(IllegalArgumentException.class, () -> DirectRenderApi.uniformValues(invalidUniforms,
                DirectRenderApi.frame(3, 800, 600, 1.5f, 128.0f, invalidUniforms.id(), 0)));
    }

    @Test
    void padsUniformsToStableShaderBufferLayout() {
        DirectRenderPass pass = new DirectRenderPass() {
            @Override
            public String id() {
                return "example.tint";
            }

            @Override
            public String pixelShaderSource() {
                return "float4 main(float4 position : SV_Position) : SV_Target0 { return 1; }";
            }

            @Override
            public float[] uniforms(DirectFrameContext frame) {
                assertEquals(2, frame.passIndex());
                assertEquals("example.tint", frame.passId());
                return new float[] {1.0f, 0.5f, 0.25f, 1.0f};
            }
        };
        float[] result = DirectRenderApi.uniformValues(pass,
                DirectRenderApi.frame(7, 1280, 720, 4.0f, 96.0f, pass.id(), 2));
        assertEquals(DirectRenderApi.MAX_UNIFORM_FLOATS, result.length);
        assertArrayEquals(new float[] {1.0f, 0.5f, 0.25f, 1.0f}, java.util.Arrays.copyOf(result, 4));
        assertEquals(0.0f, result[4]);

        float[] reused = new float[DirectRenderApi.MAX_UNIFORM_FLOATS];
        java.util.Arrays.fill(reused, 9.0f);
        DirectRenderApi.copyUniforms(pass, DirectRenderApi.frame(7, 1280, 720, 4.0f, 96.0f, pass.id(), 2),
                reused, 0);
        assertArrayEquals(new float[] {1.0f, 0.5f, 0.25f, 1.0f}, java.util.Arrays.copyOf(reused, 4));
        assertEquals(0.0f, reused[4]);
        assertEquals(0.0f, reused[DirectRenderApi.MAX_UNIFORM_FLOATS - 1]);
    }

    @Test
    void concurrentPassReplacementAndSnapshotsRemainBounded() throws Exception {
        int threadCount = 4;
        int iterations = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
        try {
            for (int thread = 0; thread < threadCount; thread++) {
                int order = thread;
                tasks.add(executor.submit(() -> {
                    start.await();
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        service.registerPass(pass("example.concurrent", order));
                        assertTrue(service.passes().size() <= DirectRenderApi.MAX_PASSES);
                    }
                    return null;
                }));
            }
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            for (java.util.concurrent.Future<?> task : tasks) {
                task.get();
            }
            assertEquals(1, service.passes().size());
        } finally {
            executor.shutdownNow();
        }
    }

    private static DirectRenderPass pass(String id, int order) {
        return new DirectRenderPass() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String pixelShaderSource() {
                return "float4 main(float4 position : SV_Position) : SV_Target0 { return 1; }";
            }

            @Override
            public int order() {
                return order;
            }
        };
    }
}
