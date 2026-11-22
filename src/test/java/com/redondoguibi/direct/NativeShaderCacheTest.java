package com.redondoguibi.direct;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** A second activation must reuse compiled shaders (and the PSO library when the driver supports one). */
@Tag("graphics")
@EnabledOnOs(OS.WINDOWS)
class NativeShaderCacheTest {
    @Test
    void secondInitializationLoadsShadersAndPipelinesFromDisk(@TempDir Path cache) throws Exception {
        System.load(Path.of("build/generated/nativeResources/META-INF/native/windows-x86_64/direct_renderer.dll").toAbsolutePath().toString());
        try {
            assertNotNull(NativeDx12.initializeInteropCached(32, 32, false, 16, 8, 64, cache.toString()));
            int[] first = NativeDx12.initializationCacheStats();
            boolean library = (NativeDx12.interopFeatureFlags() & 8) != 0;
            NativeDx12.shutdownInterop();
            assertEquals(0, first[0], "empty cache cannot hit");
            assertEquals(8, first[1], "all terrain permutations compiled once");
            try (var files = Files.list(cache.resolve("shaders"))) {
                assertEquals(8, files.filter(file -> file.toString().endsWith(".cso")).count());
            }

            assertNotNull(NativeDx12.initializeInteropCached(32, 32, false, 16, 8, 64, cache.toString()));
            int[] second = NativeDx12.initializationCacheStats();
            assertEquals(8, second[0], "every shader comes from the disk cache");
            assertEquals(0, second[1], "no shader is recompiled");
            if (library) assertEquals(1, second[2], "serialized PSO library is reused");

            // A corrupted entry is rejected and recompiled instead of failing initialization.
            NativeDx12.shutdownInterop();
            try (var files = Files.list(cache.resolve("shaders"))) {
                Path victim = files.findFirst().orElseThrow();
                Files.write(victim, new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25});
            }
            assertNotNull(NativeDx12.initializeInteropCached(32, 32, false, 16, 8, 64, cache.toString()));
            int[] third = NativeDx12.initializationCacheStats();
            assertEquals(7, third[0]);
            assertEquals(1, third[1]);
        } finally {
            NativeDx12.shutdownInterop();
        }
    }
}
