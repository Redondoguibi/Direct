package com.redondoguibi.direct.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.redondoguibi.direct.backend.Dx12Shadow;

/**
 * DX12 backend (phases 1 and 2): mirrors each VertexBuffer's mesh and replays its draws in DX12. Static buffers keep
 * their mesh in native storage; dynamic ones are copied per draw.
 */
@Mixin(VertexBuffer.class)
abstract class VertexBufferShadowMixin {
    @Shadow @Final private VertexBuffer.Usage usage;
    @Shadow private int vertexBufferId;
    @Shadow private int indexCount;
    @Unique private final Dx12Shadow.Mesh direct$mesh = new Dx12Shadow.Mesh();

    @Inject(method = "upload", at = @At("HEAD"))
    private void direct$shadowUpload(MeshData mesh, CallbackInfo ci) {
        if (((VertexBuffer) (Object) this).isInvalid()) return;
        direct$mesh.glBuffer = vertexBufferId;
        Dx12Shadow.upload(direct$mesh, mesh, usage == VertexBuffer.Usage.STATIC);
    }

    @Inject(method = "uploadIndexBuffer", at = @At("HEAD"))
    private void direct$shadowIndices(ByteBufferBuilder.Result result, CallbackInfo ci) {
        Dx12Shadow.uploadIndices(direct$mesh, result == null ? null : result.byteBuffer());
    }

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void direct$shadowDraw(CallbackInfo ci) {
        direct$mesh.glIndexCount = indexCount;
        Dx12Shadow.draw(direct$mesh);
        if (Dx12Shadow.SKIP_GL && Dx12Shadow.presenting()) ci.cancel(); // present mode: DX12 alone renders the frame
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void direct$shadowClose(CallbackInfo ci) { Dx12Shadow.close(direct$mesh); }
}
