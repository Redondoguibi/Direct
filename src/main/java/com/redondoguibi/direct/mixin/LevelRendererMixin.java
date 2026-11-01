package com.redondoguibi.direct.mixin;

import org.joml.Matrix4f;
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
    @Inject(method = "renderSectionLayer", at = @At("HEAD"), cancellable = true)
    private void direct$replaceVanillaBaseLayers(RenderType renderType, double x, double y, double z,
            Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo callback) {
        if (Dx12GlInterop.shouldSkipVanillaLayer(renderType)) {
            callback.cancel();
        }
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void direct$captureMatrices(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
            GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix,
            Matrix4f projectionMatrix, CallbackInfo callback) {
        Dx12GlInterop.captureLevelMatrices(camera, frustumMatrix, projectionMatrix);
    }

    // Replacement mode must draw Direct before vanilla's first base layer, so those layers can be skipped.
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
            Dx12GlInterop.renderTerrainOverlay();
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