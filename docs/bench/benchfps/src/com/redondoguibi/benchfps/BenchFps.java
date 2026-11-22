package com.redondoguibi.benchfps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Benchmark-only frame logger, identical for every renderer stack (Direct, Sodium/Iris, vanilla). Not shipped.
 * Counts frames only while a world is shown without a screen, and writes one CSV row per 60 s window to
 * logs/bench-fps.csv: frames, FPS (frames / wall time) and frame-time percentiles.
 */
@Mod("benchfps")
public final class BenchFps {
    private static final long WINDOW_NANOS = 60_000_000_000L;
    private final long[] samples = new long[1 << 16];
    private int count;
    private long previous;
    private long windowStart;

    public BenchFps(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(this::frame);
    }

    private void frame(RenderFrameEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        long now = System.nanoTime();
        if (minecraft.level == null || minecraft.screen != null || minecraft.getOverlay() != null) {
            previous = 0;
            windowStart = 0;
            count = 0;
            return;
        }
        if (previous != 0 && count < samples.length) samples[count++] = now - previous;
        previous = now;
        if (windowStart == 0) windowStart = now;
        if (now - windowStart < WINDOW_NANOS) return;
        write(minecraft, now - windowStart);
        windowStart = now;
        count = 0;
    }

    private void write(Minecraft minecraft, long elapsed) {
        long[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        double fps = count * 1e9 / elapsed;
        String row = String.format(Locale.ROOT, "%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d%n", java.time.Instant.now(), count, fps,
                elapsed / 1e6 / Math.max(1, count), percentile(sorted, 0.50), percentile(sorted, 0.95), percentile(sorted, 0.99),
                minecraft.getWindow().getWidth(), minecraft.options.getEffectiveRenderDistance());
        try {
            Path file = minecraft.gameDirectory.toPath().resolve("logs").resolve("bench-fps.csv");
            Files.createDirectories(file.getParent());
            if (!Files.exists(file))
                Files.writeString(file, "utc,frames,fps,meanMs,p50Ms,p95Ms,p99Ms,width,renderDistance\n", StandardCharsets.UTF_8);
            Files.writeString(file, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // A benchmark row lost to IO is visible as a missing line; never disturb the game.
        }
    }

    private static double percentile(long[] sorted, double fraction) {
        if (sorted.length == 0) return 0;
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(fraction * sorted.length) - 1)] / 1e6;
    }
}
