package com.redondoguibi.direct.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

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

    @WrapMethod(method = COMPILE)
    private SectionCompiler.Results direct$captureTerrain(SectionPos sectionPos, RenderChunkRegion region,
            VertexSorting sorting, SectionBufferBuilderPack bufferPack,
            List<AddSectionGeometryEvent.AdditionalSectionRenderer> additionalRenderers,
            Operation<SectionCompiler.Results> original) {
        TerrainMeshStore.beginCapture(sectionPos);
        try {
            SectionCompiler.Results results = original.call(sectionPos, region, sorting, bufferPack,
                    additionalRenderers);
            TerrainMeshStore.capture(sectionPos, results);
            return results;
        } finally {
            // Also runs when vanilla compilation throws before returning Results.
            TerrainMeshStore.finishCaptureForCurrentThread();
        }
    }
}
