package com.redondoguibi.direct;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;

/**
 * The CPU atlas mode must hand DX12 exactly the bytes an OpenGL readback would return. Sprites are uploaded with the
 * same unpack state as {@code NativeImage._upload}; every patch sent is then compared with the real GL texture,
 * including uninitialised texels between sprites and patches that overlap at coarse mip levels.
 */
@Tag("graphics")
class AtlasCpuMirrorTest {
    private static final int SIZE = 64;
    private static final int LEVELS = 4;

    /** Source image per level, frames laid out horizontally like an animated sprite strip. */
    private record Sprite(int x, int y, int width, int height, int frames, ByteBuffer[] levels) {
        int sourceWidth(int level) { return (width * frames) >> level; }
        int sourceHeight(int level) { return height >> level; }
    }

    private static final class BufferSource implements AtlasRegionUpdates.SpriteSource {
        final Sprite sprite;
        boolean unusable;
        BufferSource(Sprite sprite) { this.sprite = sprite; }
        @Override public int levels() { return sprite.levels.length; }
        @Override public long address(int level) { return unusable ? 0 : MemoryUtil.memAddress0(sprite.levels[level]); }
        @Override public int width(int level) { return sprite.sourceWidth(level); }
        @Override public int height(int level) { return sprite.sourceHeight(level); }
    }

    /** Records the RGBA patch behind every upload; "encoding" keeps the input pixels. */
    private static final class RecordingSink implements AtlasRegionUpdates.AtlasSink {
        final Map<ByteBuffer, byte[]> encoded = new IdentityHashMap<>();
        final Map<String, byte[]> sent = new LinkedHashMap<>();
        int uploads;
        @Override public boolean compressed() { return true; }
        @Override public int mipLevels() { return LEVELS; }
        @Override public void encode(ByteBuffer pixels, ByteBuffer output, int width, int height, int threads) {
            byte[] copy = new byte[width * height * 4];
            pixels.duplicate().clear().get(copy);
            synchronized (encoded) { encoded.put(output, copy); }
        }
        @Override public boolean upload(ByteBuffer data, int level, int x, int y, int width, int height) {
            byte[] pixels;
            synchronized (encoded) { pixels = encoded.get(data); }
            assertTrue(pixels != null, "every uploaded patch must have been encoded first");
            sent.put(level + ":" + x + ":" + y + ":" + width + ":" + height, pixels.clone());
            uploads++;
            return true;
        }
    }

    @Test
    void cpuPatchesMatchOpenGlAndNeedOneBaselineReadback() {
        assertTrue(GLFW.glfwInit(), "GLFW initialization failed");
        long window = 0;
        try {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            window = GLFW.glfwCreateWindow(16, 16, "Direct CPU atlas mirror", 0, 0);
            assertNotEquals(0L, window, "OpenGL context creation failed");
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();

            Random random = new Random(1234);
            int atlas = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, LEVELS - 1);
            // Random texels everywhere stand in for whatever the driver leaves between sprites.
            for (int level = 0; level < LEVELS; level++) {
                int size = SIZE >> level;
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, level, GL11.GL_RGBA8, size, size, 0, GL11.GL_RGBA,
                        GL11.GL_UNSIGNED_BYTE, randomPixels(random, size * size));
            }
            // C and D overlap partially at mip 2; A and B share a block at mip 3.
            List<Sprite> sprites = List.of(
                    sprite(random, 0, 0, 16, 16, 3), sprite(random, 16, 8, 8, 8, 2),
                    sprite(random, 8, 32, 24, 8, 2), sprite(random, 0, 40, 8, 8, 4),
                    sprite(random, 40, 48, 16, 16, 1));
            List<BufferSource> sources = new ArrayList<>();
            for (Sprite sprite : sprites) sources.add(new BufferSource(sprite));

            RecordingSink sink = new RecordingSink();
            AtlasRegionUpdates updates = new AtlasRegionUpdates(sink);
            for (BufferSource source : sources) upload(updates, source, 0);
            drive(updates, atlas);
            assertEquals(1, updates.glReadbacks(), "the first update reads all baselines in one batch");
            assertMatchesOpenGl(atlas, sink);

            for (int round = 1; round <= 6; round++) {
                for (BufferSource source : sources) {
                    if (random.nextBoolean()) upload(updates, source, random.nextInt(source.sprite.frames));
                }
                upload(updates, sources.get(3), round % sources.get(3).sprite.frames); // D always: refreshes C's patch
                drive(updates, atlas);
                assertEquals(1, updates.glReadbacks(), "animation frames are copied from the CPU, round " + round);
                assertMatchesOpenGl(atlas, sink);
            }

            // A source that cannot be copied is read back from OpenGL instead, with the same result.
            sources.get(0).unusable = true;
            upload(updates, sources.get(0), 1);
            drive(updates, atlas);
            assertEquals(2, updates.glReadbacks());
            assertMatchesOpenGl(atlas, sink);
            sources.get(0).unusable = false;

            // Forgotten sources (vanilla closing the sprite images) are never read.
            int before = sink.uploads;
            updates.markUpload(0, 0, 0, 0, 16, 16, sources.get(0));
            updates.forgetSources();
            drive(updates, atlas);
            assertEquals(before, sink.uploads);
            assertFalse(updates.hasPendingWork());
            updates.close();
            GL11.glDeleteTextures(atlas);
        } finally {
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }

    @Test
    void overlayCopiesOnlyTheIntersectionAndRejectsOutOfBoundsSources() {
        ByteBuffer source = ByteBuffer.allocateDirect(8 * 4 * 4);
        for (int i = 0; i < 8 * 4; i++) source.putInt(i * 4, 0x01010101 * (i + 1));
        ByteBuffer patch = ByteBuffer.allocateDirect(4 * 4 * 4);
        var region = new AtlasRegionUpdates.Region(4, 4, 4, 4);
        // 4x4 frame at source x=4, written at atlas (6, 2): rows 4..5 and columns 6..7 fall in the patch.
        assertTrue(AtlasRegionUpdates.overlay(MemoryUtil.memAddress0(source), 8, 4, 4, 0, 4, 4, 6, 2, patch, region));
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int atlasX = 4 + x, atlasY = 4 + y;
                boolean inside = atlasX >= 6 && atlasY >= 2 && atlasY < 6;
                int expected = inside ? 0x01010101 * ((atlasY - 2) * 8 + 4 + atlasX - 6 + 1) : 0;
                assertEquals(expected, patch.getInt((y * 4 + x) * 4), "texel " + x + "," + y);
            }
        }
        assertFalse(AtlasRegionUpdates.overlay(MemoryUtil.memAddress0(source), 8, 4, 0, 0, 4, 4, 0, 0, patch, region));
        assertThrows(IllegalArgumentException.class, () ->
                AtlasRegionUpdates.overlay(MemoryUtil.memAddress0(source), 8, 4, 6, 0, 4, 4, 4, 4, patch, region));
        assertNull(AtlasRegionUpdates.alignedRegion(64, 0, 8, 8, 3, 8, 8, true), "a sprite past the mip edge writes nothing");
        assertEquals(new AtlasRegionUpdates.Region(0, 0, 4, 4), AtlasRegionUpdates.alignedRegion(8, 0, 8, 8, 3, 8, 8, true));
    }

    private static Sprite sprite(Random random, int x, int y, int width, int height, int frames) {
        ByteBuffer[] levels = new ByteBuffer[LEVELS];
        for (int level = 0; level < LEVELS; level++)
            levels[level] = randomPixels(random, ((width * frames) >> level) * (height >> level));
        return new Sprite(x, y, width, height, frames, levels);
    }

    private static ByteBuffer randomPixels(Random random, int texels) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(texels * 4);
        for (int i = 0; i < texels * 4; i++) pixels.put(i, (byte) random.nextInt(256));
        return pixels;
    }

    /** {@code SpriteContents.upload} followed by the recording hook, with {@code NativeImage._upload}'s unpack state. */
    private static void upload(AtlasRegionUpdates updates, BufferSource source, int frame) {
        Sprite sprite = source.sprite;
        int frameX = frame * sprite.width;
        for (int level = 0; level < sprite.levels.length; level++) {
            int width = sprite.width >> level, height = sprite.height >> level;
            if (width <= 0 || height <= 0) break;
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, width == sprite.sourceWidth(level) ? 0 : sprite.sourceWidth(level));
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, frameX >> level);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, level, sprite.x >> level, sprite.y >> level, width, height,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, sprite.levels[level]);
        }
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        updates.markUpload(sprite.x, sprite.y, frameX, 0, sprite.width, sprite.height, source);
    }

    private static void drive(AtlasRegionUpdates updates, int atlas) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        do {
            updates.update(atlas, SIZE, SIZE, 0, 1, true);
            Thread.yield();
        } while (updates.hasPendingWork() && System.nanoTime() < deadline);
        assertFalse(updates.hasPendingWork(), "atlas updates must complete");
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    private static void assertMatchesOpenGl(int atlas, RecordingSink sink) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        byte[][] levels = new byte[LEVELS][];
        for (int level = 0; level < LEVELS; level++) {
            int size = SIZE >> level;
            ByteBuffer pixels = ByteBuffer.allocateDirect(size * size * 4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, level, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            levels[level] = new byte[pixels.capacity()];
            pixels.get(levels[level]);
        }
        assertFalse(sink.sent.isEmpty());
        for (var entry : sink.sent.entrySet()) {
            String[] key = entry.getKey().split(":");
            int level = Integer.parseInt(key[0]), x = Integer.parseInt(key[1]), y = Integer.parseInt(key[2]);
            int width = Integer.parseInt(key[3]), height = Integer.parseInt(key[4]);
            int size = SIZE >> level;
            byte[] expected = new byte[width * height * 4];
            for (int row = 0; row < height; row++)
                System.arraycopy(levels[level], ((y + row) * size + x) * 4, expected, row * width * 4, width * 4);
            assertArrayEquals(expected, entry.getValue(), "patch " + entry.getKey() + " differs from OpenGL");
        }
    }
}
