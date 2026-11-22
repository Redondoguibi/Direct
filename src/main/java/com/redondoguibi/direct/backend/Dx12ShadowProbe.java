package com.redondoguibi.direct.backend;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.redondoguibi.direct.Direct;
import com.redondoguibi.direct.mixin.NativeImageAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.minecraft.client.gui.screens.options.controls.ControlsScreen;

/**
 * Pixel comparison of OpenGL's main render target against the DX12 shadow copy at the end of each frame.
 * With {@code -Ddirect.phase1.probe=true} it walks a fixed list of menus, writes the images to {@code phase1/} in the
 * game directory and stops the client.
 */
final class Dx12ShadowProbe {
    private static final boolean PROBE = Boolean.getBoolean("direct.phase1.probe");
    private static final int WARMUP = 40, SAMPLES = 3;

    private record Step(String name, Supplier<Screen> screen) {}
    private static final List<Step> STEPS = new ArrayList<>();
    private static int step = -1, frames, samples;
    private static boolean failed;
    private static TitleScreen title;
    private static final StringBuilder summary = new StringBuilder();

    private Dx12ShadowProbe() {}

    private static boolean verifyNext;
    private static String verifyName;

    /** Present mode: the frame just compared is also checked on the desktop after it is presented. */
    static boolean verifyDesktop() { return PROBE && verifyNext; }

    static void desktop(int matches) {
        verifyNext = false;
        boolean pass = matches >= 60; // a cursor or a moving pixel may cover a few samples
        failed |= !pass;
        summary.append(String.format(Locale.ROOT, " %s-desktop(%d/64)", verifyName, matches));
        Direct.LOGGER.info("Direct phase1 present check {} desktop pixels matching DX12: {}/64 {}", verifyName, matches, pass ? "PASS" : "FAIL");
    }

    static void frame() {
        if (!PROBE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getOverlay() != null || mc.screen == null) return;
        if (step < 0) {
            if (!(mc.screen instanceof TitleScreen screen)) return;
            title = screen;
            STEPS.add(new Step("title", () -> title));
            STEPS.add(new Step("options", () -> new OptionsScreen(title, mc.options)));
            STEPS.add(new Step("video", () -> new VideoSettingsScreen(title, mc, mc.options)));
            STEPS.add(new Step("language", () -> new LanguageSelectScreen(title, mc.options, mc.getLanguageManager())));
            STEPS.add(new Step("sounds", () -> new SoundOptionsScreen(title, mc.options)));
            STEPS.add(new Step("controls", () -> new ControlsScreen(title, mc.options)));
            step = 0;
            frames = 0;
            samples = 0;
        }
        if (++frames < WARMUP) return;
        String name = STEPS.get(step).name + "-" + samples;
        if (Dx12Shadow.presenting()) { verifyNext = true; verifyName = name; }
        if (!Dx12Shadow.SKIP_GL) compare(mc, name);
        else if (name.endsWith("-0")) dx12Only(mc, name);
        if (++samples < SAMPLES) return;
        if (++step >= STEPS.size()) {
            Direct.LOGGER.info("Direct phase1 shadow summary {}:{}", failed ? "FAIL" : "PASS", summary);
            mc.stop();
            return;
        }
        // Reset first: opening a screen may render frames before setScreen returns.
        frames = 0;
        samples = 0;
        mc.setScreen(STEPS.get(step).screen.get());
    }

    /** OpenGL is skipped: there is nothing to compare against, but the DX12 image is written for inspection. */
    private static void dx12Only(Minecraft mc, String name) {
        var target = mc.getMainRenderTarget();
        int width = target.width, height = target.height;
        ByteBuffer dx = MemoryUtil.memAlloc(width * height * 4);
        try {
            Dx12Shadow.readback(target.getColorTextureId(), dx);
            Path directory = mc.gameDirectory.toPath().resolve("phase1");
            Files.createDirectories(directory);
            save(directory.resolve(name + "-dx12only.png"), width, height, dx);
        } catch (IOException | RuntimeException ex) {
            failed = true;
            Direct.LOGGER.error("Direct phase1 dx12-only image {} failed", name, ex);
        } finally {
            MemoryUtil.memFree(dx);
        }
    }

    private static void compare(Minecraft mc, String name) {
        var target = mc.getMainRenderTarget();
        int texture = target.getColorTextureId(), width = target.width, height = target.height;
        int bytes = width * height * 4;
        ByteBuffer gl = MemoryUtil.memAlloc(bytes), dx = MemoryUtil.memAlloc(bytes);
        try {
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GlStateManager._bindTexture(texture);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, gl);
            GlStateManager._bindTexture(previous);
            Dx12Shadow.readback(texture, dx);
            int max = 0;
            long differing = 0, over2 = 0, over8 = 0;
            for (int i = 0; i < bytes; i += 4) {
                int pixel = 0;
                for (int c = 0; c < 3; c++) pixel = Math.max(pixel, Math.abs((gl.get(i + c) & 255) - (dx.get(i + c) & 255)));
                max = Math.max(max, pixel);
                if (pixel > 0) differing++;
                if (pixel > 2) over2++;
                if (pixel > 8) over8++;
            }
            long pixels = (long) width * height;
            boolean pass = over2 * 1000 < pixels; // fewer than 0.1% of pixels more than 2/255 apart
            failed |= !pass;
            String line = String.format(Locale.ROOT, " %s(max=%d, differing=%.3f%%, over2=%.3f%%, over8=%.3f%%, draws=%d)",
                    name, max, 100.0 * differing / pixels, 100.0 * over2 / pixels, 100.0 * over8 / pixels, Dx12Shadow.draws);
            summary.append(line);
            Direct.LOGGER.info("Direct phase1 shadow check {}x{}{} {}", width, height, line, pass ? "PASS" : "FAIL");
            if (name.endsWith("-0")) write(mc, name, width, height, gl, dx);
        } catch (RuntimeException ex) {
            failed = true;
            summary.append(' ').append(name).append("(error: ").append(ex.getMessage()).append(')');
            Direct.LOGGER.error("Direct phase1 shadow check {} failed", name, ex);
        } finally {
            MemoryUtil.memFree(gl);
            MemoryUtil.memFree(dx);
        }
    }

    /** OpenGL, DX12 and an 8x amplified difference image, flipped to the usual top-down orientation. */
    private static void write(Minecraft mc, String name, int width, int height, ByteBuffer gl, ByteBuffer dx) {
        Path directory = mc.gameDirectory.toPath().resolve("phase1");
        ByteBuffer diff = MemoryUtil.memAlloc(width * height * 4);
        try {
            Files.createDirectories(directory);
            for (int i = 0; i < width * height * 4; i += 4) {
                for (int c = 0; c < 3; c++)
                    diff.put(i + c, (byte) Math.min(255, 8 * Math.abs((gl.get(i + c) & 255) - (dx.get(i + c) & 255))));
                diff.put(i + 3, (byte) 255);
            }
            save(directory.resolve(name + "-gl.png"), width, height, gl);
            save(directory.resolve(name + "-dx12.png"), width, height, dx);
            save(directory.resolve(name + "-diff.png"), width, height, diff);
            if (Dx12Shadow.TRACE) Files.writeString(directory.resolve(name + "-trace.txt"), Dx12Shadow.trace);
        } catch (IOException ex) {
            Direct.LOGGER.warn("Direct phase1 shadow could not write images for {}", name, ex);
        } finally {
            MemoryUtil.memFree(diff);
        }
    }

    private static void save(Path path, int width, int height, ByteBuffer pixels) throws IOException {
        try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false)) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(pixels), ((NativeImageAccessor) (Object) image).direct$pixels(), (long) width * height * 4);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
                image.setPixelRGBA(x, y, image.getPixelRGBA(x, y) | 0xFF000000);
            image.flipY();
            image.writeToFile(path);
        }
    }
}
