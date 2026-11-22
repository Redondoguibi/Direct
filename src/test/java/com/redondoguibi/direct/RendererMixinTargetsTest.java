package com.redondoguibi.direct;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * The 1.9 vanilla sky/cloud replacement injects at HEAD of LevelRenderer.renderSky and renderClouds; mixins are
 * not applied in unit tests, so the exact descriptors are checked against the real 1.21.1 bytecode instead.
 */
class RendererMixinTargetsTest {
    @Test
    void skyAndCloudInjectionTargetsExist() throws IOException {
        ClassNode node = new ClassNode();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("net/minecraft/client/renderer/LevelRenderer.class")) {
            assertNotNull(stream, "LevelRenderer missing from the test classpath");
            new ClassReader(stream).accept(node, 0);
        }
        String[][] targets = {
                {"renderSky", "(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V"},
                {"renderClouds", "(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FDDD)V"}};
        for (String[] target : targets) {
            long matches = node.methods.stream().filter(m -> m.name.equals(target[0])).count();
            assertEquals(1, matches, target[0] + " must be unique so method = \"" + target[0] + "\" is unambiguous");
            assertTrue(node.methods.stream().anyMatch(m -> m.name.equals(target[0]) && m.desc.equals(target[1])), target[0] + target[1]);
        }
    }

    /** The CPU atlas animation path depends on these exact members; a mismatch would fail mixin application. */
    @Test
    void cpuAtlasAnimationTargetsExist() throws IOException {
        ClassNode atlas = read("net/minecraft/client/renderer/texture/TextureAtlas");
        assertTrue(atlas.methods.stream().anyMatch(m -> m.name.equals("cycleAnimationFrames") && m.desc.equals("()V")));
        assertTrue(atlas.methods.stream().anyMatch(m -> m.name.equals("clearTextureData") && m.desc.equals("()V")));
        ClassNode sprite = read("net/minecraft/client/renderer/texture/SpriteContents");
        assertTrue(sprite.methods.stream().anyMatch(m -> m.name.equals("upload")
                && m.desc.equals("(IIII[Lcom/mojang/blaze3d/platform/NativeImage;)V")));
        ClassNode image = read("com/mojang/blaze3d/platform/NativeImage");
        assertTrue(image.fields.stream().anyMatch(f -> f.name.equals("pixels") && f.desc.equals("J")),
                "NativeImage.pixels must stay the long address passed to glTexSubImage2D");
    }

    private ClassNode read(String name) throws IOException {
        ClassNode node = new ClassNode();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(stream, name + " missing from the test classpath");
            new ClassReader(stream).accept(node, 0);
        }
        return node;
    }
}
