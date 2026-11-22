package com.redondoguibi.direct.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.redondoguibi.direct.Dx12GlInterop;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;

@Mixin(LightTexture.class)
abstract class LightTextureMixin {
    @Shadow @Final private DynamicTexture lightTexture;
    @Shadow private boolean updateLightTexture;

    @WrapMethod(method = "updateLightTexture")
    private void direct$captureCpuLightmap(float partialTick, Operation<Void> original) {
        boolean changed = updateLightTexture;
        original.call(partialTick);
        if (changed && lightTexture.getPixels() != null)
            Dx12GlInterop.lightmapUpdated(lightTexture.getPixels());
    }
}
