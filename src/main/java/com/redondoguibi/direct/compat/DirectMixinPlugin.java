package com.redondoguibi.direct.compat;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Avoid applying vanilla terrain injection points to a different terrain renderer. */
public final class DirectMixinPlugin implements IMixinConfigPlugin {
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".PhaseZeroWindowMixin") && !Boolean.getBoolean("direct.phase0.present")) {
            return false;
        }
        var mods = FMLLoader.getLoadingModList();
        if (mods == null) return true;
        for (String id : List.of("sodium", "embeddium", "iris", "oculus", "optifine")) {
            if (mods.getModFileById(id) != null) return false;
        }
        return true;
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String name, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String name, ClassNode node, String mixin, IMixinInfo info) {}
}
