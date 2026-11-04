package com.redondoguibi.direct.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.redondoguibi.direct.TerrainMeshStore;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class RenderSectionMixin {
    @Shadow
    public abstract BlockPos getOrigin();

    @Inject(method="setCompiled",at=@At("RETURN"))
    private void direct$committed(SectionRenderDispatcher.CompiledSection compiled,CallbackInfo ci){
        TerrainMeshStore.vanillaCommitted((SectionRenderDispatcher.RenderSection)(Object)this);
    }

    @Inject(method = "reset", at = @At("HEAD"))
    private void direct$removeTerrain(CallbackInfo callback) {
        TerrainMeshStore.remove(SectionPos.of(getOrigin()));
    }
}
