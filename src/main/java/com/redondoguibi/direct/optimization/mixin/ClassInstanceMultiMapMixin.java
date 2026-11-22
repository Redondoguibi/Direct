package com.redondoguibi.direct.optimization.mixin;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;

import net.minecraft.util.ClassInstanceMultiMap;

/**
 * Lithium collections.entity_by_type / entity_filtering. Entity sections keep one list per queried
 * class. Class equality is identity, so an identity map is equivalent to vanilla's HashMap (whose
 * iteration order was never specified). A class already present in the map passed vanilla's
 * {@code isAssignableFrom} check when it was inserted, so repeated lookups return the same
 * unmodifiable view without repeating the check or allocating the computeIfAbsent lambda.
 * First lookups and invalid classes still run the vanilla method (same list, same exception).
 */
@Mixin(ClassInstanceMultiMap.class)
abstract class ClassInstanceMultiMapMixin<T> {
    @Shadow @Final @Mutable
    private Map<Class<?>, List<T>> byClass;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void direct$identityMap(Class<T> baseClass, CallbackInfo callback) {
        this.byClass = new Reference2ReferenceOpenHashMap<>(this.byClass);
    }

    @Inject(method = "find", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <S> void direct$knownClass(Class<S> type, CallbackInfoReturnable<Collection<S>> callback) {
        List<T> list = this.byClass.get(type);
        if (list != null) callback.setReturnValue((Collection<S>) Collections.unmodifiableCollection((List) list));
    }
}
