package com.redondoguibi.direct;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLE_DX12 = BUILDER
            .comment("Enable the experimental DirectX 12 terrain overlay through OpenGL interop.")
            .define("enableDx12Preview", false);

    public static final ModConfigSpec.ConfigValue<String> SHADER_PACK = BUILDER
            .comment("Folder name in config/direct/shaderpacks.")
            .define("shaderPack", "default", value -> value instanceof String name
                    && name.matches("[A-Za-z0-9_-]+"));

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {
    }
}
