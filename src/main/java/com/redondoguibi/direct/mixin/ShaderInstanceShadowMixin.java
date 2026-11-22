package com.redondoguibi.direct.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.redondoguibi.direct.backend.Dx12Shadow;

import net.minecraft.client.renderer.ShaderInstance;

/** Phase 1 shadow backend: the program in use and its sampler bindings. */
@Mixin(ShaderInstance.class)
abstract class ShaderInstanceShadowMixin implements Dx12Shadow.ShaderInstanceAccess {
    @Shadow @Final private Map<String, Object> samplerMap;

    @Override public Map<String, Object> direct$samplerMap() { return samplerMap; }

    @Inject(method = "apply", at = @At("HEAD"))
    private void direct$shadowApply(CallbackInfo ci) { Dx12Shadow.applied(this); }
}
