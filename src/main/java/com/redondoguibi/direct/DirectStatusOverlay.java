package com.redondoguibi.direct;

import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

final class DirectStatusOverlay {
    private static String status = "disabled";
    private static Object[] arguments = new Object[0];
    private static Component message = Component.translatable("direct.status.disabled");

    private DirectStatusOverlay() {
    }

    static void register(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath("direct", "renderer_status"),
                DirectStatusOverlay::render);
    }

    static void setStatus(String next, Object... args) {
        if (!next.equals(status) || !java.util.Arrays.equals(arguments, args)) {
            status = next;
            arguments = args;
            message = Component.translatable("direct.status." + next, args);
        }
    }

    static Component message() {
        if (!ClientConfig.ENABLE_DX12.get()) return Component.translatable("direct.status.disabled");
        var reason = DirectEnvironment.unavailableReason();
        if (reason != null) return reason;
        if (Minecraft.getInstance().level == null) return Component.translatable("direct.status.no_world");
        return message;
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (ClientConfig.SHOW_STATUS.get() && minecraft.level != null && !minecraft.options.hideGui
                && !minecraft.getDebugOverlay().showDebugScreen()) {
            String text = minecraft.font.plainSubstrByWidth("Direct: " + message().getString(), graphics.guiWidth() - 16);
            graphics.fill(4, 4, 12 + minecraft.font.width(text), 18, 0xA0000000);
            graphics.drawString(minecraft.font, text, 8, 7, 0xFFE0E0E0, true);
        }
    }
}
