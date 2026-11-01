package com.redondoguibi.direct;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

public final class NativeRenderer {
    private static final int MAX_PASSES = 8;
    private static final int MAX_UNIFORMS = 16;
    private static final int CAPTURE_BUFFER_COUNT = 3;
    private static final int CAPTURE_FREE = 0;
    private static final int CAPTURE_PENDING = 1;
    private static final int CAPTURE_READY = 2;
    private static final Pattern UNIFORM_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static boolean initialized;
    private static boolean disabled;
    private static boolean reportedUnsupportedPlatform;
    private static int width;
    private static int height;
    private static long startedAtNanos;
    private static ShaderPack activePack;
    private static final int[] pixelBuffers = new int[CAPTURE_BUFFER_COUNT];
    private static final long[] captureFences = new long[CAPTURE_BUFFER_COUNT];
    private static final long[] captureOrders = new long[CAPTURE_BUFFER_COUNT];
    private static final int[] captureStates = new int[CAPTURE_BUFFER_COUNT];
    private static final float[] captureTimes = new float[CAPTURE_BUFFER_COUNT];
    private static long captureSequence;
    private static boolean hasPresentedDx12;
    private static boolean reportedFirstDx12Frame;

    private NativeRenderer() {
    }

    public static boolean renderIfEnabled(long glfwWindow) {
        if (!ClientConfig.shaderPresentActive()) {
            if (initialized || pixelBuffers[0] != 0) {
                shutdown();
            }
            disabled = false;
            return false;
        }
        if (disabled) {
            return false;
        }
        if (!isWindowsX64()) {
            if (!reportedUnsupportedPlatform) {
                Direct.LOGGER.error("The DirectX 12 shader present requires Windows x86-64; "
                        + "vanilla presentation remains active.");
                reportedUnsupportedPlatform = true;
            }
            return false;
        }

        try {
            Minecraft minecraft = Minecraft.getInstance();
            int currentWidth = minecraft.getWindow().getWidth();
            int currentHeight = minecraft.getWindow().getHeight();
            if (currentWidth <= 0 || currentHeight <= 0) {
                return false;
            }
            long byteCount = (long) currentWidth * currentHeight * 4;
            if (byteCount > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Framebuffer is too large for the DX12 frame bridge.");
            }

            if (!initialized) {
                NativeLibrary.load();
                activePack = loadShaderPack(ClientConfig.SHADER_PACK.get());
                long hwnd = GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
                if (hwnd == 0) {
                    throw new IllegalStateException("GLFW returned a null Win32 HWND.");
                }
                NativeDx12.initialize(hwnd, currentWidth, currentHeight, activePack.shaders());
                width = currentWidth;
                height = currentHeight;
                startedAtNanos = System.nanoTime();
                initialized = true;
                Direct.LOGGER.info("DirectX 12 shader present initialized with pack '{}'.", activePack.name());
            } else if (currentWidth != width || currentHeight != height) {
                NativeDx12.resize(currentWidth, currentHeight);
                width = currentWidth;
                height = currentHeight;
                releasePixelBuffers();
                hasPresentedDx12 = false;
            }
            if (pixelBuffers[0] == 0) {
                allocatePixelBuffers(byteCount);
            }

            pollCaptureFences();
            int readyBuffer = findOldestReadyBuffer();
            if (readyBuffer >= 0) {
                renderCapturedFrame(readyBuffer, byteCount, currentWidth, currentHeight);
                hasPresentedDx12 = true;
                if (!reportedFirstDx12Frame) {
                    Direct.LOGGER.info("DirectX 12 presented the first captured Minecraft frame.");
                    reportedFirstDx12Frame = true;
                }
            }
            int writableBuffer = findFreeBuffer();
            if (writableBuffer >= 0) {
                captureCurrentFrame(writableBuffer, currentWidth, currentHeight);
            }
            return hasPresentedDx12;
        } catch (IOException | JsonParseException | IllegalArgumentException | IllegalStateException
                 | UnsatisfiedLinkError exception) {
            Direct.LOGGER.error("DirectX 12 shader present failed; falling back to OpenGL presentation.", exception);
            shutdown();
            disabled = true;
            return false;
        }
    }

    public static void shutdown() {
        if (initialized) {
            NativeDx12.shutdown();
        }
        initialized = false;
        width = 0;
        height = 0;
        activePack = null;
        releasePixelBuffers();
        hasPresentedDx12 = false;
        reportedFirstDx12Frame = false;
    }

    private static boolean isWindowsX64() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.contains("win") && (arch.equals("amd64") || arch.equals("x86_64"));
    }

    private static void pollCaptureFences() {
        for (int index = 0; index < CAPTURE_BUFFER_COUNT; index++) {
            if (captureStates[index] != CAPTURE_PENDING) {
                continue;
            }
            int result = GL32.glClientWaitSync(captureFences[index], 0, 0);
            if (result == GL32.GL_ALREADY_SIGNALED || result == GL32.GL_CONDITION_SATISFIED) {
                GL32.glDeleteSync(captureFences[index]);
                captureFences[index] = 0;
                captureStates[index] = CAPTURE_READY;
            } else if (result == GL32.GL_WAIT_FAILED) {
                throw new IllegalStateException("OpenGL failed while checking a frame-capture fence.");
            }
        }
    }

    private static int findOldestReadyBuffer() {
        int oldest = -1;
        long oldestSequence = Long.MAX_VALUE;
        for (int index = 0; index < CAPTURE_BUFFER_COUNT; index++) {
            if (captureStates[index] == CAPTURE_READY && captureOrders[index] < oldestSequence) {
                oldest = index;
                oldestSequence = captureOrders[index];
            }
        }
        return oldest;
    }

    private static int findFreeBuffer() {
        for (int offset = 0; offset < CAPTURE_BUFFER_COUNT; offset++) {
            int index = (int) ((captureSequence + offset) % CAPTURE_BUFFER_COUNT);
            if (captureStates[index] == CAPTURE_FREE) {
                return index;
            }
        }
        return -1;
    }

    private static void captureCurrentFrame(int bufferIndex, int frameWidth, int frameHeight) {
        int previousPackBuffer = GL11.glGetInteger(GL30.GL_PIXEL_PACK_BUFFER_BINDING);
        int previousReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int previousPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
            GL11.glReadBuffer(GL11.GL_BACK);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, pixelBuffers[bufferIndex]);
            GL11.glReadPixels(0, 0, frameWidth, frameHeight, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            long fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (fence == 0) {
                throw new IllegalStateException("OpenGL failed to create a frame-capture fence.");
            }
            captureFences[bufferIndex] = fence;
            captureStates[bufferIndex] = CAPTURE_PENDING;
            captureTimes[bufferIndex] = (System.nanoTime() - startedAtNanos) / 1_000_000_000.0f;
            captureOrders[bufferIndex] = captureSequence++;
        } finally {
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, previousPackBuffer);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, previousPackAlignment);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            GL11.glReadBuffer(previousReadBuffer);
        }
    }

    private static void renderCapturedFrame(int bufferIndex, long byteCount, int frameWidth, int frameHeight) {
        int previousPackBuffer = GL11.glGetInteger(GL30.GL_PIXEL_PACK_BUFFER_BINDING);
        GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, pixelBuffers[bufferIndex]);
        boolean mapped = false;
        try {
            ByteBuffer pixels = GL30.glMapBufferRange(GL30.GL_PIXEL_PACK_BUFFER, 0, byteCount, GL30.GL_MAP_READ_BIT);
            if (pixels == null) {
                throw new IllegalStateException("OpenGL could not map a completed pixel-pack buffer.");
            }
            mapped = true;
            NativeDx12.render(captureTimes[bufferIndex], frameWidth, frameHeight, activePack.uniforms(), pixels);
        } finally {
            boolean valid = !mapped || GL15.glUnmapBuffer(GL30.GL_PIXEL_PACK_BUFFER);
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, previousPackBuffer);
            captureStates[bufferIndex] = CAPTURE_FREE;
            if (!valid) {
                throw new IllegalStateException("OpenGL invalidated the mapped frame capture.");
            }
        }
    }

    private static void allocatePixelBuffers(long byteCount) {
        int previousPackBuffer = GL11.glGetInteger(GL30.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            for (int index = 0; index < CAPTURE_BUFFER_COUNT; index++) {
                pixelBuffers[index] = GL15.glGenBuffers();
                if (pixelBuffers[index] == 0) {
                    throw new IllegalStateException("OpenGL could not allocate a frame-capture buffer.");
                }
                GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, pixelBuffers[index]);
                GL15.glBufferData(GL30.GL_PIXEL_PACK_BUFFER, byteCount, GL15.GL_STREAM_READ);
            }
        } finally {
            GL15.glBindBuffer(GL30.GL_PIXEL_PACK_BUFFER, previousPackBuffer);
        }
    }

    private static void releasePixelBuffers() {
        for (int index = 0; index < CAPTURE_BUFFER_COUNT; index++) {
            if (pixelBuffers[index] != 0) {
                GL15.glDeleteBuffers(pixelBuffers[index]);
                pixelBuffers[index] = 0;
            }
            if (captureFences[index] != 0) {
                GL32.glDeleteSync(captureFences[index]);
                captureFences[index] = 0;
            }
            captureStates[index] = CAPTURE_FREE;
            captureOrders[index] = 0;
            captureTimes[index] = 0.0f;
        }
        captureSequence = 0;
    }

    private static ShaderPack loadShaderPack(String name) throws IOException {
        Path shaderpacks = FMLPaths.CONFIGDIR.get().resolve("direct").resolve("shaderpacks").normalize();
        Path packRoot = shaderpacks.resolve(name).normalize();
        if (!packRoot.startsWith(shaderpacks)) {
            throw new IllegalArgumentException("Invalid shader pack folder name: " + name);
        }
        if (!Files.isDirectory(packRoot)) {
            if (!"default".equals(name)) {
                throw new IOException("Shader pack folder does not exist: " + packRoot);
            }
            installDefaultPack(packRoot);
        }

        JsonElement root;
        try (var reader = Files.newBufferedReader(packRoot.resolve("pack.json"), StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader);
        }
        JsonObject manifest = requireObject(root, "pack.json");
        if (!isNumber(manifest.get("format")) || manifest.get("format").getAsInt() != 1) {
            throw new IllegalArgumentException("Unsupported or missing shader pack format version.");
        }
        JsonElement passesElement = manifest.get("passes");
        if (passesElement == null || !passesElement.isJsonArray()) {
            throw new IllegalArgumentException("Shader pack 'passes' must be an array.");
        }
        JsonArray passes = passesElement.getAsJsonArray();
        if (passes.isEmpty() || passes.size() > MAX_PASSES) {
            throw new IllegalArgumentException("A shader pack must define between 1 and " + MAX_PASSES + " passes.");
        }

        ArrayList<String> sources = new ArrayList<>();
        ArrayList<float[]> passUniforms = new ArrayList<>();
        for (JsonElement element : passes) {
            JsonObject pass = requireObject(element, "pass");
            JsonElement shaderElement = pass.get("shader");
            if (shaderElement == null || !shaderElement.isJsonPrimitive()
                    || !shaderElement.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Each pass needs a 'shader' path string.");
            }
            String shaderPath = shaderElement.getAsString();
            Path shader = packRoot.resolve(shaderPath).normalize();
            if (!shader.startsWith(packRoot) || !Files.isRegularFile(shader)) {
                throw new IllegalArgumentException("Shader path is invalid or missing: " + shaderPath);
            }
            String source = Files.readString(shader, StandardCharsets.UTF_8);
            JsonObject uniforms = pass.has("uniforms") ? requireObject(pass.get("uniforms"), "uniforms")
                    : new JsonObject();
            if (uniforms.size() > MAX_UNIFORMS) {
                throw new IllegalArgumentException("A pass may define at most " + MAX_UNIFORMS + " uniforms.");
            }
            float[] values = new float[MAX_UNIFORMS * 4];
            StringBuilder aliases = new StringBuilder();
            int slot = 0;
            for (Map.Entry<String, JsonElement> entry : uniforms.entrySet()) {
                String uniformName = entry.getKey();
                if (!UNIFORM_NAME.matcher(uniformName).matches()
                        || uniformName.startsWith("u_Frame") || uniformName.startsWith("u_User")
                        || uniformName.startsWith("u_Scene")) {
                    throw new IllegalArgumentException("Invalid or reserved uniform name: " + uniformName);
                }
                JsonElement vectorElement = entry.getValue();
                if (!vectorElement.isJsonArray() || vectorElement.getAsJsonArray().size() != 4) {
                    throw new IllegalArgumentException("Uniform '" + uniformName + "' must contain four numbers.");
                }
                JsonArray vector = vectorElement.getAsJsonArray();
                for (int component = 0; component < 4; component++) {
                    if (!isNumber(vector.get(component))) {
                        throw new IllegalArgumentException("Uniform '" + uniformName + "' must contain four numbers.");
                    }
                    float value = vector.get(component).getAsFloat();
                    if (!Float.isFinite(value)) {
                        throw new IllegalArgumentException("Uniform '" + uniformName + "' contains a non-finite value.");
                    }
                    values[slot * 4 + component] = value;
                }
                aliases.append("#define ").append(uniformName).append(" u_User[").append(slot).append("]\n");
                slot++;
            }
            sources.add("Texture2D u_Scene : register(t0);\n"
                    + "SamplerState u_SceneSampler : register(s0);\n"
                    + "cbuffer DirectUniforms : register(b0) { float4 u_Frame; float4 u_User[16]; };\n"
                    + aliases + "#line 1\n" + source);
            passUniforms.add(values);
        }
        String packName = manifest.has("name") && manifest.get("name").isJsonPrimitive()
                ? manifest.get("name").getAsString() : name;
        return new ShaderPack(packName, sources.toArray(String[]::new), passUniforms.toArray(float[][]::new));
    }

    private static JsonObject requireObject(JsonElement element, String what) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException("Shader pack '" + what + "' must be a JSON object.");
        }
        return element.getAsJsonObject();
    }

    private static boolean isNumber(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }

    private static void installDefaultPack(Path packRoot) throws IOException {
        Files.createDirectories(packRoot.resolve("shaders"));
        copyBundledResource("/assets/direct/shaderpacks/default/pack.json", packRoot.resolve("pack.json"));
        copyBundledResource("/assets/direct/shaderpacks/default/shaders/main.hlsl",
                packRoot.resolve("shaders").resolve("main.hlsl"));
    }

    private static void copyBundledResource(String resource, Path destination) throws IOException {
        try (InputStream input = NativeRenderer.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Bundled shader pack resource is missing: " + resource);
            }
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record ShaderPack(String name, String[] shaders, float[][] uniforms) {
    }
}