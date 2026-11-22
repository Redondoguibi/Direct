package com.redondoguibi.direct.optimization;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Predicate;

/**
 * Early (pre-mixin) switches for Direct's general game optimizations. NeoForge's ModConfigSpec
 * loads after classes are transformed, so these live in {@code config/direct-optimizations.properties}.
 * A feature is skipped when the mod it was modelled on (or a port of it) is installed, so the
 * same code path is never patched twice. No Minecraft classes are referenced here.
 */
public final class OptimizationSettings {
    public static final String FILE_NAME = "direct-optimizations.properties";

    /** Each feature names its inspiration, the mods that already provide it and its mixins. */
    public enum Feature {
        BIOME_TEMPERATURE("lithium.biome_temperature", "Lithium world.temperature_cache",
                "Computes biome temperatures directly instead of through a per-thread 1024-entry cache.",
                List.of("lithium", "canary", "radium", "modernfix"), "BiomeTemperatureMixin"),
        ENTITY_CLASS_MAP("lithium.entity_class_map", "Lithium collections.entity_by_type / entity_filtering",
                "Identity map for per-class entity lists; repeated lookups skip the class check and lambda.",
                List.of("lithium", "canary", "radium"), "ClassInstanceMultiMapMixin"),
        ENTITY_TRACKER("lithium.entity_tracker", "Lithium alloc.entity_tracker",
                "Entity trackers keep their players in a fastutil identity set.",
                List.of("lithium", "canary", "radium"), "TrackedEntityMixin"),
        THREAD_PRIORITIES("modernfix.thread_priorities", "ModernFix perf.thread_priorities",
                "Background workers and the integrated server run below the render thread's priority.",
                List.of("modernfix", "smoothboot", "threadtweak"), "UtilExecutorMixin", "IntegratedServerMixin"),
        BLOCKSTATE_CACHE_ARRAYS("modernfix.blockstate_cache_arrays", "ModernFix perf.cache_blockstate_cache_arrays",
                "Block-state cache construction reuses enum arrays instead of copying them per state.",
                List.of("modernfix"), "BlockStateCacheMixin"),
        EMPTY_NEIGHBOUR_TABLES("modernfix.empty_neighbour_tables", "ModernFix perf.mojang_registry_size",
                "States without properties share one empty neighbour table.",
                List.of("modernfix", "ferritecore"), "StateHolderMixin"),
        MULTIPART_DEDUPLICATION("ferritecore.multipart_deduplication",
                "FerriteCore cacheMultipartPredicates / multipartDeduplication",
                "Multipart block models (fences, walls, panes, redstone) are shared across identical states.",
                List.of("ferritecore", "modernfix"),
                "SelectorMixin", "MultiPartBuilderMixin", "MultiPartBakedModelMixin", "ModelBakeryMixin");

        public final String key;
        public final String inspiration;
        public final String description;
        public final List<String> providedBy;
        public final List<String> mixins;

        Feature(String key, String inspiration, String description, List<String> providedBy, String... mixins) {
            this.key = key;
            this.inspiration = inspiration;
            this.description = description;
            this.providedBy = providedBy;
            this.mixins = List.of(mixins);
        }

        static Feature forMixin(String simpleName) {
            for (Feature feature : values()) if (feature.mixins.contains(simpleName)) return feature;
            return null;
        }
    }

    /** Why a feature is on or off; {@code null} reason means enabled. */
    public record Decision(boolean enabled, String reason) {}

    private final Map<Feature, Decision> decisions;
    private final boolean masterEnabled;

    private OptimizationSettings(Map<Feature, Decision> decisions, boolean masterEnabled) {
        this.decisions = Collections.unmodifiableMap(decisions);
        this.masterEnabled = masterEnabled;
    }

    /** Pure decision logic: properties plus a loaded-mod test, no file system. */
    public static OptimizationSettings resolve(Properties properties, Predicate<String> modLoaded) {
        boolean master = Boolean.parseBoolean(properties.getProperty("enabled", "true").trim());
        Map<Feature, Decision> decisions = new EnumMap<>(Feature.class);
        for (Feature feature : Feature.values()) {
            String value = properties.getProperty(feature.key, "true").trim();
            if (!master) {
                decisions.put(feature, new Decision(false, "all Direct optimizations disabled (enabled=false)"));
            } else if (!Boolean.parseBoolean(value)) {
                decisions.put(feature, new Decision(false, "disabled in " + FILE_NAME));
            } else {
                String provider = feature.providedBy.stream().filter(modLoaded).findFirst().orElse(null);
                decisions.put(feature, provider == null ? new Decision(true, null)
                        : new Decision(false, "already provided by installed mod '" + provider + "'"));
            }
        }
        return new OptimizationSettings(decisions, master);
    }

    /** Reads (creating with defaults when absent) the properties file. I/O failures keep defaults. */
    public static OptimizationSettings load(Path configDirectory, Predicate<String> modLoaded) {
        Properties properties = new Properties();
        Path file = configDirectory.resolve(FILE_NAME);
        try {
            if (Files.isRegularFile(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
            } else {
                writeDefaults(file);
            }
        } catch (IOException | RuntimeException ignored) {
            // Defaults (everything on, subject to installed-mod checks) remain in effect.
        }
        return resolve(properties, modLoaded);
    }

    static void writeDefaults(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("# Direct general game optimizations. Restart Minecraft after editing.\n");
            writer.write("# Each one is skipped automatically when the mod it is modelled on is installed.\n");
            writer.write("enabled=true\n");
            for (Feature feature : Feature.values()) {
                writer.write("\n# " + feature.description + "\n# Inspired by: " + feature.inspiration + "\n");
                writer.write(feature.key + "=true\n");
            }
        }
    }

    public boolean masterEnabled() { return masterEnabled; }
    public Decision decision(Feature feature) { return decisions.get(feature); }
    public boolean enabled(Feature feature) { return decisions.get(feature).enabled(); }

    /** Mixin class simple name → whether it may apply. Unknown mixins are never applied. */
    public boolean allowsMixin(String simpleName) {
        Feature feature = Feature.forMixin(simpleName);
        return feature != null && enabled(feature);
    }
}
