package com.redondoguibi.direct.optimization.mixin;

import java.util.Set;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import net.minecraft.server.network.ServerPlayerConnection;

/**
 * Lithium alloc.entity_tracker: every tracked entity keeps the connections that can see it in
 * {@code Sets.newIdentityHashSet()} (an IdentityHashMap behind a set view). A fastutil reference set
 * has the same identity semantics and unspecified iteration order, iterates its backing array
 * directly on every broadcast, and allocates no map entry wrappers.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
abstract class TrackedEntityMixin {
    @Shadow @Final @Mutable
    private Set<ServerPlayerConnection> seenBy;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void direct$referenceSet(CallbackInfo callback) {
        this.seenBy = new ReferenceOpenHashSet<>(this.seenBy);
    }
}
