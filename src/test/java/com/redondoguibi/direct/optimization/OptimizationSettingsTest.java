package com.redondoguibi.direct.optimization;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OptimizationSettingsTest {
    @Test
    void everythingIsOnByDefaultAndUnknownMixinsNeverApply() {
        var settings = OptimizationSettings.resolve(new Properties(), id -> false);
        for (var feature : OptimizationSettings.Feature.values()) assertTrue(settings.enabled(feature), feature.key);
        assertTrue(settings.allowsMixin("SelectorMixin"));
        assertFalse(settings.allowsMixin("SomethingElseMixin"));
    }

    @Test
    void installedSourceModDisablesOnlyItsFeatures() {
        var settings = OptimizationSettings.resolve(new Properties(), "ferritecore"::equals);
        assertFalse(settings.enabled(OptimizationSettings.Feature.MULTIPART_DEDUPLICATION));
        assertFalse(settings.enabled(OptimizationSettings.Feature.EMPTY_NEIGHBOUR_TABLES));
        assertTrue(settings.enabled(OptimizationSettings.Feature.BIOME_TEMPERATURE));
        assertTrue(settings.decision(OptimizationSettings.Feature.MULTIPART_DEDUPLICATION).reason().contains("ferritecore"));
        var lithium = OptimizationSettings.resolve(new Properties(), "canary"::equals);
        assertFalse(lithium.enabled(OptimizationSettings.Feature.ENTITY_CLASS_MAP));
        assertTrue(lithium.enabled(OptimizationSettings.Feature.THREAD_PRIORITIES));
        assertFalse(OptimizationSettings.resolve(new Properties(), "smoothboot"::equals)
                .allowsMixin("IntegratedServerMixin"), "thread-priority mods own that change");
    }

    @Test
    void propertiesDisableSingleFeaturesOrEverything() {
        var properties = new Properties();
        properties.setProperty("modernfix.thread_priorities", "false");
        var settings = OptimizationSettings.resolve(properties, id -> false);
        assertFalse(settings.allowsMixin("UtilExecutorMixin"));
        assertFalse(settings.allowsMixin("IntegratedServerMixin"));
        assertTrue(settings.allowsMixin("BiomeTemperatureMixin"));
        properties.setProperty("enabled", "false");
        var off = OptimizationSettings.resolve(properties, id -> false);
        for (var feature : OptimizationSettings.Feature.values()) assertFalse(off.enabled(feature));
    }

    @Test
    void writesADefaultFileThatRoundTrips(@TempDir Path directory) throws Exception {
        var first = OptimizationSettings.load(directory, id -> false);
        Path file = directory.resolve(OptimizationSettings.FILE_NAME);
        assertTrue(Files.isRegularFile(file));
        Files.writeString(file, Files.readString(file).replace("lithium.entity_tracker=true", "lithium.entity_tracker=false"));
        var second = OptimizationSettings.load(directory, id -> false);
        assertTrue(first.enabled(OptimizationSettings.Feature.ENTITY_TRACKER));
        assertFalse(second.enabled(OptimizationSettings.Feature.ENTITY_TRACKER));
    }

    @Test
    void mixinConfigAndFeatureTableListTheSameMixins() throws Exception {
        Set<String> declared = new HashSet<>();
        try (var stream = getClass().getResourceAsStream("/direct.optimizations.mixins.json")) {
            assertNotNull(stream);
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String section : new String[] {"mixins", "client"})
                json.getAsJsonArray(section).forEach(element -> declared.add(element.getAsString()));
            assertEquals(OptimizationMixinPlugin.class.getName(), json.get("plugin").getAsString());
            assertFalse(json.get("required").getAsBoolean(), "an optimization must never block game start");
        }
        Set<String> owned = new HashSet<>();
        for (var feature : OptimizationSettings.Feature.values()) owned.addAll(feature.mixins);
        assertEquals(owned, declared);
        for (String mixin : declared) {
            assertNotNull(Class.forName("com.redondoguibi.direct.optimization.mixin." + mixin, false, getClass().getClassLoader()), mixin);
        }
    }
}
