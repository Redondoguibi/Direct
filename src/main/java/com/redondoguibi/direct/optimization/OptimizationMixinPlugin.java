package com.redondoguibi.direct.optimization;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Gates Direct's optimization mixins (independent of the terrain renderer's own plugin, so they
 * also apply when another terrain renderer disables Direct's rendering hooks) and records which
 * ones were actually applied.
 */
public final class OptimizationMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Direct optimizations");
    private static volatile OptimizationSettings settings;
    private static final Map<String, String> APPLIED = new ConcurrentHashMap<>();

    /** Settings resolved at mixin load; {@code null} before that (e.g. in unit tests). */
    public static OptimizationSettings settings() { return settings; }

    /** Applied mixin simple name → target class. */
    public static Map<String, String> applied() { return Map.copyOf(APPLIED); }

    @Override
    public void onLoad(String mixinPackage) {
        Path configDirectory;
        try {
            configDirectory = FMLPaths.CONFIGDIR.get();
        } catch (RuntimeException exception) {
            configDirectory = Path.of("config");
        }
        var mods = FMLLoader.getLoadingModList();
        OptimizationSettings resolved = OptimizationSettings.load(configDirectory,
                id -> mods != null && mods.getModFileById(id) != null);
        settings = resolved;
        for (OptimizationSettings.Feature feature : OptimizationSettings.Feature.values()) {
            var decision = resolved.decision(feature);
            if (decision.enabled()) LOGGER.info("{}: enabled ({})", feature.key, feature.inspiration);
            else LOGGER.info("{}: off, {}", feature.key, decision.reason());
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        OptimizationSettings current = settings;
        return current != null && current.allowsMixin(simpleName(mixinClassName));
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        String name = simpleName(mixinClassName);
        if (APPLIED.put(name, targetClassName) == null) LOGGER.info("Applied {} to {}", name, targetClassName);
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
