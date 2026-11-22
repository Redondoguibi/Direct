package com.redondoguibi.direct.mixin;

import java.util.Map;
import java.util.function.IntSupplier;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.redondoguibi.direct.backend.Dx12Shadow;

import net.minecraft.client.renderer.EffectInstance;

/** Phase 1 shadow backend: post-processing programs (PostChain passes) and their sampler bindings. */
@Mixin(EffectInstance.class)
abstract class EffectInstanceShadowMixin implements Dx12Shadow.EffectInstanceAccess {
    @Shadow @Final private Map<String, IntSupplier> samplerMap;
    @Shadow @Final private String name;

    @Override public Map<String, IntSupplier> direct$samplerMap() { return samplerMap; }
    @Override public String direct$name() { return name; }

    @Inject(method = "apply", at = @At("HEAD"))
    private void direct$shadowApply(CallbackInfo ci) { Dx12Shadow.applied(this); }
}
