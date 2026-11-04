package com.redondoguibi.direct.mixin;

import org.joml.Matrix4f;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.redondoguibi.direct.Dx12GlInterop;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final
    private it.unimi.dsi.fastutil.objects.ObjectArrayList<net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection> visibleSections;

    @WrapMethod(method = "renderSectionLayer")
    private void direct$replaceTerrain(RenderType renderType, double x, double y, double z,
            Matrix4f frustumMatrix, Matrix4f projectionMatrix, Operation<Void> original) {
        boolean skip = renderType==RenderType.translucent()?Dx12GlInterop.renderTranslucentLayer():Dx12GlInterop.shouldSkipVanillaLayer(renderType);
        if (renderType == RenderType.solid() || renderType == RenderType.cutout() || renderType == RenderType.cutoutMipped())
            com.redondoguibi.direct.RendererMetrics.layer(skip);
        if (skip) {
            // Preserve the public NeoForge render-stage notification when bypassing the vanilla draw.
            var renderer = (LevelRenderer) (Object) this;
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            renderType.setupRenderState();
            try {
                net.neoforged.neoforge.client.ClientHooks.dispatchRenderStage(renderType, renderer,
                        frustumMatrix, projectionMatrix, renderer.getTicks(), minecraft.gameRenderer.getMainCamera(), renderer.getFrustum());
            } finally {
                renderType.clearRenderState();
            }
            return;
        }
        original.call(renderType, x, y, z, frustumMatrix, projectionMatrix);
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "renderSectionLayer",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;draw()V"))
    private void direct$countVanillaDraw(com.mojang.blaze3d.vertex.VertexBuffer buffer, Operation<Void> original) {
        if(!Dx12GlInterop.shouldSkipVanillaBuffer(buffer)){
            com.redondoguibi.direct.RendererMetrics.vanillaDraw();
            original.call(buffer);
        }
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;compileSections(Lnet/minecraft/client/Camera;)V",
            shift = At.Shift.AFTER))
    private void direct$submitTerrain(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
            GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix,
            Matrix4f projectionMatrix, CallbackInfo callback) {
        Dx12GlInterop.captureVisibleSections(visibleSections);
        if (Dx12GlInterop.shouldCompositeBeforeVanilla()) Dx12GlInterop.renderTerrainOverlay();
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void direct$captureMatrices(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
            GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix,
            Matrix4f projectionMatrix, CallbackInfo callback) {
        com.redondoguibi.direct.RendererMetrics.frame();
        Dx12GlInterop.captureLevelMatrices(camera, frustumMatrix, projectionMatrix);
    }

    // Composite current DX12 color and depth before deciding whether vanilla draws are necessary.
    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSectionLayer("
                            + "Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;"
                            + "Lorg/joml/Matrix4f;)V",
                    ordinal = 0, shift = At.Shift.BEFORE))
    private void direct$compositeBeforeVanillaReplacement(DeltaTracker deltaTracker, boolean renderBlockOutline,
            Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix,
            Matrix4f projectionMatrix, CallbackInfo callback) {
        if (Dx12GlInterop.shouldCompositeBeforeVanilla()) {
            Dx12GlInterop.compositeTerrainFrame();
        }
    }

    // Preview mode keeps vanilla solid/cutout layers; composite Direct after the third (cutout) layer.
    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSectionLayer("
                            + "Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;"
                            + "Lorg/joml/Matrix4f;)V",
                    ordinal = 2, shift = At.Shift.AFTER))
    private void direct$compositeAfterVanillaBaseLayers(DeltaTracker deltaTracker, boolean renderBlockOutline,
            Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix,
            Matrix4f projectionMatrix, CallbackInfo callback) {
        if (!Dx12GlInterop.shouldCompositeBeforeVanilla()) {
            Dx12GlInterop.renderTerrainOverlay();
        }
    }
}
