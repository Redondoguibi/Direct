package com.redondoguibi.direct.optimization.mixin;

import java.util.BitSet;
import java.util.List;
import java.util.function.Predicate;

import org.apache.commons.lang3.tuple.Pair;
import javax.annotation.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.redondoguibi.direct.optimization.CopyOnWriteCache;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.MultiPartBakedModel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Thread safety for shared multipart models. Vanilla memoizes selector bits per state in a plain
 * fastutil map; with one model per state that map held a single key. A deduplicated model serves
 * every state of its block and is queried concurrently by the chunk-meshing workers, so the bits
 * come from a lock-free copy-on-write cache instead. The bits are computed exactly as vanilla does
 * (same predicate order, same indices); the vanilla map is no longer touched.
 */
@Mixin(MultiPartBakedModel.class)
abstract class MultiPartBakedModelMixin {
    @Shadow @Final
    private List<Pair<Predicate<BlockState>, BakedModel>> selectors;

    @Unique
    private final CopyOnWriteCache<BlockState, BitSet> direct$selectorBits = new CopyOnWriteCache<>();

    @Inject(method = "getSelectors", at = @At("HEAD"), cancellable = true)
    private void direct$sharedSelectors(@Nullable BlockState state, CallbackInfoReturnable<BitSet> callback) {
        BitSet bits = this.direct$selectorBits.get(state);
        if (bits == null) bits = this.direct$selectorBits.computeIfAbsent(state, this::direct$computeBits);
        callback.setReturnValue(bits);
    }

    @Unique
    private BitSet direct$computeBits(@Nullable BlockState state) {
        BitSet bits = new BitSet();
        for (int i = 0; i < this.selectors.size(); i++) {
            if (this.selectors.get(i).getLeft().test(state)) bits.set(i);
        }
        return bits;
    }
}
