# Direct

Direct is a Windows-only native Direct3D 12 rendering prototype for Minecraft Java 1.21.1 / NeoForge 21.1. It does not depend on Iris and does not provide Iris compatibility.

> **Current scope:** The opt-in renderer captures compiled Minecraft chunk meshes for the solid and cutout block layers, uploads their positions, colors, UVs and indices to native buffers, and draws them with a Direct3D pipeline over the DX12 offscreen texture. The block atlas is copied from OpenGL when it is loaded or reloaded. The result is imported into OpenGL with `WGL_NV_DX_interop2` and drawn before vanilla GUI rendering, so Minecraft continues to present through its existing OpenGL swap chain and the HUD remains above the overlay. It remains disabled by default.

## Build

Use JDK 21, CMake 3.24+, and the Visual Studio 2022 C++ build tools with the Windows SDK. On Windows, `gradlew.bat build` configures and builds `direct_renderer.dll`, then packages it in the mod JAR. `compileJava` can be used without the native toolchain. Other operating systems can build Java sources, but cannot package or run the DX12 backend.

The native code links only Windows SDK libraries (`d3d11`, `d3d12`, `dxgi`, and `d3dcompiler`). HLSL is compiled at runtime with `D3DCompile`; no additional runtime library is bundled.

## DX12/OpenGL interop

The renderer runs synchronously on Minecraft's render thread, from the head of `Gui.render`. DX12 creates and clears an offscreen texture; `D3D11On12` exposes it to D3D11 because `WGL_NV_DX_interop2` imports D3D10/11 resources rather than D3D12 resources directly. The driver did not expose the wrapped resource's contents to GL sampling, so the bridge copies it into a native D3D11 texture before registration. This adds a GPU copy but no CPU pixel transfer for the rendered frame. The block atlas is a separate one-time CPU readback on resource load/reload, so animated atlas frames are not refreshed yet. The active OpenGL driver must advertise `WGL_NV_DX_interop2`; otherwise Direct logs the missing extension and leaves vanilla rendering untouched, without trying another path.

The D3D12 queue fence is awaited before OpenGL locks the D3D11 import texture. After the fullscreen draw, OpenGL inserts a `GLsync`; the next frame queues `glWaitSync` and waits for completion before DX12 clears the source texture again. `glWaitSync` cannot directly wait on a D3D12 fence, so this bridge uses the DX12 fence for DX12-to-GL ordering and GL syncs for GL-to-DX12 ordering. This deliberately conservative test serializes both APIs rather than introducing a second render thread and separate presentation path.

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
