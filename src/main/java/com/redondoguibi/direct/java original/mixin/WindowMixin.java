package com.redondoguibi.direct.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.platform.Window;
import com.redondoguibi.direct.Dx12GlInterop;
import com.redondoguibi.direct.NativeRenderer;

@Mixin(Window.class)
abstract class WindowMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void direct$shutdown(CallbackInfo callbackInfo) {
        Dx12GlInterop.shutdown();
        NativeRenderer.shutdown();
    }
}
