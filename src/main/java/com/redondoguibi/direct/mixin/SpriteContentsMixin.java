package com.redondoguibi.direct.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.mojang.blaze3d.platform.NativeImage;
import com.redondoguibi.direct.Dx12GlInterop;
import net.minecraft.client.renderer.texture.SpriteContents;

@Mixin(SpriteContents.class)
abstract class SpriteContentsMixin {
    @Shadow public abstract int width();
    @Shadow public abstract int height();

    @Inject(method = "upload(IIII[Lcom/mojang/blaze3d/platform/NativeImage;)V", at = @At("RETURN"))
    private void direct$changedRegion(int x, int y, int sourceX, int sourceY, NativeImage[] levels, CallbackInfo ci) {
        Dx12GlInterop.blockAtlasRegionUpdated(x, y, width(), height());
    }
}
