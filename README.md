# Direct

Direct is a Windows x86-64 native Direct3D 12 terrain renderer and render-pass extension platform for Minecraft Java 1.21.1 / NeoForge 21.1. It is not a shader-pack loader and does not provide Iris compatibility. HLSL shaders and shader-pack UX belong in separate mods that depend on Direct's public Java API.

The renderer is opt-in. Minecraft continues to use its OpenGL window and swap chain; Direct composites its offscreen terrain through `WGL_NV_DX_interop2` after vanilla solid/cutout terrain and before entity rendering. Vanilla depth remains available to entities, which, along with translucent terrain and the GUI/HUD, render above the Direct terrain. If the required WGL extension, native library, device, shader, or resource is unavailable, Direct logs the failure and leaves rendering to vanilla.

## Build and verification

Use JDK 21, CMake 3.24+, Visual Studio 2022 C++ tools, and the Windows SDK.

```powershell
.\gradlew.bat build test javadoc nativeSmokeTest
```

`build` compiles the Java mod and native DLL on Windows. The native smoke test creates a D3D12 WARP device, direct queue/list, and fence. `compileJava`, `test`, and `javadoc` can also run on Linux without building the Windows renderer. The Linux CI job runs only those Java checks and does not publish a JAR. The Windows CI job creates the distributable JAR and verifies that it contains `META-INF/native/windows-x86_64/direct_renderer.dll`; a JAR built manually on Linux does not include the Windows backend and must not be used as the DX12 release artifact.

The native DLL links Windows SDK libraries (`d3d11`, `d3d12`, `dxgi`, and `d3dcompiler`). D3D11On12 is used only to expose/copy the D3D12 color target for WGL interop; terrain geometry and extension passes are recorded and rendered by native D3D12.

## Renderer and capabilities

- Terrain section meshes are captured from vanilla compilation and uploaded into D3D12 vertex/index buffers. Each packed vertex occupies 16 bytes (uint16 position, RGBA8 color, and unorm16 UVs); source indices are expanded to 32-bit indices. The active native pipelines cover the `solid`, `cutout_mipped`, and `cutout` layers.
- Opaque and cutout command lists use two native D3D12 PSOs, a shared root signature, an atlas SRV descriptor heap, and a 64-slot shader-indexed descriptor table. A D3D12 pipeline library caches PSOs in memory for the active renderer lifetime.
- Opaque and cutout command lists can be recorded concurrently; the renderer falls back to one recording thread if worker creation fails. Both lists bind the color/depth targets explicitly with `OMSetRenderTargets` instead of `BeginRenderPass`: a D3D12 render pass may not span command lists, and the runtime removes a command list that violates this.
- Terrain atlas and section data are copied from upload heaps. Color/depth and atlas transitions are explicit. Atlas BC7 compression is configurable, enabled by default (`compressBlockAtlas=true`), and lossy; it is used where the texture dimensions support BC7. Animated block atlases are refreshed after vanilla advances their sprites, throttled by `animatedAtlasRefreshMillis` (250 ms by default); the current bridge rereads and uploads the complete atlas, so animation fidelity has a CPU/readback cost.
- The D3D12 output is imported into OpenGL via `WGL_NV_DX_interop2`. Direct polls the D3D12 completion fence without blocking before sampling the output on a later frame; incomplete frames are discarded, never sampled. When `GL_EXT_semaphore_win32` can import the shared completion fence, D3D12 waits on OpenGL's prior-frame signal on the GPU. Otherwise Direct uses a polled `GLsync` and a CPU fence signal after GL completes; this CPU fallback also waits for D3D12 terrain rendering before issuing the D3D11On12 copy, then signals the output fence only after the copy is flushed. A watchdog marks stalled/device-removed frames invalid; it never fabricates fence completion. Unsupported semaphore imports fall back to `GLsync`. `glFinish` is reserved for rare sync-object creation failure, resize, and teardown. After a renderer/device error, Direct returns to vanilla and retries initialization after a short cooldown.
- On initialization, the public API reports the adapter name, hardware/software adapter status, descriptor tier, pipeline-library availability, semaphore status, and WGL support. `renderPasses` is reported as `false` because Direct binds targets explicitly. The in-game status overlay shows whether Direct, CPU fallback, or vanilla rendering is active.

The current renderer captures only the `solid`, `cutout_mipped`, and `cutout` terrain layers into D3D12. Vanilla continues to render those layers by default and supplies the depth buffer; the experimental `replaceVanillaTerrain` option can suppress the corresponding vanilla draws only after capture is stable and the captured geometry is compatible. The asynchronous composite can be one frame behind and falls back to vanilla while a Direct output is incomplete. Block entities, entities, translucent terrain, particles, weather, the hand, and GUI/HUD remain vanilla OpenGL rendering; full coverage of those layers is not implemented. Passes can sample color but cannot sample the depth attachment yet. There is no Iris support.

## Configuration

Open Minecraft's mod configuration screen and select **Direct DX12**. The client options are:

- `enableDx12Preview` — enable Direct's native D3D12 terrain overlay (off by default; experimental).
- `compressBlockAtlas` — compress the D3D12 block atlas with BC7 (on by default).
- `animatedAtlasRefreshMillis` — minimum interval between animated-atlas refreshes (250 ms by default).
- `showRendererStatus` — show Direct's backend status in the HUD.

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
