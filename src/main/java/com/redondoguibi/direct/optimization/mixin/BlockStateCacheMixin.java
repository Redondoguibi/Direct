package com.redondoguibi.direct.optimization.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.SupportType;

/**
 * ModernFix perf.cache_blockstate_cache_arrays: every block state's cache constructor copies
 * {@code SupportType.values()} once per direction and {@code Direction.Axis.values()} once, i.e.
 * hundreds of thousands of throwaway arrays while a large pack loads. The loops only read the
 * arrays, so one shared copy gives identical results.
 */
@Mixin(targets = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase$Cache")
abstract class BlockStateCacheMixin {
    @Unique private static final SupportType[] DIRECT$SUPPORT_TYPES = SupportType.values();
    @Unique private static final Direction.Axis[] DIRECT$AXES = Direction.Axis.values();

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/SupportType;values()[Lnet/minecraft/world/level/block/SupportType;"))
    private SupportType[] direct$supportTypes() {
        return DIRECT$SUPPORT_TYPES;
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/Direction$Axis;values()[Lnet/minecraft/core/Direction$Axis;"))
    private Direction.Axis[] direct$axes() {
        return DIRECT$AXES;
    }
}
