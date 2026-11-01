# Direct

Direct is a Windows-only native Direct3D 12 rendering prototype for Minecraft Java 1.21.1 / NeoForge 21.1. It does not depend on Iris and does not provide Iris compatibility.

> **Current scope:** The opt-in renderer captures compiled Minecraft chunk meshes for the solid and cutout block layers, uploads their positions, colors, UVs and indices to native buffers, and draws them with a Direct3D pipeline over the DX12 offscreen texture. The block atlas is copied from OpenGL when it is loaded or reloaded. The result is imported into OpenGL with `WGL_NV_DX_interop2` and drawn before vanilla GUI rendering, so Minecraft continues to present through its existing OpenGL swap chain and the HUD remains above the overlay. It remains disabled by default.

## Build

Use JDK 21, CMake 3.24+, and the Visual Studio 2022 C++ build tools with the Windows SDK. On Windows, `gradlew.bat build` configures and builds `direct_renderer.dll`, then packages it in the mod JAR. `compileJava` can be used without the native toolchain. Other operating systems can build Java sources, but cannot package or run the DX12 backend.

The native code links only Windows SDK libraries (`d3d11`, `d3d12`, `dxgi`, and `d3dcompiler`). HLSL is compiled at runtime with `D3DCompile`; no additional runtime library is bundled.

## DX12/OpenGL interop

The renderer runs synchronously on Minecraft's render thread, from the head of `Gui.render`. DX12 creates and clears an offscreen texture; `D3D11On12` exposes it to D3D11 because `WGL_NV_DX_interop2` imports D3D10/11 resources rather than D3D12 resources directly. The driver did not expose the wrapped resource's contents to GL sampling, so the bridge copies it into a native D3D11 texture before registration. This adds a GPU copy but no CPU pixel transfer for the rendered frame. The block atlas is a separate one-time CPU readback on resource load/reload, so animated atlas frames are not refreshed yet. The active OpenGL driver must advertise `WGL_NV_DX_interop2`; otherwise Direct logs the missing extension and leaves vanilla rendering untouched, without trying another path.

When the active driver exposes `GL_EXT_semaphore_win32`, Direct imports two shared D3D12 fences as OpenGL timeline semaphores. D3D11On12 records the frame, flushes it to the shared D3D12 queue, and queues a ready-fence signal without waiting on the CPU. OpenGL queues a wait for that value before sampling the imported texture; after the fullscreen draw and WGL unlock, it signals a separate GL-done fence. The D3D12 queue waits on that GL signal before writing the next frame. The separate, monotonically increasing timelines provide wait-before-signal behavior without a per-frame CPU fence wait or `glClientWaitSync`.

The semaphore is optional: if export/import or an OpenGL semaphore operation fails, Direct logs the reason, CPU-waits for the relevant fence values, calls `glFinish` before CPU-signaling the GL-done fence, and continues in the serialized fallback path. A native watchdog monitors each D3D12 ready value; after five seconds without completion it attempts to CPU-signal that value so an OpenGL timeline wait is released, logs the fault, and disables asynchronous synchronization. CPU fallback waits are also bounded to five seconds and attempt to signal the affected fence on timeout. These exceptional paths can present a stale frame; a device-removal failure may also prevent CPU signaling. Shutdown still waits for outstanding GPU work before releasing shared objects. WGL interop lock/unlock calls remain necessary to transfer the D3D11 import texture between APIs and may have driver-side synchronization costs.

The terrain path is currently implemented through D3D11On12, not native D3D12 graphics command lists. Chunk mesh extraction/compaction already runs in the section compiler's worker context; GPU resource creation and immediate-context draws happen on the Minecraft render thread. D3D11 shaders, input layout, sampler and constant buffer are created once and reused, but there are no D3D12 PSOs, command lists, pipeline library, descriptor heap, or explicit D3D12 render passes in this path. The current single block-atlas binding and one contiguous terrain pass avoid per-section texture rebinding; implementing D3D12 bindless descriptors, parallel command-list recording, PSO libraries, or render-pass load/store optimization requires replacing this D3D11On12 terrain pipeline with a native D3D12 renderer. The current 24-byte vertex contains position, packed color and UV; packed color is retained for vanilla per-vertex shading, and atlas compression is not applied.

## Shader packs

The bundled `default` pack can be copied to `config/direct/shaderpacks/default` for customization. The `shaderPack` setting and HLSL pass loader belong to the separate DX12 shader-preview prototype; the active terrain overlay uses its built-in HLSL and ignores that setting.

The shader-pack prototype contains `pack.json` and HLSL source files. Passes execute in manifest order, each sampling the preceding pass output:

```json
{
  "format": 1,
  "name": "My pack",
  "passes": [
    {
      "name": "color",
      "shader": "shaders/color.hlsl",
      "uniforms": {
        "u_Tint": [1.0, 0.8, 0.6, 1.0]
      }
    }
  ]
}
```

Each pixel shader must define `float4 main(float4 position : SV_Position) : SV_Target0`. Direct supplies `u_Scene` (the prior scene/pass image), `u_SceneSampler` (linear, clamp), and `u_Frame` in a `b0` constant buffer: `.x` is elapsed seconds, `.y` and `.z` are framebuffer width and height, and `.w` is the frame number. OpenGL's bottom-up pixel rows mean shaders should sample with `float2(position.x / u_Frame.y, 1.0 - position.y / u_Frame.z)`. Every configured uniform is a `float4`; Direct assigns slots in JSON order and makes the configured name an HLSL alias to `u_User[slot]`. A pack may have 1–8 passes and each pass may define up to 16 uniforms. Passes are sequential: each pass samples the prior pass output, and only the final pass is presented. Shader paths are restricted to files beneath the pack directory.

This initial format has no depth access, Minecraft camera/world uniforms, or arbitrary texture inputs. Pass-to-pass color sampling is supported; it is not yet a general post-processing graph. The current terrain overlay still uses its built-in HLSL and does not run these shader-pack passes.

## Limitations

- Only opaque and cutout chunk layers are currently captured. The renderer does not yet support translucent terrain, block entities, entities, particles, weather, the hand, or GUI rendering in DX12. Unsupported layers and all GUI/HUD content remain vanilla OpenGL; the HUD is rendered after the DX12 overlay.
- The terrain path currently uses the block atlas and packed vanilla vertex colors but does not refresh animated atlas frames, reproduce all vanilla fog/lighting/render states, or guarantee parity with other mods that add custom section layers.
- The WGL interop path is a correctness prototype, not an optimized renderer. It uses D3D11On12 because WGL interop2 has no direct D3D12 resource import, and it serializes access with CPU fence waits.
- DX12 is Windows x86-64 only. Missing `WGL_NV_DX_interop2` or native initialization errors are logged and leave vanilla rendering active.
- No Iris integration or compatibility is provided.
