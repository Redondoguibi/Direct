package com.redondoguibi.direct.mixin;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.mojang.blaze3d.systems.RenderSystem;
import com.redondoguibi.direct.backend.Dx12Shadow;

/** Phase 1 present mode: DXGI shows the DX12 image, so OpenGL's back buffer is never swapped onto the window. */
@Mixin(RenderSystem.class)
abstract class RenderSystemShadowMixin {
    @Redirect(method = "flipFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V", remap = false))
    private static void direct$swap(long window) {
        if (!Dx12Shadow.presenting()) GLFW.glfwSwapBuffers(window);
    }
}
