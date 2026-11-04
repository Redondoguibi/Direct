package com.redondoguibi.direct.mixin;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.redondoguibi.direct.TerrainMeshStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VertexBuffer.class)
abstract class VertexBufferMixin {
    @Inject(method="upload",at=@At("RETURN"))
    private void direct$uploaded(MeshData mesh,CallbackInfo ci){
        if(!TerrainMeshStore.isCaptureEnabled())return;
        var buffer=(VertexBuffer)(Object)this;
        if(!buffer.isInvalid())TerrainMeshStore.vanillaUploaded(mesh,buffer);
    }
}
