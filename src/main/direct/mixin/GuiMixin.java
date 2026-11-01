package com.redondoguibi.direct.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.redondoguibi.direct.Dx12GlInterop;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;

@Mixin(Gui.class)
abstract class GuiMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("HEAD"))
    private void direct$renderDx12Texture(GuiGraphics graphics, DeltaTracker deltaTracker,
            CallbackInfo callbackInfo) {
        Dx12GlInterop.renderBeforeGui();
    }
}
