package com.redondoguibi.direct.api;

import net.neoforged.bus.api.Event;

/** Fired once during client setup so dependent mods can register their initial render passes. */
public final class DirectRenderPassRegistrationEvent extends Event {
    private final DirectRenderService service;

    /**
     * Creates the registration event with the active service.
     *
     * @param service active extension service
     */
    public DirectRenderPassRegistrationEvent(DirectRenderService service) {
        this.service = service;
    }

    /**
     * Returns the public extension service.
     *
     * @return extension service
     */
    public DirectRenderService service() {
        return service;
    }
}
