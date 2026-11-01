package com.redondoguibi.direct.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Internal bridge used by Direct's client renderer.
 *
 * Extensions should use {@link DirectRenderService} rather than this implementation helper.
 */
public final class DirectRenderApi implements DirectRenderService {
    /** Maximum number of extension passes accepted by the current native backend. */
    public static final int MAX_PASSES = 8;
    /** Number of packed float components reserved for each pass's HLSL uniform buffer. */
    public static final int MAX_UNIFORM_FLOATS = 60;
    private static final Pattern PASS_ID = Pattern.compile("[a-z0-9_.-]{1,96}");
    private static final DirectRenderApi INSTANCE = new DirectRenderApi();
    private final ConcurrentHashMap<String, DirectRenderPass> passes = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(true);
    private volatile List<DirectRenderPass> orderedPasses = List.of();
    private volatile DirectRenderCapabilities capabilities = DirectRenderCapabilities.unavailable();

    private DirectRenderApi() {
    }

    /**
     * Returns the singleton extension service.
     *
     * @return the shared service
     */
    public static DirectRenderService service() {
        return INSTANCE;
    }

    /**
     * Returns an immutable snapshot ordered for execution.
     *
     * @return registered passes
     */
    public static List<DirectRenderPass> snapshot() {
        return INSTANCE.passes();
    }

    /**
     * Atomically consumes the flag indicating that native pipelines need reconfiguration.
     *
     * @return whether the native pipelines need reconfiguration
     */
    public static boolean consumeDirty() {
        return INSTANCE.dirty.getAndSet(false);
    }

    /** Marks the current pass set for native pipeline reconfiguration. */
    public static void markDirty() {
        INSTANCE.dirty.set(true);
    }

    /**
     * Updates the capabilities snapshot exposed to extensions.
     *
     * @param next latest capability snapshot
     */
    public static void updateCapabilities(DirectRenderCapabilities next) {
        INSTANCE.capabilities = Objects.requireNonNull(next, "next");
    }

    /**
     * Creates an immutable, frame-scoped pass context.
     *
     * @param frameIndex frame index
     * @param width frame width
     * @param height frame height
     * @param elapsedSeconds elapsed renderer time
     * @param renderDistance render distance in blocks
     * @param passId pass id
     * @param passIndex pass index
     * @return frame-scoped context
     */
    public static DirectFrameContext frame(long frameIndex, int width, int height, float elapsedSeconds,
            float renderDistance, String passId, int passIndex) {
        DirectTextureHandle input = new DirectTextureHandle(passIndex == 0 ? "terrain-color" : "previous-pass-color",
                width, height, frameIndex);
        DirectTextureHandle output = new DirectTextureHandle("pass-color-" + passIndex,
                width, height, frameIndex);
        DirectTextureHandle depth = new DirectTextureHandle("terrain-depth", width, height, frameIndex);
        return new DirectFrameContext(frameIndex, width, height, elapsedSeconds, renderDistance,
                passId, passIndex, input, output, depth);
    }

    /**
     * Creates frame-wide metadata for NeoForge frame notifications.
     *
     * @param frameIndex frame index
     * @param width frame width
     * @param height frame height
     * @param elapsedSeconds elapsed renderer time
     * @param renderDistance render distance in blocks
     * @return frame-wide context
     */
    public static DirectFrameContext frame(long frameIndex, int width, int height, float elapsedSeconds,
            float renderDistance) {
        return frame(frameIndex, width, height, elapsedSeconds, renderDistance, "", -1);
    }

    /**
     * Validates and zero-pads a pass's uniform data to the native constant-buffer layout.
     *
     * @param pass registered pass
     * @param context frame context
     * @return fixed-size uniform array
     */
    public static float[] uniformValues(DirectRenderPass pass, DirectFrameContext context) {
        float[] padded = new float[MAX_UNIFORM_FLOATS];
        copyUniforms(pass, context, padded, 0);
        return padded;
    }

    /**
     * Validates a pass's uniforms and writes them into a native-sized slot, zero-padding the tail.
     *
     * @param pass registered pass
     * @param context frame context
     * @param destination destination array
     * @param destinationOffset start of this pass's slot
     */
    public static void copyUniforms(DirectRenderPass pass, DirectFrameContext context, float[] destination,
            int destinationOffset) {
        float[] values;
        try {
            values = pass.uniforms(context);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Direct extension pass " + pass.id()
                    + " failed to provide frame uniforms.", exception);
        }
        if (values == null) {
            throw new IllegalStateException("Direct extension pass " + pass.id() + " returned null uniforms.");
        }
        if (values.length > MAX_UNIFORM_FLOATS) {
            throw new IllegalArgumentException("Direct pass " + pass.id() + " returned more than "
                    + MAX_UNIFORM_FLOATS + " uniform floats.");
        }
        System.arraycopy(values, 0, destination, destinationOffset, values.length);
        if (values.length < MAX_UNIFORM_FLOATS) {
            java.util.Arrays.fill(destination, destinationOffset + values.length,
                    destinationOffset + MAX_UNIFORM_FLOATS, 0.0f);
        }
    }

    @Override
    public String apiVersion() {
        return API_VERSION;
    }

    @Override
    public DirectRenderCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public synchronized void registerPass(DirectRenderPass pass) {
        Objects.requireNonNull(pass, "pass");
        String id = Objects.requireNonNull(pass.id(), "pass id");
        String source = Objects.requireNonNull(pass.pixelShaderSource(), "pixel shader source");
        if (!PASS_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Direct pass id must match " + PASS_ID.pattern() + ".");
        }
        if (source.isBlank() || source.length() > 1_000_000) {
            throw new IllegalArgumentException("Direct pass HLSL source must be non-empty and at most 1 MB.");
        }
        DirectRenderPass previous = passes.put(id, pass);
        if (previous == null && passes.size() > MAX_PASSES) {
            passes.remove(id, pass);
            throw new IllegalArgumentException("Direct supports at most " + MAX_PASSES + " extension passes.");
        }
        refreshOrderedPasses();
        dirty.set(true);
    }

    @Override
    public synchronized boolean unregisterPass(String id) {
        Objects.requireNonNull(id, "id");
        boolean removed = passes.remove(id) != null;
        if (removed) {
            refreshOrderedPasses();
            dirty.set(true);
        }
        return removed;
    }

    @Override
    public List<DirectRenderPass> passes() {
        return orderedPasses;
    }

    private void refreshOrderedPasses() {
        ArrayList<DirectRenderPass> snapshot = new ArrayList<>(passes.values());
        snapshot.sort(Comparator.comparingInt(DirectRenderPass::order).thenComparing(DirectRenderPass::id));
        orderedPasses = List.copyOf(snapshot);
    }
}
