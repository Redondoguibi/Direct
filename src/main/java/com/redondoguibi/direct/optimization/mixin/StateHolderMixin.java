package com.redondoguibi.direct.optimization.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Table;

import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * ModernFix perf.mojang_registry_size: a state without properties keeps its own empty
 * {@code HashBasedTable} (plus its backing LinkedHashMap) forever. Vanilla only reads the table
 * after {@code populateNeighbours}; lookups on the shared empty ImmutableTable return the same
 * {@code null}, so {@code setValue} still throws the same exception.
 */
@Mixin(StateHolder.class)
abstract class StateHolderMixin<O, S> {
    @Shadow
    private Table<Property<?>, Comparable<?>, S> neighbours;

    @Inject(method = "populateNeighbours", at = @At("RETURN"))
    private void direct$shareEmptyTable(Map<Map<Property<?>, Comparable<?>>, S> possibleStateMap, CallbackInfo callback) {
        if (this.neighbours instanceof HashBasedTable<?, ?, ?> && this.neighbours.isEmpty()) {
            this.neighbours = ImmutableTable.of();
        }
    }
}
