package com.redondoguibi.direct.mixin;


import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

}
