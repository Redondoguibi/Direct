package com.redondoguibi.direct;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLE_DX12 = BUILDER
            .comment("Enable the experimental DirectX 12 terrain overlay through OpenGL interop.",
                    "Takes priority over enableDx12ShaderPresent; both are never active together.")
            .define("enableDx12Preview", false);

    public static final ModConfigSpec.BooleanValue ENABLE_DX12_SHADER_PRESENT = BUILDER
            .comment("Present the captured OpenGL frame through a DirectX 12 swap chain using a shader pack.",
                    "Ignored while enableDx12Preview is enabled.")
            .define("enableDx12ShaderPresent", false);

    public static final ModConfigSpec.ConfigValue<String> SHADER_PACK = BUILDER
            .comment("Folder name in config/direct/shaderpacks (used by enableDx12ShaderPresent).")
            .define("shaderPack", "default", value -> value instanceof String name
                    && name.matches("[A-Za-z0-9_-]+"));

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {
    }

    /** O caminho de apresentação via swap chain só roda quando o interop está desligado. */
    public static boolean shaderPresentActive() {
        return ENABLE_DX12_SHADER_PRESENT.get() && !ENABLE_DX12.get();
    }
}