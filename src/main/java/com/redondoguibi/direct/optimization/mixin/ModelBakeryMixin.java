package com.redondoguibi.direct.optimization.mixin;

import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.redondoguibi.direct.optimization.MultipartDeduplication;

import net.minecraft.client.resources.model.ModelBakery;

/** Scopes multipart deduplication to one top-level model bake and reports what it shared. */
@Mixin(ModelBakery.class)
abstract class ModelBakeryMixin {
    @Inject(method = "bakeModels", at = @At("HEAD"))
    private void direct$beginDeduplication(ModelBakery.TextureGetter textureGetter, CallbackInfo callback) {
        MultipartDeduplication.begin();
    }

    @Inject(method = "bakeModels", at = @At("RETURN"))
    private void direct$endDeduplication(ModelBakery.TextureGetter textureGetter, CallbackInfo callback) {
        long[] counts = MultipartDeduplication.end();
        LoggerFactory.getLogger("Direct optimizations").info(
                "Multipart models: {} built, {} shared with an identical model ({} distinct).",
                counts[0], counts[1], counts[0] - counts[1]);
    }
}
