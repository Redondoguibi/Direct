package com.redondoguibi.direct.api;

import java.util.List;

/**
 * Stable Java extension API for registering D3D12 render passes without exposing native devices.
 *
 * <p>The API version follows semantic versioning independently of the mod version. A compatible
 * implementation preserves existing methods and semantics within the same major version.</p>
 */
public interface DirectRenderService {
    /** Current public API semantic version. */
    String API_VERSION = "1.7.0";

    /** Queues a shaderpack terrain program and its 32 options on the render thread.
     * An empty source restores the built-in vertex and pixel shaders.
     * @param source HLSL terrain hooks, with includes already expanded
     * @param options live shaderpack uniform values, in manifest order
     * @param translucent whether Direct should render the pack's translucent terrain
     * @param sceneInputs whether to expose read-only color and depth captured before translucency
     * @param shadows whether to render a terrain shadow map for the pack
     */
    default void shaderPackGeometry(String source, float[] options, boolean translucent, boolean sceneInputs, boolean shadows) {}

    /** @return rejection for the current shaderpack geometry source, or an empty string */
    default String shaderPackGeometryFailure() { return ""; }


    /** Executes HLSL against the completed world color/depth, before hand and GUI (API 1.5).
     *
     * <p>API 1.7 world graph, declared by directives on their own lines in the sources (a pass without them
     * keeps the 1.5 chain contract):</p>
     * <ul>
     *   <li>{@code // direct:buffer <0-7> <format> [scale=<0.125-1>] [history] [clear]} declares a pack buffer;
     *       formats rgba8, rgba16f, rgba32f, r11g11b10f, rg16f, r16f, r32f, rgba16, rg8, r8. Every pass that
     *       declares a buffer must declare it identically.</li>
     *   <li>{@code // direct:outputs color,<i>,...} up to four render targets; {@code color} (the chain
     *       output) must come first. All outputs of one pass have the same size.</li>
     *   <li>{@code // direct:compute} runs {@code main} as a compute shader with its {@code numthreads}
     *       over the size of its outputs, which must be buffers (typed UAV stores).</li>
     *   <li>{@code // direct:terrain-depth} binds a terrain-only depth snapshot (before entities, particles
     *       and translucency) at t3.</li>
     * </ul>
     * <p>Bindings: t0 chain input, t1 scene depth, t2 original color, t3 terrain depth, t4-t11 buffers,
     * t12-t19 their previous frame (history buffers) or current content, u0-u7 compute outputs, b0 frame
     * data and uniforms, {@code b1 { float4 DirectTarget; float4 DirectBufferSize[8]; }} (width, height and
     * reciprocals), s0 linear clamp and s1 point clamp. The final pass writes the chain color in rgba8.</p>
     * @param sources one to sixteen HLSL passes
     * @param uniforms 252 floats per pass
     * @return true when the current scene was processed by DX12
     */
    default boolean renderWorld(String[] sources, float[] uniforms) { return false; }
    /** @return most recent world shader rejection, or empty */
    default String worldFailure(){return "";}
    /** Lets the world passes draw the sky and/or clouds themselves (API 1.7): Direct skips the vanilla
     * draws while the world stage keeps running, and restores them as soon as it stops. Release with false.
     * @param sky skip the vanilla sky (sky dome, sun, moon and stars)
     * @param clouds skip the vanilla clouds
     */
    default void worldReplacesVanilla(boolean sky, boolean clouds) {}
    /** Sets the shaderpack's native terrain fog multiplier; restore 1 on release.
     * @param distance multiplier in [0.25, 3]
     */
    default void shaderFogDistance(float distance) {}

    /**
     * Returns the active service. The service is available on the client after client setup.
     *
     * @return the active extension service
     */
    static DirectRenderService getInstance() {
        return DirectRenderApi.service();
    }

    /**
     * Returns the semantic API version implemented by this service.
     *
     * @return the API semantic version
     */
    String apiVersion();

    /**
     * Returns the latest hardware/backend capability snapshot.
     *
     * @return the current capability snapshot
     */
    DirectRenderCapabilities capabilities();

    /**
     * Registers or replaces a pass with the same id.
     *
     * @param pass extension pass to register
     * @throws IllegalArgumentException if the id, source, or uniform layout is invalid
     */
    void registerPass(DirectRenderPass pass);

    /**
     * Removes the pass with the given id.
     *
     * @param id pass id
     * @return whether a pass was removed
     */
    boolean unregisterPass(String id);

    /**
     * Returns an immutable snapshot in actual execution order.
     *
     * @return passes ordered for execution
     */
    List<DirectRenderPass> passes();

    /**
     * Returns the last rejected configuration error for a pass, or an empty string.
     * Registration is asynchronous; a failed replacement retains the previous working chain.
     *
     * @param id registered pass id
     * @return rejection message, empty when no rejection is recorded
     */
    default String passFailure(String id) {
        return "";
    }
    /**
     * Registers geometry effects. The lexically first owner wins; requests do not stack.
     * @param owner stable id matching the pass-id syntax; at most 64 owners
     * @param settings validated effect strengths
     */
    default void requestTerrainEffects(String owner, DirectTerrainEffects settings) {
        throw new UnsupportedOperationException("Geometry effects unavailable");
    }
    /**
     * Atomically requests geometry and materials for the same owner (API 1.4).
     * @param owner stable owner id
     * @param settings geometry settings
     * @param materials material settings
     */
    default void requestTerrainEffects(String owner, DirectTerrainEffects settings, DirectMaterialEffects materials) {
        if (!DirectMaterialEffects.NONE.equals(materials)) throw new UnsupportedOperationException("Material effects unavailable");
        requestTerrainEffects(owner, settings);
    }
    /**
     * Releases the client's request without disabling terrain. Release on unload/disable.
     * @param owner previously registered owner
     */
    default void releaseTerrainEffects(String owner) {}
    /**
     * Reports implementation features, independently gated from fullscreen passes.
     * A supported feature can still fall back for an incompatible target or an exhausted budget.
     * @return immutable feature names, empty when the native backend is unavailable
     */
    default java.util.Set<String> terrainFeatures() { return java.util.Set.of(); }
    /**
     * Returns the selected geometry effect owner.
     * @return selected owner, or empty if no request exists
     */
    default String terrainEffectOwner() { return ""; }
}
