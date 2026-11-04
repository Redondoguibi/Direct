# Direct

Direct is a Windows x86-64 native Direct3D 12 terrain renderer and render-pass extension platform for Minecraft Java 1.21.1 / NeoForge 21.1. It is not a shader-pack loader and does not provide Iris compatibility. HLSL shaders and shader-pack UX belong in separate mods that depend on Direct's public Java API.

The renderer is opt-in and remains experimental: no validated in-game FPS improvement is available for this version. With `enableDx12Preview=true` and `replaceVanillaTerrain=true`, Direct prepares terrain after vanilla section uploads, then imports its color and depth before the solid layer. Only buffers belonging to the successfully composed native subset are skipped; pending sections retain their vanilla draw without switching the whole terrain backend. Entities and later OpenGL passes use the imported depth. Optional native translucency runs after entities, importing the current scene first. Setting `replaceVanillaTerrain=false` selects the diagnostic overlay, which renders the terrain twice and is not the optimization mode. Renderer failures retain vanilla rendering.

## Build and verification

Use JDK 21, CMake 3.24+, Visual Studio 2022 C++ tools, and the Windows SDK.

```powershell
.\gradlew.bat build test javadoc nativeSmokeTest
```

On a machine with an OpenGL driver, also run `.\gradlew.bat graphicsTest`. This creates a hidden GL window and checks every RGBA texel rendered by the actual compositor shaders, including scale and vertical orientation. It is separate from the headless unit tests.

`build` compiles the Java mod and native DLL on Windows. The native smoke test creates a D3D12 WARP device, direct queue/list, and fence. `compileJava`, `test`, and `javadoc` can also run on Linux without building the Windows renderer. The Linux CI job runs only those Java checks and does not publish a JAR. The Windows CI job creates the distributable JAR and verifies that it contains `META-INF/native/windows-x86_64/direct_renderer.dll`; a JAR built manually on Linux does not include the Windows backend and must not be used as the DX12 release artifact.

The native DLL links Windows SDK libraries (`d3d11`, `d3d12`, `dxgi`, and `d3dcompiler`). D3D11On12 exposes the native color target to WGL when compatible, retaining a copied-color fallback; depth still uses a copied bridge. Terrain geometry and extension passes are recorded and rendered by native D3D12.

## Renderer and capabilities

Direct 1.5.3 reuses bounded capture workspaces, removes obsolete uploads before budget checks and consolidates the pending queue. See [research sources, implementation and allocation measurements](docs/referencias-otimizacao-1.5.3.md).

Direct 1.5.2 bypasses inactive chunk-capture work, speeds up sequential index generation and caches native translucent quad order for an unchanged camera position. See [scope, CPU measurement and validation](docs/chunks-1.5.2.md).

Direct 1.5.1 reduces terrain overdraw, compacts eligible GPU indices and removes duplicated section selection. See [measured results and validation limits](docs/terrain-1.5.1.md).

Direct 1.5.0 / API 1.4.0 adds optional material highlights, rain wetness, emissive surfaces and depth-based water absorption in the native terrain passes. [Controls, cost measurements and limits](docs/materiais-1.5.0.md). Existing API calls remain supported.

Direct 1.4.2 removes the intermediate color copy on compatible WGL drivers, retaining explicit synchronization and the copied bridge as fallback. It saves one RGBA8 image allocation and export copy, plus the color import copy when native translucency runs. See [measurements, fallback coverage and remaining game validation](docs/interop-cor-1.4.2.md). No in-game FPS gain is certified.

Direct 1.2.4 uses a single terrain color target and copies the actual D32 depth buffer through 11On12, eliminating the former shader-written depth export target. Invalidated frames are rejected before the CPU waits for their completion. See [measured workload and validation limits](docs/dx12-terrain-performance.md).

- Terrain section meshes are captured from vanilla compilation and uploaded into D3D12 vertex/index buffers. Each V2 vertex occupies 20 bytes: the prior 16-byte position/light/color/UV layout plus SNORM8 normal xyz and material/animation flags. Source indices are expanded to 32-bit. Native pipelines cover solid, both cutout layers, optional sorted translucency and sun shadows.
- Opaque and cutout command lists use two native D3D12 PSOs, a shared root signature, an atlas SRV descriptor heap, and a 64-slot shader-indexed descriptor table (63 atlas versions plus the lightmap). A D3D12 pipeline library caches PSOs in memory for the active renderer lifetime.
- Opaque and cutout command lists can be recorded concurrently; the renderer falls back to one recording thread if worker creation fails. Both lists bind the color/depth targets explicitly with `OMSetRenderTargets` instead of `BeginRenderPass`: a D3D12 render pass may not span command lists, and the runtime removes a command list that violates this.
- Terrain capture retains vanilla vertex colors/AO and both light coordinates inside the original 16-byte prefix. The native vertex shader samples the CPU-captured vanilla lightmap. The pixel shader applies vanilla fog distance/shape, smoothstep fog, color modulation and the layer-specific alpha cutoff. Counter-clockwise front faces and back-face culling are verified by the WGL integration test.
- Replacement uses the actual vanilla visible-section selection, including occlusion, with no additional spherical-distance rejection that could create holes. Missing, pending or incompatible visible meshes retain vanilla rendering. The selection and capture readiness are rechecked after compilation before composition.
- The full block atlas is uploaded on load/reload. Actual sprite uploads mark changed rectangles; subsequent animation updates read only those rectangles and their mipmaps. BC7 encoding of private pixel snapshots runs on a daemon worker, with block-aligned patches, reused buffers, reload generation checks and upload-ring backpressure. BC7 remains optional and lossy. The lightmap is captured from its CPU image only when vanilla updates it, avoiding a per-frame GL readback.
- Native D3D12 renders one color target and the actual depth buffer, without a shader-written depth export. D3D11On12 shares the acquired color target with WGL when registration exposes valid storage; otherwise it copies color. Depth is always copied. Acquire/release and Flush preserve queue order. Replacement waits for the current output fence at composition, then writes `gl_FragDepth`; it does not use a previous camera frame during movement. GPU semaphores or a real GLsync protect subsequent reuse. A watchdog rejects stalled/device-removed work; the optional translucent stage additionally completes the current GL scene capture before native import; this synchronization cost is measured separately.
- On initialization, the public API reports the adapter name, hardware/software adapter status, descriptor tier, pipeline-library availability, semaphore status, and WGL support. `renderPasses` is reported as `false` because Direct binds targets explicitly. The in-game status overlay shows whether Direct, CPU fallback, or vanilla rendering is active.

Direct 1.4.0 / API 1.3.0 supports native near sun shadows, foliage wind, translucent blending, water waves/refraction and bounded screen-space reflections. Raydiance requests these independently through `DirectTerrainEffects`; Direct owns their shaders and memory. Translucency retains vanilla on Fabulous/custom targets or when its index budget is exceeded. Without water/reflection requests, the second interop round trip is skipped unless `dx12Translucency=true`. Entities, GUI, sky and features not migrated remain OpenGL. Direct does not load Iris packs.

Replacement requires full terrain distance, compatible meshes and a successful current-frame import. Resource/device errors retain vanilla. The Intel UHD 630 driver tested here rejects direct D3D12 texture and fence imports despite advertising their GL extensions, so D3D11On12 remains the validated bridge. See [native contract and test limits](docs/native-shader-contract.md).

See [renderer validation](docs/renderer-validation.md) for test coverage and the remaining visual/performance limitations.

## Configuration

Open Minecraft's mod configuration screen and select **Direct DX12**. The client options are:

- `enableDx12Preview` — enable Direct's D3D12 renderer (off by default; experimental).
- `preferDirectColorInterop` — share native color when validated, falling back to copied color if incompatible (on by default; applied on backend initialization/resize).
- `replaceVanillaTerrain` — true selects experimental color/depth replacement; false selects the diagnostic overlay.
- `profileRenderer` — log FPS, bounded frame-time percentiles, CPU/GPU costs, atlas traffic and draw counts.
- `compressBlockAtlas` — compress the D3D12 block atlas with BC7 (on by default).
- `animatedAtlasRefreshMillis` — minimum interval between animated-atlas refreshes (250 ms by default).
- `showRendererStatus` — show Direct's backend status in the HUD.
- `dx12Translucency` — request native translucent blocks without water/reflection effects (off by default).
- `uploadRingBufferMiB` — bounded staging/sorted-index budget; increasing it consumes memory.

## Extension API

The public API is in `com.redondoguibi.direct.api`. Its semantic version (`DirectRenderService.API_VERSION`) is independent of the mod version. Compatible changes preserve behavior within the same API major version. The service provides:

- `DirectRenderService` for registration/removal and capability queries;
- `DirectRenderPass` for ordered HLSL full-screen passes and per-frame uniforms;
- `DirectFrameContext` and frame-scoped opaque texture handles;
- `DirectRenderPassRegistrationEvent` and read-only `DirectFrameRenderEvent` notifications.

An extension never receives `ID3D12Device`, queue, command list, or native pointers. Direct owns GPU objects, pass targets, resource barriers, synchronization, resize, and teardown. Passes run by ascending `order()`, then lexicographic pass id. They sample the preceding color output and write a distinct full-screen target. See the [dependent-mod developer guide](docs/dependent-mod-guide.md) for the HLSL contract, state and frame-lifetime rules, and a consumer example.

## CI and signed releases

GitHub Actions runs Java compile/tests/Javadoc on Linux and the full Windows build plus the WARP native smoke test on Windows. The Windows job uploads the mod and documentation artifacts. Tag pushes matching `v*` create a GitHub Release only after signing the versioned mod JAR.

Configure these repository Actions secrets for signed releases:

- `JAR_SIGNING_KEYSTORE_BASE64` — base64-encoded PKCS#12 signing keystore;
- `JAR_SIGNING_STORE_PASSWORD` — keystore password;
- `JAR_SIGNING_KEY_PASSWORD` — private-key password;
- `JAR_SIGNING_ALIAS` — signing key alias.

Signing credentials are never stored in the repository. A release job fails explicitly if required signing secrets are absent or JAR verification fails.


API 1.2 depth at HLSL t1 remains compatible. API 1.3 adds geometry effect requests, owner arbitration and feature queries. Native geometry channels are internal; post passes do not receive a material G-buffer.

Direct 1.4.1 tracks actual vanilla upload/commit ownership per section and prevents background compilation from invalidating the whole frame. See [chunk stability correction](docs/correcao-chunks-1.4.1.md) for tested scenarios and remaining client validation.
