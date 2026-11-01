# Direct dependent-mod developer guide

Direct provides the D3D12 backend and a versioned Java extension API. A shader mod should own its pack format, UI, file watching, validation, and HLSL assets; it registers compiled shader source with Direct at runtime rather than bundling shaders into Direct.

## Dependency and API version

Compile against the Direct mod JAR as a `compileOnly` dependency and declare Direct as a required NeoForge dependency in the shader mod's `neoforge.mods.toml`. Use the API version, not the Direct mod version, for compatibility checks:

```java
DirectRenderService service = DirectRenderService.getInstance();
String apiVersion = service.apiVersion(); // 1.0.0
DirectRenderCapabilities caps = service.capabilities();
```

`DirectRenderService.API_VERSION` follows SemVer independently of Direct's release version. A consumer requiring API 1.x should reject an unknown major version and should check `capabilities()` before using optional facilities. The service and registration event are client-only.

## Registering a pass

Subscribe to the NeoForge game event bus during your mod's constructor. Direct posts the registration event once during client setup, after mod constructors have run:

```java
public final class ExampleShaderMod {
    public ExampleShaderMod() {
        NeoForge.EVENT_BUS.addListener(this::registerPasses);
    }

    private void registerPasses(DirectRenderPassRegistrationEvent event) {
        event.service().registerPass(new TintPass());
    }
}
```

Example pass:

```java
final class TintPass implements DirectRenderPass {
    private static final String HLSL = """
            Texture2D<float4> DirectInput : register(t0);
            SamplerState DirectSampler : register(s0);
            cbuffer DirectFrame : register(b0) {
                float4 DirectFrameData;
                float4 DirectUniforms[15];
            };
            float4 main(float4 position : SV_Position) : SV_Target0 {
                float2 uv = position.xy / DirectFrameData.yz;
                return DirectInput.Sample(DirectSampler, uv) * DirectUniforms[0];
            }
            """;

    @Override
    public String id() {
        return "example.tint";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String pixelShaderSource() {
        return HLSL;
    }

    @Override
    public float[] uniforms(DirectFrameContext frame) {
        return new float[] {1.0f, 0.8f, 0.8f, 1.0f};
    }
}
```

Uniform values are packed float scalars representing up to 15 `float4` vectors and are zero-filled when fewer than 60 floats are returned. The frame constant `DirectFrameData` is `(elapsedSeconds, width, height, frameIndex)`. The shader entry point must be `main` and return `float4` with `SV_Target0`. HLSL compiles on the native D3D12 device; compilation errors are logged and cause a clean fallback to vanilla rather than exposing the device to the extension.

Pass ids must match `[a-z0-9_.-]{1,96}` and be unique. At most eight passes may be registered. A repeated id replaces the previous registration. Pass order is ascending integer order, with lexicographic id as the tie-breaker. Call `unregisterPass(id)` when the extension unloads or disables a pass. Updates are thread-safe and native pipelines are rebuilt on the render thread before the next dispatched frame.

## State and frame contract

1. Direct clears the terrain color target (`R8G8B8A8_UNORM`) and depth target (`D32_FLOAT`, clear depth 1.0), then draws opaque and cutout terrain. Opaque/cutout work is submitted in that order. The depth attachment is preserved between these terrain operations and discarded at the end of terrain rendering.
2. Each extension pass reads the previous pass's color output at `t0` with the clamp/linear `DirectSampler`. It writes a separate full-screen color render target. Blend and depth testing are disabled for extension passes; a pass must write every pixel. Depth is not sampleable in API 1.0.
3. Pass outputs are transitioned from render-target to pixel-shader-resource state before the next pass reads them. Direct transitions the final output to copy-source, copies it into the terrain color target, and restores the interop target state before handing it to D3D11On12/WGL. Extensions must not issue barriers or mutate target state.
4. The final D3D12 color image is copied to the WGL-importable D3D11 texture and composited after vanilla solid/cutout layers, before entity rendering. Vanilla depth remains intact, so entities and later translucent/weather/GUI layers render above the Direct terrain. The existing OpenGL swap chain presents the frame.
5. Three frame slots protect upload and terrain command allocators/resources until their D3D12 fences complete. When shared OpenGL/D3D12 timeline semaphores work, the D3D12 queue waits for GL's prior-frame signal before reusing the shared output. CPU fallback uses bounded waits. A frame's texture handles expire at the end of that frame.
6. On resize, device removal, invalid HLSL, or interop failure, Direct tears down native state and continues through vanilla rendering. A pass must not cache frame handles or assume consecutive frame ids imply resource identity.

`DirectFrameRenderEvent` is a read-only notification before/after the pass chain. `DirectFrameContext` exposes dimensions, time, render distance, pass id/index, and opaque color/depth target descriptions only. Texture handles never contain a D3D12 or OpenGL pointer.

## Capability checks

`DirectRenderCapabilities` reports the detected adapter label and whether the active device is hardware-backed, native terrain/pass support, bindless descriptor tier, D3D12 render-pass command support, pipeline-library availability, GPU semaphore synchronization, WGL interop, and depth sampling. Optional features must be guarded by these values. The native renderer still requires Windows x86-64 and `WGL_NV_DX_interop2`; without it, Direct does not try an alternate import path.
