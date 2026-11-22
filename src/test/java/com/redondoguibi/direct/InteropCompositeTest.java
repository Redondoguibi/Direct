package com.redondoguibi.direct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

@Tag("graphics")
class InteropCompositeTest {
    @Test
    void compositesEveryTexelAtNativeScaleWithD3dRowOrientation() {
        assertTrue(GLFW.glfwInit(), "GLFW initialization failed");
        long window = 0;
        try {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            window = GLFW.glfwCreateWindow(16, 16, "Direct compositor regression", 0, 0);
            assertNotEquals(0L, window, "OpenGL context creation failed");
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();

            int program = GL20.glCreateProgram();
            int vertex = compile(GL20.GL_VERTEX_SHADER, Dx12GlInterop.VERTEX_SHADER);
            int fragment = compile(GL20.GL_FRAGMENT_SHADER, Dx12GlInterop.FRAGMENT_SHADER);
            GL20.glAttachShader(program, vertex);
            GL20.glAttachShader(program, fragment);
            GL20.glLinkProgram(program);
            assertEquals(GL11.GL_TRUE, GL20.glGetProgrami(program, GL20.GL_LINK_STATUS),
                    GL20.glGetProgramInfoLog(program));
            GL20.glDeleteShader(vertex);
            GL20.glDeleteShader(fragment);
            GL20.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "sourceTexture"), 0);
            GL30.glBindVertexArray(GL30.glGenVertexArrays());

            try (MemoryStack stack = MemoryStack.stackPush()) {
                // Unique RGBA values in every texel expose cropping, scaling, flips and lost alpha.
                int size = 8;
                ByteBuffer source = stack.malloc(size * size * 4);
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        source.put((byte) (x * 31)).put((byte) (y * 29))
                                .put((byte) (x + y * size)).put((byte) (x * 13 + y * 17));
                    }
                }
                source.flip();
                int inputTexture = texture(size, source);
                int outputTexture = texture(size, null);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, GL30.glGenFramebuffers());
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D, outputTexture, 0);
                assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, inputTexture);
                GL11.glViewport(0, 0, size, size);
                GL11.glDisable(GL11.GL_DITHER);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                ByteBuffer actual = stack.malloc(source.remaining());
                GL11.glReadPixels(0, 0, size, size, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, actual);
                assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        for (int channel = 0; channel < 4; channel++) {
                            int expectedOffset = ((size - 1 - y) * size + x) * 4 + channel;
                            int actualOffset = (y * size + x) * 4 + channel;
                            assertEquals(Byte.toUnsignedInt(source.get(expectedOffset)),
                                    Byte.toUnsignedInt(actual.get(actualOffset)),
                                    "Pixel (" + x + ", " + y + "), channel " + channel);
                        }
                    }
                }
            }
        } finally {
            // Destroying this isolated context also releases its GL objects after a failed assertion.
            if (window != 0) {
                GL.setCapabilities(null);
                GLFW.glfwDestroyWindow(window);
            }
            GLFW.glfwTerminate();
        }
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        assertEquals(GL11.GL_TRUE, GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS),
                GL20.glGetShaderInfoLog(shader));
        return shader;
    }

    private static int texture(int size, ByteBuffer pixels) {
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, size, size, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        return texture;
    }
}
