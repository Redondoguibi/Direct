package com.redondoguibi.direct;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NativeLibraryTest {
    @Test
    void nativeRendererIsRestrictedToWindowsX64() {
        assertTrue(NativeLibrary.supportsPlatform("Windows 11", "amd64"));
        assertTrue(NativeLibrary.supportsPlatform("Windows 10", "x86_64"));
        assertFalse(NativeLibrary.supportsPlatform("Linux", "amd64"));
        assertFalse(NativeLibrary.supportsPlatform("Windows 11", "aarch64"));
        assertFalse(NativeLibrary.supportsPlatform(null, "amd64"));
        assertFalse(NativeLibrary.supportsPlatform("Windows 11", null));
    }
}
