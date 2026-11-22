package com.redondoguibi.direct;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;


@Mod(value = Direct.MODID, dist = Dist.CLIENT)
public class DirectClient {
    private static final KeyMapping MENU = new KeyMapping("key.direct.menu", GLFW.GLFW_KEY_F7, "key.categories.direct");

    public DirectClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new DirectScreen(parent));
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        container.getEventBus().addListener(this::onClientSetup);
        container.getEventBus().addListener(DirectStatusOverlay::register);
        container.getEventBus().addListener((RegisterKeyMappingsEvent event) -> event.register(MENU));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            Dx12GlInterop.idle();
            while (MENU.consumeClick()) {
                var minecraft = Minecraft.getInstance();
                if (minecraft.screen == null) minecraft.setScreen(new DirectScreen(null));
            }
        });
        NeoForge.EVENT_BUS.addListener(DirectClient::registerCommands);
        // LOWEST: other mods' drawing at the same stage is handed to the GPU by the same flush.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (RenderLevelStageEvent event) -> {
            if (Dx12GlInterop.FLUSH_STAGES.contains(event.getStage())) Dx12GlInterop.flushAfterStage();
        });
        Direct.LOGGER.info("Direct DX12 client initialized.");
    }

    private static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("direct")
                .executes(context -> {
                    context.getSource().sendSuccess(DirectStatusOverlay::message, false);
                    return 1;
                })
                .then(Commands.literal("menu").executes(context -> {
                    Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new DirectScreen(null)));
                    return 1;
                }))
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().sendSuccess(DirectStatusOverlay::message, false);
                    return 1;
                }))
                .then(Commands.literal("retry").executes(context -> {
                    Dx12GlInterop.requestRestart();
                    context.getSource().sendSuccess(() -> DirectScreen.text("retry_requested"), false);
                    return 1;
                })));
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(DirectRenderEventBridge::postRegistration);
    }
}
