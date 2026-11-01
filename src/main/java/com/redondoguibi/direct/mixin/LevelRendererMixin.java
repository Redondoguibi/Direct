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

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void direct$captureMatrices(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
            GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix,
            Matrix4f projectionMatrix, CallbackInfo callback) {
        Dx12GlInterop.captureLevelMatrices(camera, frustumMatrix, projectionMatrix);
    }

    @Inject(method = "renderLevel",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSectionLayer("
                            + "Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;"
                            + "Lorg/joml/Matrix4f;)V",
                    ordinal = 2, shift = At.Shift.AFTER))
    private void direct$compositeTerrain(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
            GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix,
            Matrix4f projectionMatrix, CallbackInfo callback) {
        Dx12GlInterop.renderTerrainOverlay();
    }
}