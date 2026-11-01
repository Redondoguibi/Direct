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

    // Spread chunk uploads over multiple frames to avoid large GPU submission spikes.
    public static final ModConfigSpec.IntValue MAX_MESH_UPLOADS_PER_FRAME = BUILDER
            .comment("Maximum number of compiled terrain sections uploaded to D3D12 per frame.")
            .defineInRange("maxMeshUploadsPerFrame", 32, 1, 512);

    public static final ModConfigSpec.IntValue MAX_MESH_UPLOAD_MIB_PER_FRAME = BUILDER
            .comment("Soft terrain upload budget per frame in MiB. One oversized section is still allowed.")
            .defineInRange("maxMeshUploadMiBPerFrame", 16, 1, 512);

    public static final ModConfigSpec.IntValue TERRAIN_RENDER_DISTANCE_PERCENT = BUILDER
            .comment("Direct terrain visibility distance as a percentage of Minecraft's configured render distance.")
            .defineInRange("terrainRenderDistancePercent", 100, 25, 100);

    public static final ModConfigSpec.BooleanValue USE_GPU_SEMAPHORE_SYNC = BUILDER
            .comment("Use shared GPU timeline semaphores for OpenGL/D3D12 synchronization when supported.",
                    "Disable to force the CPU synchronization fallback.")
            .define("useGpuSemaphoreSync", true);

    public static final ModConfigSpec.IntValue BC7_ENCODER_THREADS = BUILDER
            .comment("Maximum CPU threads used by the BC7 block-atlas encoder.",
                    "Lower values leave more CPU time for Minecraft; applied on resource reload.")
            .defineInRange("bc7EncoderThreads", 2, 1, 16);

    public static final ModConfigSpec.BooleanValue PARALLEL_SECTION_RECORDING = BUILDER
            .comment("Record opaque and cutout terrain command lists in parallel on a worker thread.")
            .define("parallelSectionRecording", true);

    public static final ModConfigSpec.BooleanValue USE_EXECUTE_INDIRECT = BUILDER
            .comment("Submit terrain draw commands through D3D12 ExecuteIndirect when supported.")
            .define("useExecuteIndirect", true);

    public static final ModConfigSpec.BooleanValue REPLACE_VANILLA_TERRAIN = BUILDER
            .comment("Replace vanilla solid/cutout chunk layers with Direct after its terrain capture is stable.",
                    "Experimental: custom geometry may not be represented, and baked lightmap/normal shading is not yet reproduced.")
            .define("replaceVanillaTerrain", false);

    public static final ModConfigSpec.IntValue UPLOAD_RING_BUFFER_MIB = BUILDER
            .comment("Persistent per-frame D3D12 upload-ring capacity shared by terrain section updates.")
            .defineInRange("uploadRingBufferMiB", 32, 16, 512);

    public static final ModConfigSpec.IntValue TERRAIN_ARENA_PAGE_MIB = BUILDER
            .comment("Size of each lazily allocated GPU terrain arena page.")
            .defineInRange("terrainArenaPageMiB", 16, 8, 256);

    public static final ModConfigSpec.IntValue MAX_TERRAIN_ARENA_MIB = BUILDER
            .comment("Maximum total GPU memory reserved for Direct terrain section buffers.")
            .defineInRange("maxTerrainArenaMiB", 256, 64, 4096);

    public static final ModConfigSpec.IntValue MAX_PENDING_MESH_MIB = BUILDER
            .comment("Soft backpressure limit for compiled terrain meshes waiting for GPU upload.")
            .defineInRange("maxPendingMeshMiB", 128, 64, 2048);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {
    }

}