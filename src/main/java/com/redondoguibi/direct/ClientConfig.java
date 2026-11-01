package com.redondoguibi.direct;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLE_DX12 = BUILDER
            .comment("Enable the DirectX 12 terrain renderer and registered extension passes.",
                    "If native initialization or OpenGL interop is unavailable, vanilla rendering stays active.")
            .define("enableDx12Preview", false);

    public static final ModConfigSpec.BooleanValue COMPRESS_BLOCK_ATLAS = BUILDER
            .comment("Compress the DX12 block atlas to BC7 on load (less GPU memory bandwidth).",
                    "Lossy: may slightly change pixel-art colors. Applied on the next resource reload.")
            .define("compressBlockAtlas", true);

    public static final ModConfigSpec.BooleanValue SHOW_STATUS = BUILDER
            .comment("Show Direct's renderer state in the in-game HUD.")
            .define("showRendererStatus", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {
    }

}