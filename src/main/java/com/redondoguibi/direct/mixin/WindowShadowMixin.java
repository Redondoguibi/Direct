package com.redondoguibi.direct.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.platform.Window;
import com.redondoguibi.direct.backend.Dx12Shadow;

/** Phase 1 shadow backend: the frame is complete when it is presented. */
@Mixin(Window.class)
abstract class WindowShadowMixin {
    @Inject(method = "updateDisplay", at = @At("HEAD"))
    private void direct$shadowFrame(CallbackInfo ci) { Dx12Shadow.endFrame(); }
}
