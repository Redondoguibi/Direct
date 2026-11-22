package com.redondoguibi.direct.mixin;

import com.mojang.blaze3d.platform.Window;
import com.redondoguibi.direct.PhaseZeroPresentation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Enabled only in the dedicated phase 0 presentation run. */
@Mixin(Window.class)
abstract class PhaseZeroWindowMixin {
    @Inject(method = "updateDisplay", at = @At("HEAD"), cancellable = true)
    private void direct$presentProbe(CallbackInfo callback) {
        if (PhaseZeroPresentation.takeOver((Window) (Object) this)) callback.cancel();
    }
}
