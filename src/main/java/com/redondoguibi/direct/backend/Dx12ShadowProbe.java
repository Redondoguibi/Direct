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
 * game directory and stops the client. With {@code -Ddirect.phase2.world=true} as well, it waits for a quick-played
 * world to finish loading and walks fixed world scenes instead (camera angles, night, rain, F3, inventory, pause),
 * writing to {@code phase2/}.
 */
final class Dx12ShadowProbe {
    private static final boolean PROBE = Boolean.getBoolean("direct.phase1.probe");
    private static final boolean WORLD = PROBE && Boolean.getBoolean("direct.phase2.world");
    private static final int WARMUP = 40, SAMPLES = 3;
    private static final String DIRECTORY = WORLD ? "phase2" : "phase1";

    private record Step(String name, Supplier<Screen> screen) {}
    /** A world scene: entered once, then the camera is held at (yaw, pitch) until {@code settleMillis} have passed. */
    private record Scene(String name, Runnable enter, float pitch, long settleMillis) {}
    private static final List<Scene> SCENES = new ArrayList<>();
    private static long joined, entered, sceneFrames;
    private static float yaw;
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
        if (WORLD) { world(mc); return; }
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

    private static void world(Minecraft mc) {
        var player = mc.player;
        if (mc.getOverlay() != null || mc.level == null || player == null) return;
        long now = System.currentTimeMillis();
        if (step < 0) {
            if (mc.screen != null) return;
            if (joined == 0) joined = now;
            // Terrain first: every section compiled, at least 20 s in, or 120 s at most.
            boolean loaded = mc.levelRenderer.hasRenderedAllSections() && now - joined > 20_000
                    && (!com.redondoguibi.direct.ClientConfig.ENABLE_DX12.get() || com.redondoguibi.direct.Dx12GlInterop.terrainReplaced());
            if (!loaded && now - joined < 120_000) return;
            yaw = player.getYRot();
            var server = mc.getSingleplayerServer();
            Runnable none = () -> { };
            var clouds = mc.options.cloudStatus().get();
            SCENES.add(new Scene("world-horizon", none, 0, 3_000));
            SCENES.add(new Scene("world-sky", none, -65, 3_000));
            SCENES.add(new Scene("world-clouds", () -> mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.FANCY), -20, 3_000));
            SCENES.add(new Scene("world-ground", () -> mc.options.cloudStatus().set(clouds), 70, 3_000));
            SCENES.add(new Scene("world-breaking", () -> {
                if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                        && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK)
                    mc.levelRenderer.destroyBlockProgress(-1, hit.getBlockPos(), 6);
            }, 70, 2_000));
            SCENES.add(new Scene("world-third-person", () -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK), 15, 2_000));
            SCENES.add(new Scene("world-night", () -> {
                mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                serverRun(server, () -> server.overworld().setDayTime(18_000));
            }, -35, 5_000));
            SCENES.add(new Scene("world-rain", () -> serverRun(server, () -> {
                server.overworld().setDayTime(6_000);
                server.overworld().setWeatherParameters(0, 12_000, true, false);
            }), 10, 9_000));
            SCENES.add(new Scene("world-f3", () -> mc.getDebugOverlay().toggleOverlay(), 0, 2_000));
            SCENES.add(new Scene("world-inventory", () -> {
                mc.getDebugOverlay().toggleOverlay();
                mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(player));
            }, 0, 2_000));
            SCENES.add(new Scene("world-pause", () -> mc.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true)), 0, 2_000));
            Direct.LOGGER.info("Direct phase2 world ready after {} ms, {} sections rendered, Direct terrain {}", now - joined,
                    mc.levelRenderer.countRenderedSections(),
                    com.redondoguibi.direct.ClientConfig.ENABLE_DX12.get() ? (com.redondoguibi.direct.Dx12GlInterop.terrainReplaced() ? "replacing vanilla" : "NOT active") : "off");
            step = 0;
            samples = 0;
            entered = now;
            SCENES.get(0).enter.run();
        }
        Scene scene = SCENES.get(step);
        player.setYRot(yaw);
        player.yRotO = yaw;
        player.setXRot(scene.pitch);
        player.xRotO = scene.pitch;
        sceneFrames++;
        if (now - entered < scene.settleMillis) return;
        String name = scene.name + "-" + samples;
        // Rough rate while the scene settled (window size, synchronous backend; not a benchmark).
        if (samples == 0) Direct.LOGGER.info("Direct phase2 scene {} settled at {} FPS", scene.name,
                String.format(Locale.ROOT, "%.1f", sceneFrames * 1000.0 / Math.max(1, now - entered)));
        if (Dx12Shadow.presenting()) { verifyNext = true; verifyName = name; }
        if (!Dx12Shadow.SKIP_GL) compare(mc, name);
        else if (name.endsWith("-0")) dx12Only(mc, name);
        if (++samples < SAMPLES) return;
        if (++step >= SCENES.size()) {
            Direct.LOGGER.info("Direct phase2 world summary {}:{} unsupported={} meshMiB={} meshVerification={}", failed ? "FAIL" : "PASS",
                    summary, Dx12Shadow.unsupported(), Dx12Shadow.meshBytes() >> 20, Dx12Shadow.meshVerification());
            mc.stop();
            return;
        }
        samples = 0;
        entered = now;
        sceneFrames = 0;
        SCENES.get(step).enter.run();
    }

    private static void serverRun(net.minecraft.server.MinecraftServer server, Runnable task) {
        if (server != null) server.execute(task);
    }

    /** OpenGL is skipped: there is nothing to compare against, but the DX12 image is written for inspection. */
    private static void dx12Only(Minecraft mc, String name) {
        var target = mc.getMainRenderTarget();
        int width = target.width, height = target.height;
        ByteBuffer dx = MemoryUtil.memAlloc(width * height * 4);
        try {
            Dx12Shadow.readback(target.getColorTextureId(), dx);
            Path directory = mc.gameDirectory.toPath().resolve(DIRECTORY);
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
            boolean[] mask = new boolean[width * height];
            long unexplained = unexplained(gl, dx, width, height, mask);
            int blocks = solidBlocks(mask, width, height);
            int cluster = largestCluster(mask, width, height);
            // Menus: fewer than 0.1% of pixels more than 2/255 apart. World: differences a one-pixel shift cannot explain
            // (sample points exactly on triangle or texel borders, and the crack sparkles along cloud quad seams, land on
            // different pixels in each API) are dots and one-pixel lines: no 2x2 block of them, no group over 16 pixels,
            // under 0.1% of the image. A missing or misplaced object leaves an area.
            boolean pass = WORLD ? blocks == 0 && cluster <= 16 && unexplained * 1000 < pixels : over2 * 1000 < pixels;
            failed |= !pass;
            String line = String.format(Locale.ROOT, " %s(max=%d, differing=%.3f%%, over2=%.3f%%, over8=%.3f%%, unexplained=%d, blocks=%d, cluster=%d, calls=%d/%d/%d, terrain=%d)",
                    name, max, 100.0 * differing / pixels, 100.0 * over2 / pixels, 100.0 * over8 / pixels, unexplained, blocks, cluster,
                    Dx12Shadow.lastCalls, Dx12Shadow.lastDraws, Dx12Shadow.lastNative, Dx12Shadow.lastTerrain);
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

    /**
     * Pixels more than 8/255 apart where neither image has a pixel within 8/255 of the other's value in the 3x3
     * neighbourhood: differences that are not a one-pixel shift of an edge or texel border.
     */
    private static long unexplained(ByteBuffer gl, ByteBuffer dx, int width, int height, boolean[] mask) {
        long count = 0;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int i = (y * width + x) * 4;
            if (distance(gl, i, dx, i) <= 8) continue;
            if (!nearby(dx, i, gl, x, y, width, height) || !nearby(gl, i, dx, x, y, width, height)) {
                count++;
                mask[y * width + x] = true;
            }
        }
        return count;
    }

    /** 2x2 blocks whose four pixels are all marked: area, not a one-pixel line. */
    private static int solidBlocks(boolean[] mask, int width, int height) {
        int blocks = 0;
        for (int y = 0; y + 1 < height; y++) for (int x = 0; x + 1 < width; x++) {
            int i = y * width + x;
            if (mask[i] && mask[i + 1] && mask[i + width] && mask[i + width + 1]) blocks++;
        }
        return blocks;
    }

    /** Largest 8-connected group of marked pixels. */
    private static int largestCluster(boolean[] mask, int width, int height) {
        int largest = 0;
        int[] stack = new int[mask.length];
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start]) continue;
            int top = 0, size = 0;
            stack[top++] = start;
            mask[start] = false;
            while (top > 0) {
                int p = stack[--top], px = p % width, py = p / width;
                size++;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = px + dx, ny = py + dy;
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height || !mask[ny * width + nx]) continue;
                    mask[ny * width + nx] = false;
                    stack[top++] = ny * width + nx;
                }
            }
            largest = Math.max(largest, size);
        }
        return largest;
    }

    private static boolean nearby(ByteBuffer value, int index, ByteBuffer image, int x, int y, int width, int height) {
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int nx = x + dx, ny = y + dy;
            if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
            if (distance(value, index, image, (ny * width + nx) * 4) <= 8) return true;
        }
        return false;
    }

    private static int distance(ByteBuffer a, int i, ByteBuffer b, int j) {
        int d = 0;
        for (int c = 0; c < 3; c++) d = Math.max(d, Math.abs((a.get(i + c) & 255) - (b.get(j + c) & 255)));
        return d;
    }

    /** OpenGL, DX12 and an 8x amplified difference image, flipped to the usual top-down orientation. */
    private static void write(Minecraft mc, String name, int width, int height, ByteBuffer gl, ByteBuffer dx) {
        Path directory = mc.gameDirectory.toPath().resolve(DIRECTORY);
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
