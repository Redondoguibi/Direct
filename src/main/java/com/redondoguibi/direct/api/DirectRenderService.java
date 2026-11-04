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
    String API_VERSION = "1.3.0";

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
