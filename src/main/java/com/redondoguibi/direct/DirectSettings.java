package com.redondoguibi.direct;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;

/** A screen-local draft: navigating, resizing and Escape never write the live config. */
final class DirectSettings {
    static final List<ConfigValue<?>> VALUES = List.of(
            ClientConfig.ENABLE_DX12, ClientConfig.REPLACE_VANILLA_TERRAIN,
            ClientConfig.SHOW_STATUS, ClientConfig.PROFILE_RENDERER,
            ClientConfig.COMPRESS_BLOCK_ATLAS, ClientConfig.PREFER_DIRECT_COLOR_INTEROP, ClientConfig.DIRECT_BRIDGE,
            ClientConfig.MAX_MESH_UPLOADS_PER_FRAME, ClientConfig.MAX_MESH_UPLOAD_MIB_PER_FRAME,
            ClientConfig.MAX_MESH_UPLOAD_MICROS, ClientConfig.MAX_SECTION_ADOPTIONS_PER_FRAME,
            ClientConfig.TERRAIN_RENDER_DISTANCE_PERCENT, ClientConfig.USE_GPU_SEMAPHORE_SYNC, ClientConfig.FLUSH_OPENGL_STAGES,
            ClientConfig.ANIMATED_ATLAS_REFRESH_MILLIS, ClientConfig.CPU_ATLAS_ANIMATION, ClientConfig.BC7_ENCODER_THREADS,
            ClientConfig.PARALLEL_SECTION_RECORDING, ClientConfig.USE_EXECUTE_INDIRECT, ClientConfig.FACE_CULLING, ClientConfig.NATIVE_VERTEX_PACKING,
            ClientConfig.DX12_TRANSLUCENCY, ClientConfig.UPLOAD_RING_BUFFER_MIB,
            ClientConfig.TERRAIN_ARENA_PAGE_MIB, ClientConfig.MAX_TERRAIN_ARENA_MIB,
            ClientConfig.MAX_PENDING_MESH_MIB);
    private final Map<ConfigValue<?>, Object> draft = new LinkedHashMap<>();
    private final Map<ConfigValue<?>, Object> original = new LinkedHashMap<>();

    DirectSettings() {
        for (var value : VALUES) {
            draft.put(value, value.get());
            original.put(value, value.get());
        }
    }

    @SuppressWarnings("unchecked")
    <T> T get(ConfigValue<T> value) { return (T) draft.get(value); }
    <T> void set(ConfigValue<T> value, T next) { draft.put(value, next); }

    private static boolean preference(ConfigValue<?> value) {
        return value == ClientConfig.ENABLE_DX12 || value == ClientConfig.SHOW_STATUS || value == ClientConfig.PROFILE_RENDERER;
    }

    private static Map<ConfigValue<?>, Object> preset(int index) {
        Map<ConfigValue<?>, Object> values = new LinkedHashMap<>();
        VALUES.stream().filter(value -> !preference(value)).forEach(value -> values.put(value, value.getDefault()));
        if (index == 1) {
            values.put(ClientConfig.MAX_MESH_UPLOADS_PER_FRAME, 16);
            values.put(ClientConfig.MAX_MESH_UPLOAD_MIB_PER_FRAME, 8);
            values.put(ClientConfig.MAX_SECTION_ADOPTIONS_PER_FRAME, 16);
            values.put(ClientConfig.UPLOAD_RING_BUFFER_MIB, 16);
            values.put(ClientConfig.MAX_TERRAIN_ARENA_MIB, 128);
            values.put(ClientConfig.MAX_PENDING_MESH_MIB, 64);
            values.put(ClientConfig.BC7_ENCODER_THREADS, 1);
        } else if (index == 2) {
            values.put(ClientConfig.USE_GPU_SEMAPHORE_SYNC, false);
            values.put(ClientConfig.FLUSH_OPENGL_STAGES, false);
            values.put(ClientConfig.CPU_ATLAS_ANIMATION, false);
            values.put(ClientConfig.PREFER_DIRECT_COLOR_INTEROP, false);
            values.put(ClientConfig.DIRECT_BRIDGE, false);
            values.put(ClientConfig.PARALLEL_SECTION_RECORDING, false);
            values.put(ClientConfig.USE_EXECUTE_INDIRECT, false);
            values.put(ClientConfig.FACE_CULLING, false);
            values.put(ClientConfig.NATIVE_VERTEX_PACKING, false);
            values.put(ClientConfig.COMPRESS_BLOCK_ATLAS, false);
        }
        return values;
    }

    int presetIndex() {
        for (int i = 0; i < 3; i++) {
            if (draft.entrySet().containsAll(preset(i).entrySet())) return i;
        }
        return -1;
    }

    void applyPreset(int index) { draft.putAll(preset(index)); }

    boolean changed() { return !draft.equals(original); }

    @SuppressWarnings({"rawtypes", "unchecked"})
    boolean save() {
        Map<ConfigValue<?>, Object> previous = new LinkedHashMap<>();
        boolean restart = false;
        try {
            for (var entry : draft.entrySet()) {
                ConfigValue value = entry.getKey();
                // Preserve external config reloads for controls the user did not edit.
                if (entry.getValue().equals(original.get(value))) continue;
                previous.put(value, value.get());
                if (!entry.getValue().equals(value.get()) && value != ClientConfig.SHOW_STATUS && value != ClientConfig.PROFILE_RENDERER) restart = true;
                value.set(entry.getValue());
            }
            ClientConfig.SPEC.save();
        } catch (RuntimeException exception) {
            previous.forEach((value, old) -> ((ConfigValue) value).set(old));
            throw exception;
        }
        return restart;
    }
}
