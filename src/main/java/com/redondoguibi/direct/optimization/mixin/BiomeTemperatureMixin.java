package com.redondoguibi.direct.optimization.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;

/**
 * Lithium world.temperature_cache / ModernFix remove_biome_temperature_cache: vanilla memoizes
 * {@code getHeightAdjustedTemperature} in a ThreadLocal 1024-entry linked map per biome and per
 * thread. The function is pure (biome climate settings, a fixed-seed noise and the position), so
 * calling it directly returns the same value, skips the ThreadLocal/hash/LRU bookkeeping and never
 * allocates those maps on worker threads.
 */
@Mixin(Biome.class)
abstract class BiomeTemperatureMixin {
    @Shadow
    private float getHeightAdjustedTemperature(BlockPos pos) {
        throw new AssertionError();
    }

    @Inject(method = "getTemperature", at = @At("HEAD"), cancellable = true)
    private void direct$uncachedTemperature(BlockPos pos, CallbackInfoReturnable<Float> callback) {
        callback.setReturnValue(this.getHeightAdjustedTemperature(pos));
    }
}
