package com.redondoguibi.direct.optimization.mixin;

import java.util.function.Predicate;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.renderer.block.model.multipart.Selector;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * FerriteCore cacheMultipartPredicates: a multipart block is baked once per state, and each bake
 * asked every selector to rebuild its condition predicate (new lambdas, split strings, parsed
 * values). The predicate depends only on the selector's condition and the block's state
 * definition, so it is built once per (selector, definition) and reused. Sharing these instances
 * is also what lets identical multipart models compare equal for deduplication.
 */
@Mixin(Selector.class)
abstract class SelectorMixin {
    /** {definition, predicate}, published together so readers never pair mismatched halves. */
    @Unique private volatile Object[] direct$cachedPredicate;

    @WrapMethod(method = "getPredicate")
    @SuppressWarnings("unchecked")
    private Predicate<BlockState> direct$cachePredicate(StateDefinition<Block, BlockState> definition,
            Operation<Predicate<BlockState>> original) {
        Object[] cached = this.direct$cachedPredicate;
        if (cached != null && cached[0] == definition) return (Predicate<BlockState>) cached[1];
        Predicate<BlockState> predicate = original.call(definition);
        this.direct$cachedPredicate = new Object[] {definition, predicate};
        return predicate;
    }
}
