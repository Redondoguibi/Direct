package com.redondoguibi.direct;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;


@Mod(value = Direct.MODID, dist = Dist.CLIENT)
public class DirectClient {
    public DirectClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        container.getEventBus().addListener(this::onClientSetup);
        container.getEventBus().addListener(DirectStatusOverlay::register);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Post event) -> Dx12GlInterop.idle());
        Direct.LOGGER.info("Direct DX12 client initialized.");
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(DirectRenderEventBridge::postRegistration);
    }
}
