package com.redondoguibi.direct.mixin;

import org.joml.Matrix4f;
import java.nio.ByteBuffer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
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
    // Even when DX12 replaces the terrain color, vanilla must still populate OpenGL's
    // depth buffer: clouds, entities, translucent blocks and later GL passes depend on it.
    // Cancelling these calls leaves those passes with no terrain occlusion.
    @WrapMethod(method = "renderSectionLayer")
    private void direct$preserveVanillaDepth(RenderType renderType, double x, double y, double z,
            Matrix4f frustumMatrix, Matrix4f projectionMatrix, Operation<Void> original) {
        if (!Dx12GlInterop.shouldSkipVanillaLayer(renderType)) {
            original.call(renderType, x, y, z, frustumMatrix, projectionMatrix);
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer colorMask = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask);
            GL11.glColorMask(false, false, false, false);
            try {
                // Keep vanilla's depth test, depth writes and cutout discard intact.
                original.call(renderType, x, y, z, frustumMatrix, projectionMatrix);
            } finally {
                GL11.glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0,
                        colorMask.get(2) != 0, colorMask.get(3) != 0);
            }
        }
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void direct$captureMatrices(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
            GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix,
            Matrix4f projectionMatrix, CallbackInfo callback) {
        Dx12GlInterop.captureLevelMatrices(camera, frustumMatrix, projectionMatrix);
    }

    // Replacement composites Direct before the base layers; vanilla then writes depth without color.
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