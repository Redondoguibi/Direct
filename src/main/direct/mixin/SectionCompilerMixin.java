package com.redondoguibi.direct.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.blaze3d.vertex.VertexSorting;
import com.redondoguibi.direct.TerrainMeshStore;

import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;

@Mixin(SectionCompiler.class)
abstract class SectionCompilerMixin {
    private static final String COMPILE = "compile(Lnet/minecraft/core/SectionPos;"
            + "Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;"
            + "Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;"
            + "Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;";

    @Inject(method = COMPILE, at = @At("HEAD"))
    private void direct$beginCapture(SectionPos sectionPos, RenderChunkRegion region, VertexSorting sorting,
            SectionBufferBuilderPack bufferPack,
            List<AddSectionGeometryEvent.AdditionalSectionRenderer> additionalRenderers,
            CallbackInfoReturnable<SectionCompiler.Results> callback) {
        TerrainMeshStore.beginCapture(sectionPos);
    }

    @Inject(method = COMPILE, at = @At("RETURN"))
    private void direct$captureTerrain(SectionPos sectionPos, RenderChunkRegion region, VertexSorting sorting,
            SectionBufferBuilderPack bufferPack,
            List<AddSectionGeometryEvent.AdditionalSectionRenderer> additionalRenderers,
            CallbackInfoReturnable<SectionCompiler.Results> callback) {
        TerrainMeshStore.capture(sectionPos, callback.getReturnValue());
    }
}