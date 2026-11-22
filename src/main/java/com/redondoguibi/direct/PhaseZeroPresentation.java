package com.redondoguibi.direct;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.glfw.GLFWVidMode;

/** One-shot diagnostic for the existing NeoForge window. Enabled by the mixin plugin in a disposable client. */
public final class PhaseZeroPresentation {
    private static boolean started;

    private PhaseZeroPresentation() {}

    private static native long open(long hwnd);
    private static native boolean frame(long probe, int w, int h, int phase, boolean vsync, boolean sample);
    private static native void close(long probe);

    public static boolean takeOver(Window window) {
        if (started) return true; // Suppress recursive GL swaps from window callbacks and the shutdown frame.
        Minecraft game = Minecraft.getInstance();
        if (game == null || !game.isRunning() || game.getOverlay() != null || game.screen == null || game.level != null)
            return false;
        started = true;
        try {
            exercise(window.getWindow());
            Direct.LOGGER.info("Direct phase0 presentation PASS");
        } catch (Exception | LinkageError ex) {
            Direct.LOGGER.error("Direct phase0 presentation FAIL", ex);
        } finally {
            game.stop();
        }
        return true;
    }

    private static void exercise(long window) throws Exception {
        if (GLFW.glfwGetCurrentContext() != window || GLFW.glfwGetWindowMonitor(window) != 0)
            throw new IllegalStateException("Start the DXGI probe in a window with the early GL context current");
        long monitor = GLFW.glfwGetPrimaryMonitor();
        GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
        if (mode == null) throw new IllegalStateException("No monitor video mode");
        int[] x = {0}, y = {0}, width = {0}, height = {0};
        GLFW.glfwGetWindowPos(window, x, y);
        GLFW.glfwGetWindowSize(window, width, height);
        long hwnd = GLFWNativeWin32.glfwGetWin32Window(window);
        Direct.LOGGER.info("Direct phase0 presentation hwnd={} GL={}.{} window={}x{}", Long.toHexString(hwnd),
                GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_CONTEXT_VERSION_MAJOR),
                GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_CONTEXT_VERSION_MINOR), width[0], height[0]);
        NativeLibrary.load();
        long probe = open(hwnd);
        boolean visible = true;
        try {
            GLFW.glfwFocusWindow(window);
            visible &= stage(window, probe, 0, "window", false, width[0], height[0]);
            for (int i = 0; i < 2; i++) {
                int stage = i * 3 + 1;
                int w = Math.max(320, width[0] * 3 / 4), h = Math.max(240, height[0] * 3 / 4);
                GLFW.glfwSetWindowSize(window, w, h);
                visible &= stage(window, probe, stage, "resize", true, w, h);
                GLFW.glfwSetWindowMonitor(window, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate());
                if (GLFW.glfwGetWindowMonitor(window) != monitor) throw new IllegalStateException("Fullscreen failed");
                visible &= stage(window, probe, stage + 1, "fullscreen", i != 0, mode.width(), mode.height());
                GLFW.glfwSetWindowMonitor(window, 0, x[0], y[0], width[0], height[0], GLFW.GLFW_DONT_CARE);
                if (GLFW.glfwGetWindowMonitor(window) != 0) throw new IllegalStateException("Return to window failed");
                visible &= stage(window, probe, stage + 2, "restored", false, width[0], height[0]);
            }
        } finally {
            close(probe);
            if (GLFW.glfwGetWindowMonitor(window) != 0)
                GLFW.glfwSetWindowMonitor(window, 0, x[0], y[0], width[0], height[0], GLFW.GLFW_DONT_CARE);
        }
        if (!visible) throw new IllegalStateException("Desktop pixels did not match both DXGI color bars in every stage");
    }

    private static boolean stage(long window, long probe, int phase, String name, boolean vsync, int w, int h) {
        final int frames = 24;
        int[] fw = {0}, fh = {0}, ww = {0}, wh = {0};
        boolean visible = false;
        for (int i = 0; i < frames; i++) {
            GLFW.glfwPollEvents();
            if (GLFW.glfwWindowShouldClose(window)) throw new IllegalStateException("Window closed during probe");
            GLFW.glfwGetWindowSize(window, ww, wh);
            GLFW.glfwGetFramebufferSize(window, fw, fh);
            if (ww[0] != w || wh[0] != h || GLFW.glfwGetCurrentContext() != window)
                throw new IllegalStateException("GLFW geometry or current GL context changed unexpectedly");
            visible = frame(probe, fw[0], fh[0], phase, vsync, i == frames - 1);
        }
        Direct.LOGGER.info("Direct phase0 presentation stage={} mode={} framebuffer={}x{} interval={} frames={} desktopPixels={}",
                phase, name, fw[0], fh[0], vsync ? 1 : 0, frames, visible);
        return visible;
    }
}
