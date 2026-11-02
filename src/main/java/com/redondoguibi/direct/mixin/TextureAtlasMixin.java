package com.redondoguibi.direct.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.redondoguibi.direct.Dx12GlInterop;

import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.inventory.InventoryMenu;

@Mixin(TextureAtlas.class)
abstract class TextureAtlasMixin {
    @Shadow
    private List<?> animatedTextures;

    @Inject(method = "upload(Lnet/minecraft/client/renderer/texture/SpriteLoader$Preparations;)V",
            at = @At("RETURN"))
    private void direct$markAtlasDirty(SpriteLoader.Preparations preparations, CallbackInfo callback) {
        if (((TextureAtlas) (Object) this).location().equals(InventoryMenu.BLOCK_ATLAS)) {
            Dx12GlInterop.blockAtlasUpdated();
        }
    }

    @Inject(method = "cycleAnimationFrames()V", at = @At("RETURN"))
    private void direct$markAnimatedAtlasDirty(CallbackInfo callback) {
        TextureAtlas atlas = (TextureAtlas) (Object) this;
        if (!animatedTextures.isEmpty() && atlas.location().equals(InventoryMenu.BLOCK_ATLAS)) {
            Dx12GlInterop.blockAtlasUpdated();
        }
    }
}
