package com.redondoguibi.direct;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLE_DX12 = BUILDER
            .comment("Enable DirectX 12 terrain rendering (replacement is experimental).",
                    "If native initialization or OpenGL interop is unavailable, vanilla rendering stays active.")
            .define("enableDx12Preview", false);

    public static final ModConfigSpec.BooleanValue COMPRESS_BLOCK_ATLAS = BUILDER
            .comment("Compress the DX12 block atlas to BC7 on load (less GPU memory bandwidth).",
                    "Lossy: may slightly change pixel-art colors. Applied on the next resource reload.")
            .define("compressBlockAtlas", true);

    public static final ModConfigSpec.BooleanValue SHOW_STATUS = BUILDER
            .comment("Show Direct's renderer state in the in-game HUD.")
            .define("showRendererStatus", true);

    public static final ModConfigSpec.BooleanValue PREFER_DIRECT_COLOR_INTEROP = BUILDER
            .comment("Share the native DX12 color target through D3D11On12/WGL instead of copying it.",
                    "Falls back to the original bridge when registration is incompatible. Applied on backend initialization/resize.")
            .define("preferDirectColorInterop", true);

    public static final ModConfigSpec.BooleanValue DIRECT_BRIDGE = BUILDER
            .comment("With shared native color, also share the depth image natively: an R32F D3D12 image replaces the",
                    "D3D11 depth copies (terrain export, translucency import) and the world stage samples OpenGL's",
                    "depth without an import copy. Falls back to the copied depth bridge when the driver rejects it.",
                    "Applied on backend initialization/resize.")
            .define("directBridge", true);

    // Spread chunk uploads over multiple frames to avoid large GPU submission spikes.
    public static final ModConfigSpec.IntValue MAX_MESH_UPLOADS_PER_FRAME = BUILDER
            .comment("Maximum number of compiled terrain sections uploaded to D3D12 per frame.")
            .defineInRange("maxMeshUploadsPerFrame", 32, 1, 512);

    public static final ModConfigSpec.IntValue MAX_MESH_UPLOAD_MIB_PER_FRAME = BUILDER
            .comment("Soft terrain upload budget per frame in MiB. One oversized section is still allowed.")
            .defineInRange("maxMeshUploadMiBPerFrame", 16, 1, 512);

    public static final ModConfigSpec.IntValue MAX_MESH_UPLOAD_MICROS = BUILDER
            .comment("Soft CPU time budget for section uploads per frame in microseconds; zero disables it.",
                    "At least one queued section may progress. Complements the byte/count budgets.")
            .defineInRange("maxMeshUploadMicros", 2000, 0, 20000);

    public static final ModConfigSpec.IntValue MAX_SECTION_ADOPTIONS_PER_FRAME = BUILDER
            .comment("Visible sections compiled before Direct was enabled that are queued for an asynchronous",
                    "rebuild per frame. Vanilla keeps drawing them until the rebuilt mesh is captured, so",
                    "activation never rebuilds the whole world at once.")
            .defineInRange("maxSectionAdoptionsPerFrame", 32, 1, 512);

    public static final ModConfigSpec.IntValue TERRAIN_RENDER_DISTANCE_PERCENT = BUILDER
            .comment("Direct terrain visibility distance as a percentage of Minecraft's configured render distance.")
            .defineInRange("terrainRenderDistancePercent", 100, 25, 100);

    public static final ModConfigSpec.BooleanValue USE_GPU_SEMAPHORE_SYNC = BUILDER
            .comment("Use shared GPU timeline semaphores for OpenGL/D3D12 synchronization when supported.",
                    "Disable to force the CPU synchronization fallback.")
            .define("useGpuSemaphoreSync", true);

    public static final ModConfigSpec.BooleanValue FLUSH_OPENGL_STAGES = BUILDER
            .comment("While DX12 terrain is active, hand recorded OpenGL work to the GPU at the end of each world",
                    "render stage (sky, entities, block entities, translucency, particles, weather, level).",
                    "Some drivers otherwise hold the whole frame until the buffer swap, and the next DX12",
                    "terrain frame then waits behind it while the render thread blocks. No effect in vanilla.")
            .define("flushOpenGlStages", true);

    public static final ModConfigSpec.IntValue ANIMATED_ATLAS_REFRESH_MILLIS = BUILDER
            .comment("Minimum interval between DX12 block-atlas refreshes caused by animated sprites.",
                    "Only changed sprite regions are read; BC7 compression runs off the render thread. Lower",
                    "values increase readback frequency. Animated blocks update at this rate.")
            .defineInRange("animatedAtlasRefreshMillis", 250, 50, 2000);

    public static final ModConfigSpec.BooleanValue CPU_ATLAS_ANIMATION = BUILDER
            .comment("Copy animated sprite frames into the DX12 block atlas from the CPU images Minecraft uploads",
                    "to OpenGL, instead of reading the changed regions back from OpenGL. Each region is read",
                    "from OpenGL once, as its baseline; later frames copy the same bytes OpenGL receives.",
                    "Disable for mods that write the block atlas outside vanilla sprite uploads.")
            .define("cpuAtlasAnimation", true);

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

    public static final ModConfigSpec.BooleanValue FACE_CULLING = BUILDER
            .comment("Group opaque/cutout quads by exact facing and skip groups that are entirely back-facing",
                    "for the current camera, instead of vertex-shading them for the rasterizer to discard.",
                    "Layers with active wind/wave animation are never culled. Output is unchanged.")
            .define("faceCulling", true);

    public static final ModConfigSpec.BooleanValue NATIVE_VERTEX_PACKING = BUILDER
            .comment("Convert captured chunk meshes to Direct's vertex format in C++ on the meshing threads.",
                    "Byte-identical to the Java converter, which remains the fallback.")
            .define("nativeVertexPacking", true);

    public static final ModConfigSpec.BooleanValue REPLACE_VANILLA_TERRAIN = BUILDER
            .comment("Replace vanilla solid/cutout terrain with DX12 color AND depth when the current visible set is complete.",
                    "Experimental until in-game visual and performance validation; false enables the diagnostic overlay.")
            .define("replaceVanillaTerrain", true);

    public static final ModConfigSpec.BooleanValue DX12_TRANSLUCENCY = BUILDER
            .comment("Render translucent blocks in DX12 even without a requested water/reflection effect.",
                    "Water/reflection requests enable this stage automatically. Off avoids a second interop round trip when unused.")
            .define("dx12Translucency", false);

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
            .comment("Soft warning threshold for coalesced, committed meshes waiting for GPU upload.",
                    "The render thread never blocks waiting for this queue; per-frame uploads remain budgeted.")
            .defineInRange("maxPendingMeshMiB", 128, 64, 2048);

    public static final ModConfigSpec.BooleanValue PROFILE_RENDERER = BUILDER
            .comment("Record optional CPU/GPU costs, FPS and recent frame-time percentiles every 60 seconds.","Appends logs/direct-performance.csv; bounded 16384-sample window, no per-frame disk writes.")
            .define("profileRenderer", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {
    }

}
