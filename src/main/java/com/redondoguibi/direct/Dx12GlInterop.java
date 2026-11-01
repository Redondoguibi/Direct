package com.redondoguibi.direct;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.WGLCapabilities;
import org.lwjgl.opengl.WGLNVDXInterop;
import org.lwjgl.system.MemoryStack;


public final class Dx12GlInterop {
    private static final int GL_TEXTURE0 = GL13.GL_TEXTURE0;
    private static final String VERTEX_SHADER = """
            #version 150
            out vec2 texCoord;
            void main() {
                vec2 position = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                texCoord = position * 0.5;
                gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String FRAGMENT_SHADER = """
            #version 150
            uniform sampler2D sourceTexture;
            in vec2 texCoord;
            out vec4 color;
            void main() {
                color = texture(sourceTexture, texCoord);
            }
            """;

    private static boolean initialized;
    private static boolean disabled;
    private static boolean reportedUnsupported;
    private static boolean reportedHook;
    private static volatile boolean blockAtlasDirty = true;
    private static boolean terrainCaptureEnabled;
    private static ClientLevel renderedLevel;
    private static int width;
    private static int height;
    private static long interopDevice;
    private static long interopObject;
    private static long lastGlFence;
    private static int texture;
    private static int vertexArray;
    private static int program;
    private static int blockAtlasId = -1;

    private Dx12GlInterop() {
    }

    public static void renderBeforeGui() {
        if (!reportedHook) {
            Direct.LOGGER.info("DX12/OpenGL interop pre-GUI render hook reached.");
            reportedHook = true;
        }
        if (!ClientConfig.ENABLE_DX12.get()) {
            if (terrainCaptureEnabled) {
                TerrainMeshStore.setCaptureEnabled(false);
                TerrainMeshStore.clear();
                terrainCaptureEnabled = false;
            }
            shutdown();
            disabled = false;
            return;
        }
        if (disabled) {
            return;
        }

        try {
            WGLCapabilities capabilities = GL.getCapabilitiesWGL();
            if (capabilities == null || !capabilities.WGL_NV_DX_interop2) {
                if (!reportedUnsupported) {
                    Direct.LOGGER.error("WGL_NV_DX_interop2 is unavailable on the active OpenGL driver; "
                            + "DX12/OpenGL interop was not attempted and vanilla rendering remains active.");
                    reportedUnsupported = true;
                }
                disabled = true;
                TerrainMeshStore.setCaptureEnabled(false);
                if (terrainCaptureEnabled) {
                    TerrainMeshStore.clear();
                    terrainCaptureEnabled = false;
                }
                return;
            }

            int currentWidth = Minecraft.getInstance().getWindow().getWidth();
            int currentHeight = Minecraft.getInstance().getWindow().getHeight();
            Minecraft minecraft = Minecraft.getInstance();
            if (!terrainCaptureEnabled) {
                TerrainMeshStore.setCaptureEnabled(true);
                terrainCaptureEnabled = true;
                if (minecraft.level != null) {
                    minecraft.levelRenderer.allChanged();
                }
            }
            if (minecraft.level != renderedLevel) {
                if (renderedLevel != null) {
                    TerrainMeshStore.clear();
                }
                renderedLevel = minecraft.level;
            }
            if (currentWidth <= 0 || currentHeight <= 0) {
                return;
            }
            if (!initialized) {
                initialize(currentWidth, currentHeight);
                TerrainMeshStore.resendAll();
                blockAtlasDirty = true;
                Direct.LOGGER.info("DX12 offscreen texture is copied to a D3D11 import texture for "
                        + "WGL_NV_DX_interop2.");
            } else if (currentWidth != width || currentHeight != height) {
                shutdown();
                initialize(currentWidth, currentHeight);
                TerrainMeshStore.resendAll();
                blockAtlasDirty = true;
            }

            waitForPreviousGlUse();
            TerrainMeshStore.uploadPending();
            uploadBlockAtlasIfNeeded(minecraft);
            Camera camera = minecraft.gameRenderer.getMainCamera();
            Vec3 cameraPosition = camera.getPosition();
            Matrix4f view = new Matrix4f().rotation(camera.rotation().conjugate(new Quaternionf()))
                    .translate((float) -cameraPosition.x, (float) -cameraPosition.y, (float) -cameraPosition.z);
            Matrix4f clipCorrection = new Matrix4f().identity().m11(-1.0f).m22(0.5f).m32(0.5f);
            Matrix4f viewProjection = clipCorrection
                    .mul(minecraft.gameRenderer.getProjectionMatrix(minecraft.options.fov().get()))
                    .mul(view);
            float[] matrix = new float[16];
            viewProjection.get(matrix);
            NativeDx12.renderInterop(matrix, (float) cameraPosition.x, (float) cameraPosition.y,
                    (float) cameraPosition.z, minecraft.options.renderDistance().get() * 16.0f);
            drawImportedTexture();
        } catch (IOException | IllegalStateException | UnsatisfiedLinkError exception) {
            Direct.LOGGER.error("DX12/OpenGL interop failed; retaining vanilla rendering.", exception);
            shutdown();
            TerrainMeshStore.setCaptureEnabled(false);
            if (terrainCaptureEnabled) {
                TerrainMeshStore.clear();
                terrainCaptureEnabled = false;
            }
            disabled = true;
        }
    }

    public static void shutdown() {
        if (lastGlFence != 0) {
            GL11.glFinish();
            GL32.glDeleteSync(lastGlFence);
            lastGlFence = 0;
        }
        if (interopDevice != 0 && interopObject != 0) {
            WGLNVDXInterop.wglDXUnregisterObjectNV(interopDevice, interopObject);
            interopObject = 0;
        }
        if (interopDevice != 0) {
            WGLNVDXInterop.wglDXCloseDeviceNV(interopDevice);
            interopDevice = 0;
        }
        if (texture != 0) {
            GL11.glDeleteTextures(texture);
            texture = 0;
        }
        if (vertexArray != 0) {
            GL30.glDeleteVertexArrays(vertexArray);
            vertexArray = 0;
        }
        if (program != 0) {
            GL20.glDeleteProgram(program);
            program = 0;
        }
        if (initialized) {
            NativeDx12.shutdownInterop();
            initialized = false;
        }
        width = 0;
        height = 0;
    }

    public static void blockAtlasUpdated() {
        blockAtlasDirty = true;
    }

    private static void uploadBlockAtlasIfNeeded(Minecraft minecraft) {
        int atlasTexture = minecraft.getTextureManager()
                .getTexture(InventoryMenu.BLOCK_ATLAS).getId();
        if (atlasTexture != blockAtlasId) {
            blockAtlasId = atlasTexture;
            blockAtlasDirty = true;
        }
        if (!blockAtlasDirty) {
            return;
        }

        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousPackBuffer = GL11.glGetInteger(GL30.GL_PIXEL_PACK_BUFFER_BINDING);
        int previousPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlasTexture);
        try {
            int atlasWidth = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int atlasHeight = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (atlasWidth <= 0 || atlasHeight <= 0) {
                return;
            }
            long byteCount = (long) atlasWidth * atlasHeight * 4;
            if (byteCount > Integer.MAX_VALUE) {
                throw new IllegalStateException("Minecraft block atlas exceeds the supported DX12 upload size.");
            }
            ByteBuffer pixels = ByteBuffer.allocateDirect((int) byteCount);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            NativeDx12.updateTerrainAtlas(pixels, atlasWidth, atlasHeight);
            blockAtlasDirty = false;
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, previousPackAlignment);
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, previousPackBuffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        }
    }

    private static void initialize(int frameWidth, int frameHeight) throws IOException {
        NativeRenderer.loadNativeLibrary();
        long[] handles = NativeDx12.initializeInterop(frameWidth, frameHeight);
        if (handles == null || handles.length != 2 || handles[0] == 0 || handles[1] == 0) {
            throw new IllegalStateException("DX12 did not expose its D3D11 device and import texture.");
        }
        initialized = true;
        interopDevice = WGLNVDXInterop.wglDXOpenDeviceNV(handles[0]);
        if (interopDevice == 0) {
            throw new IllegalStateException("WGL_NV_DX_interop2 could not open the D3D11On12 device.");
        }

        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL_TEXTURE0);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            interopObject = WGLNVDXInterop.wglDXRegisterObjectNV(interopDevice, handles[1], texture,
                    GL11.GL_TEXTURE_2D, WGLNVDXInterop.WGL_ACCESS_READ_ONLY_NV);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL13.glActiveTexture(previousActiveTexture);
        }
        if (interopObject == 0) {
            throw new IllegalStateException("WGL_NV_DX_interop2 could not register the D3D11 import texture.");
        }
        program = createProgram();
        vertexArray = GL30.glGenVertexArrays();
        width = frameWidth;
        height = frameHeight;
    }

    private static void waitForPreviousGlUse() {
        if (lastGlFence == 0) {
            return;
        }
        GL32.glWaitSync(lastGlFence, 0, GL32.GL_TIMEOUT_IGNORED);
        int result = GL32.glClientWaitSync(lastGlFence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, Long.MAX_VALUE);
        GL32.glDeleteSync(lastGlFence);
        lastGlFence = 0;
        if (result != GL32.GL_ALREADY_SIGNALED && result != GL32.GL_CONDITION_SATISFIED) {
            throw new IllegalStateException("OpenGL failed while waiting for the prior texture read.");
        }
    }

    private static void drawImportedTexture() {
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int[] previousViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport);
        GL13.glActiveTexture(GL_TEXTURE0);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean stencilEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean[] previousColorMask = new boolean[4];

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer colorMask = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask);
            for (int component = 0; component < previousColorMask.length; component++) {
                previousColorMask[component] = colorMask.get(component) != 0;
            }
            var objects = stack.mallocPointer(1);
            objects.put(interopObject).flip();
            if (!WGLNVDXInterop.wglDXLockObjectsNV(interopDevice, objects)) {
                throw new IllegalStateException("WGL_NV_DX_interop2 could not lock the import texture for OpenGL.");
            }
            try {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glDisable(GL11.GL_STENCIL_TEST);
                GL11.glDepthMask(false);
                GL11.glColorMask(true, true, true, true);
                GL11.glViewport(0, 0, width, height);
                GL20.glUseProgram(program);
                GL30.glBindVertexArray(vertexArray);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
                GL20.glUniform1i(GL20.glGetUniformLocation(program, "sourceTexture"), 0);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                lastGlFence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                if (lastGlFence == 0) {
                    throw new IllegalStateException("OpenGL failed to fence the imported texture draw.");
                }
                GL11.glFlush();
            } finally {
                objects.clear();
                objects.put(interopObject).flip();
                if (!WGLNVDXInterop.wglDXUnlockObjectsNV(interopDevice, objects)) {
                    throw new IllegalStateException("WGL_NV_DX_interop2 could not unlock the import texture.");
                }
            }
        } finally {
            GL20.glUseProgram(previousProgram);
            GL30.glBindVertexArray(previousVertexArray);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL13.glActiveTexture(previousActiveTexture);
            GL11.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            GL11.glDepthMask(depthMask);
            GL11.glColorMask(previousColorMask[0], previousColorMask[1],
                    previousColorMask[2], previousColorMask[3]);
            setEnabled(GL11.GL_DEPTH_TEST, depthEnabled);
            setEnabled(GL11.GL_BLEND, blendEnabled);
            setEnabled(GL11.GL_CULL_FACE, cullEnabled);
            setEnabled(GL11.GL_SCISSOR_TEST, scissorEnabled);
            setEnabled(GL11.GL_STENCIL_TEST, stencilEnabled);
        }
    }

    private static int createProgram() {
        int vertexShader = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader;
        try {
            fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        } catch (IllegalStateException exception) {
            GL20.glDeleteShader(vertexShader);
            throw exception;
        }
        int shaderProgram = GL20.glCreateProgram();
        GL20.glAttachShader(shaderProgram, vertexShader);
        GL20.glAttachShader(shaderProgram, fragmentShader);
        GL20.glLinkProgram(shaderProgram);
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);
        if (GL20.glGetProgrami(shaderProgram, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(shaderProgram);
            GL20.glDeleteProgram(shaderProgram);
            throw new IllegalStateException("OpenGL overlay shader link failed: " + log);
        }
        return shaderProgram;
    }

    private static int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("OpenGL overlay shader compilation failed: " + log);
        }
        return shader;
    }

    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) {
            GL11.glEnable(capability);
        } else {
            GL11.glDisable(capability);
        }
    }

}
