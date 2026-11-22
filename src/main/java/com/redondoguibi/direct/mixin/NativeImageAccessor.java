package com.redondoguibi.direct.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import com.mojang.blaze3d.platform.NativeImage;

/** Address of the CPU pixels vanilla uploads with glTexSubImage2D; 0 once the image is closed. */
@Mixin(NativeImage.class)
public interface NativeImageAccessor {
    @Accessor("pixels")
    long direct$pixels();
}
