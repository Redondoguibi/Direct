package com.redondoguibi.direct.optimization.mixin;

import java.util.List;
import java.util.function.Predicate;

import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.redondoguibi.direct.optimization.MultipartDeduplication;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.MultiPartBakedModel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * FerriteCore multipartDeduplication: during a model bake, a multipart model whose selector list
 * equals one already built (same cached predicates, same part models from the bake cache) is the
 * same model, so the existing instance is returned. The key is an immutable copy, because the
 * builder's list would otherwise stay mutable behind a shared key.
 */
@Mixin(MultiPartBakedModel.Builder.class)
abstract class MultiPartBuilderMixin {
    @WrapOperation(method = "build", at = @At(value = "NEW", target = "(Ljava/util/List;)Lnet/minecraft/client/resources/model/MultiPartBakedModel;"))
    private MultiPartBakedModel direct$deduplicate(List<Pair<Predicate<BlockState>, BakedModel>> selectors,
            Operation<MultiPartBakedModel> original) {
        return MultipartDeduplication.intern(List.copyOf(selectors), () -> original.call(selectors));
    }
}
