package com.redondoguibi.direct.mixin;

import java.nio.IntBuffer;
import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.redondoguibi.direct.backend.Dx12Shadow;

/** Phase 1 shadow backend: mirrors blaze3d state, textures and framebuffers (only with -Ddirect.phase1.shadow=true). */
@Mixin(value = GlStateManager.class, remap = false)
abstract class GlStateManagerShadowMixin {
    @Inject(method = "_enableBlend", at = @At("HEAD")) private static void direct$enableBlend(CallbackInfo ci) { Dx12Shadow.blend(true); }
    @Inject(method = "_disableBlend", at = @At("HEAD")) private static void direct$disableBlend(CallbackInfo ci) { Dx12Shadow.blend(false); }
    @Inject(method = "_blendFunc", at = @At("HEAD")) private static void direct$blendFunc(int src, int dst, CallbackInfo ci) { Dx12Shadow.blendFunc(src, dst); }
    @Inject(method = "_blendFuncSeparate", at = @At("HEAD"))
    private static void direct$blendFuncSeparate(int src, int dst, int srcA, int dstA, CallbackInfo ci) { Dx12Shadow.blendFuncSeparate(src, dst, srcA, dstA); }
    @Inject(method = "_blendEquation", at = @At("HEAD")) private static void direct$blendEquation(int mode, CallbackInfo ci) { Dx12Shadow.blendEquation(mode); }
    @Inject(method = "_enableDepthTest", at = @At("HEAD")) private static void direct$enableDepth(CallbackInfo ci) { Dx12Shadow.depthTest(true); }
    @Inject(method = "_disableDepthTest", at = @At("HEAD")) private static void direct$disableDepth(CallbackInfo ci) { Dx12Shadow.depthTest(false); }
    @Inject(method = "_depthFunc", at = @At("HEAD")) private static void direct$depthFunc(int func, CallbackInfo ci) { Dx12Shadow.depthFunc(func); }
    @Inject(method = "_depthMask", at = @At("HEAD")) private static void direct$depthMask(boolean flag, CallbackInfo ci) { Dx12Shadow.depthMask(flag); }
    @Inject(method = "_enableCull", at = @At("HEAD")) private static void direct$enableCull(CallbackInfo ci) { Dx12Shadow.cull(true); }
    @Inject(method = "_disableCull", at = @At("HEAD")) private static void direct$disableCull(CallbackInfo ci) { Dx12Shadow.cull(false); }
    @Inject(method = "_colorMask", at = @At("HEAD"))
    private static void direct$colorMask(boolean r, boolean g, boolean b, boolean a, CallbackInfo ci) { Dx12Shadow.colorMask(r, g, b, a); }
    @Inject(method = "_enableScissorTest", at = @At("HEAD")) private static void direct$enableScissor(CallbackInfo ci) { Dx12Shadow.scissor(true); }
    @Inject(method = "_disableScissorTest", at = @At("HEAD")) private static void direct$disableScissor(CallbackInfo ci) { Dx12Shadow.scissor(false); }
    @Inject(method = "_scissorBox", at = @At("HEAD"))
    private static void direct$scissorBox(int x, int y, int w, int h, CallbackInfo ci) { Dx12Shadow.scissorBox(x, y, w, h); }
    @Inject(method = "_viewport", at = @At("HEAD"))
    private static void direct$viewport(int x, int y, int w, int h, CallbackInfo ci) { Dx12Shadow.viewport(x, y, w, h); }
    @Inject(method = "_enablePolygonOffset", at = @At("HEAD")) private static void direct$enableOffset(CallbackInfo ci) { Dx12Shadow.polygonOffset(true); }
    @Inject(method = "_disablePolygonOffset", at = @At("HEAD")) private static void direct$disableOffset(CallbackInfo ci) { Dx12Shadow.polygonOffset(false); }
    @Inject(method = "_polygonOffset", at = @At("HEAD"))
    private static void direct$polygonOffset(float factor, float units, CallbackInfo ci) { Dx12Shadow.polygonOffset(factor, units); }
    @Inject(method = "_clearColor", at = @At("HEAD"))
    private static void direct$clearColor(float r, float g, float b, float a, CallbackInfo ci) { Dx12Shadow.clearColor(r, g, b, a); }
    @Inject(method = "_clearDepth", at = @At("HEAD")) private static void direct$clearDepth(double depth, CallbackInfo ci) { Dx12Shadow.clearDepth(depth); }
    @Inject(method = "_clear", at = @At("HEAD"), cancellable = true)
    private static void direct$clear(int mask, boolean check, CallbackInfo ci) {
        Dx12Shadow.clear(mask);
        if (Dx12Shadow.SKIP_GL && Dx12Shadow.presenting()) ci.cancel(); // DX12 alone renders: OpenGL's images are unused
    }
    @Inject(method = "_activeTexture", at = @At("HEAD")) private static void direct$activeTexture(int unit, CallbackInfo ci) { Dx12Shadow.activeTexture(unit); }
    @Inject(method = "_bindTexture", at = @At("HEAD")) private static void direct$bindTexture(int texture, CallbackInfo ci) { Dx12Shadow.bindTexture(texture); }
    @Inject(method = "_texParameter(IIF)V", at = @At("HEAD"))
    private static void direct$texParameterF(int target, int name, float value, CallbackInfo ci) { Dx12Shadow.texParameter(target, name, value); }
    @Inject(method = "_texParameter(III)V", at = @At("HEAD"))
    private static void direct$texParameterI(int target, int name, int value, CallbackInfo ci) { Dx12Shadow.texParameter(target, name, value); }
    @Inject(method = "_texImage2D", at = @At("HEAD"))
    private static void direct$texImage2D(int target, int level, int internal, int width, int height, int border, int format,
            int type, IntBuffer pixels, CallbackInfo ci) { Dx12Shadow.texImage2D(target, level, internal, width, height, format, type, pixels); }
    @Inject(method = "_texSubImage2D", at = @At("HEAD"))
    private static void direct$texSubImage2D(int target, int level, int x, int y, int width, int height, int format, int type,
            long pixels, CallbackInfo ci) { Dx12Shadow.texSubImage2D(target, level, x, y, width, height, format, type, pixels); }
    @Inject(method = "_upload", at = @At("HEAD"))
    private static void direct$upload(int level, int x, int y, int width, int height, NativeImage.Format format, IntBuffer pixels,
            Consumer<IntBuffer> output, CallbackInfo ci) {
        Dx12Shadow.upload(level, x, y, width, height, format.glFormat(), format.components(), pixels);
    }
    @Inject(method = "_pixelStore", at = @At("HEAD")) private static void direct$pixelStore(int name, int value, CallbackInfo ci) { Dx12Shadow.pixelStore(name, value); }
    @Inject(method = "_deleteTexture", at = @At("HEAD")) private static void direct$deleteTexture(int texture, CallbackInfo ci) { Dx12Shadow.deleteTexture(texture); }
    @Inject(method = "_deleteTextures", at = @At("HEAD"))
    private static void direct$deleteTextures(int[] textures, CallbackInfo ci) { for (int texture : textures) Dx12Shadow.deleteTexture(texture); }
    @Inject(method = "_glBindFramebuffer", at = @At("HEAD"))
    private static void direct$bindFramebuffer(int target, int framebuffer, CallbackInfo ci) { Dx12Shadow.bindFramebuffer(target, framebuffer); }
    @Inject(method = "_glFramebufferTexture2D", at = @At("HEAD"))
    private static void direct$framebufferTexture(int target, int attachment, int textureTarget, int texture, int level, CallbackInfo ci) {
        Dx12Shadow.framebufferTexture(target, attachment, textureTarget, texture);
    }
    @Inject(method = "_glBlitFrameBuffer", at = @At("HEAD"), cancellable = true)
    private static void direct$blitFramebuffer(int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, int mask,
            int filter, CallbackInfo ci) {
        Dx12Shadow.blitFramebuffer(sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, mask);
        if (Dx12Shadow.SKIP_GL && Dx12Shadow.presenting()) ci.cancel();
    }
    @Inject(method = "_glDeleteFramebuffers", at = @At("HEAD"))
    private static void direct$deleteFramebuffer(int framebuffer, CallbackInfo ci) { Dx12Shadow.deleteFramebuffer(framebuffer); }
}
