package com.redondoguibi.direct;

import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

final class DirectStatusOverlay {
    private static volatile String status = "Vanilla (disabled)";

    private DirectStatusOverlay() {
    }

    static void register(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath("direct", "renderer_status"),
                DirectStatusOverlay::render);
    }

    static void setStatus(String next) {
        status = next;
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (ClientConfig.SHOW_STATUS.get()) {
            graphics.drawString(Minecraft.getInstance().font, "Direct: " + status, 6, 6, 0xFFE0E0E0, true);
        }
    }
}
