package com.redondoguibi.direct.mixin;


import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.redondoguibi.direct.Dx12GlInterop;

import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.inventory.InventoryMenu;

@Mixin(TextureAtlas.class)
abstract class TextureAtlasMixin {
    @Inject(method = "upload(Lnet/minecraft/client/renderer/texture/SpriteLoader$Preparations;)V",
            at = @At("RETURN"))
    private void direct$markAtlasDirty(SpriteLoader.Preparations preparations, CallbackInfo callback) {
        if (((TextureAtlas) (Object) this).location().equals(InventoryMenu.BLOCK_ATLAS)) {
            Dx12GlInterop.blockAtlasUpdated((TextureAtlas)(Object)this);
        }
    }

    /** Sprite images are closed right after this; recorded CPU sources must not outlive them. */
    @Inject(method = "clearTextureData()V", at = @At("HEAD"))
    private void direct$forgetSprites(CallbackInfo callback) {
        if (((TextureAtlas) (Object) this).location().equals(InventoryMenu.BLOCK_ATLAS)) {
            Dx12GlInterop.blockAtlasCleared();
        }
    }

    /** Sprite uploads inside this call target this atlas; no GL binding query is needed to recognise them. */
    @WrapMethod(method = "cycleAnimationFrames()V")
    private void direct$animationScope(Operation<Void> original) {
        int previous = Dx12GlInterop.enterAtlasAnimationScope(
                ((TextureAtlas) (Object) this).location().equals(InventoryMenu.BLOCK_ATLAS));
        try {
            original.call();
        } finally {
            Dx12GlInterop.exitAtlasAnimationScope(previous);
        }
    }

}
