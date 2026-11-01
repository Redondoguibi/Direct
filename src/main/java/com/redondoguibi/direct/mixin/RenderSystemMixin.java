package com.redondoguibi.direct.mixin;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.mojang.blaze3d.systems.RenderSystem;
import com.redondoguibi.direct.NativeRenderer;

@Mixin(RenderSystem.class)
abstract class RenderSystemMixin {
    @Redirect(method = "flipFrame", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V"))
    private static void direct$present(long glfwWindow) {
        if (!NativeRenderer.renderIfEnabled(glfwWindow)) {
            GLFW.glfwSwapBuffers(glfwWindow);
        }
    }
}
