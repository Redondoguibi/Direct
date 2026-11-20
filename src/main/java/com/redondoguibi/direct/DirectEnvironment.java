package com.redondoguibi.direct;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

/** Installation checks do not create a native device or touch the GL context. */
final class DirectEnvironment {
    private static final List<String> ALTERNATE_RENDERERS = List.of("sodium", "embeddium", "iris", "oculus", "optifine");
    private static boolean checked;
    private static Component problem;

    private DirectEnvironment() {}

    static Component unavailableReason() {
        if (!checked) {
            problem = inspect();
            checked = true;
        }
        return problem;
    }

    private static Component inspect() {
        if (!NativeLibrary.supportsPlatform(System.getProperty("os.name"), System.getProperty("os.arch"))) {
            return Component.translatable("direct.status.platform");
        }
        for (String id : ALTERNATE_RENDERERS) {
            if (ModList.get().isLoaded(id)) return Component.translatable("direct.status.conflict", id);
        }
        if (NativeLibrary.class.getResource("/META-INF/native/windows-x86_64/direct_renderer.dll") == null) {
            return Component.translatable("direct.status.missing_native");
        }
        return null;
    }
}
