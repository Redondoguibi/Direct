package com.redondoguibi.direct;

import com.redondoguibi.direct.api.DirectFrameRenderEvent;
import com.redondoguibi.direct.api.DirectRenderPassRegistrationEvent;
import com.redondoguibi.direct.api.DirectRenderService;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Internal event-bus bridge; extension mods should subscribe to the documented event types instead.
 */
final class DirectRenderEventBridge {
    private DirectRenderEventBridge() {
    }

    /** Posts the initial pass registration event. */
    static void postRegistration() {
        NeoForge.EVENT_BUS.post(new DirectRenderPassRegistrationEvent(DirectRenderService.getInstance()));
    }

    /**
     * Posts a frame notification.
     *
     * @param event frame event
     */
    static void postFrame(DirectFrameRenderEvent event) {
        NeoForge.EVENT_BUS.post(event);
    }
}
