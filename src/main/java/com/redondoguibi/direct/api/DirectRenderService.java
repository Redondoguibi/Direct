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
    String API_VERSION = "1.0.0";

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
}
