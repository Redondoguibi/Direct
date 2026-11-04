# Direct dependent-mod developer guide

Direct provides the D3D12 backend and a versioned Java extension API. A shader mod should own its pack format, UI, file watching, validation, and HLSL assets; it registers compiled shader source with Direct at runtime rather than bundling shaders into Direct.

## Dependency and API version

For a Direct-only extension, compile against its JAR as `compileOnly` and declare a required NeoForge dependency. An independent manager such as Raydiance must instead keep Direct optional and isolate API discovery so it can run without Direct. Use the API version, not the Direct mod version, for compatibility checks:

```java
DirectRenderService service = DirectRenderService.getInstance();
String apiVersion = service.apiVersion(); // 1.3.0
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

Uniform values are packed float scalars representing up to 15 `float4` vectors and are zero-filled when fewer than 60 floats are returned. The frame constant `DirectFrameData` is `(elapsedSeconds, width, height, frameIndex)`. The shader entry point must be `main` and return `float4` with `SV_Target0`. HLSL compiles on the native D3D12 device; shader compilation errors are logged and reject the new configuration while retaining the previous working chain. A rejected configuration is not compiled again until the registrations change. API 1.1 adds `service.passFailure(id)` to query the asynchronous rejection message (empty when no failure is recorded); re-register a corrected pass to retry. Device/resource failures still fall back to vanilla.

Pass ids must match `[a-z0-9_.-]{1,96}` and be unique. At most eight passes may be registered. A repeated id replaces the previous registration. Pass order is ascending integer order, with lexicographic id as the tie-breaker. Call `unregisterPass(id)` when the extension unloads or disables a pass. Updates are thread-safe and native pipelines are rebuilt on the render thread before the next dispatched frame.

## State and frame contract

1. Direct clears the terrain color target (`R8G8B8A8_UNORM`) and depth target (`D32_FLOAT`, clear depth 1.0), then draws opaque and cutout terrain. Opaque/cutout work is submitted in that order. The depth attachment is preserved through extension passes and interop export.
2. Each extension pass reads the previous pass's color output at `t0` with the clamp/linear `DirectSampler`. It writes a separate full-screen color render target. Blend and depth testing are disabled for extension passes; a pass must write every pixel. API 1.2 binds the original terrain depth as Texture2D<float> at t1 for every pass; test depthInput capability and use Load for exact texels.
3. Pass outputs are transitioned from render-target to pixel-shader-resource state before the next pass reads them. Direct transitions the final output to copy-source, copies it into the terrain color target, and restores the interop target state before handing it to D3D11On12/WGL. Extensions must not issue barriers or mutate target state.
4. Color and depth are copied through 11On12 to WGL textures. Replacement mode composes before vanilla solid/cutout layers and cancels those layers only after valid current-frame composition. Overlay debug mode composes after base layers and deliberately duplicates terrain rendering. The composed depth occludes entities and later translucent/weather layers correctly. The existing OpenGL swap chain presents the frame.
5. Three slots protect upload/command resources until their fences complete. Current-frame composition waits for that frame after validating camera/visible meshes; invalid frames are discarded before waiting and their unused GL-consumption value is signaled. The same D3D12 queue orders terrain, extension passes and the 11On12 copy without an extra pre-copy CPU wait. Shared GL timeline semaphores are used only after a successful import; otherwise a real GLsync protects subsequent reuse. Native translucent rendering imports the current GL scene after entities and completes that capture before DX12 reads it. It has a separate bounded index preflight and timing counters. It does not use the already-signaled opaque consumption fence to represent newer scene writes. Texture handles expire at frame end.
6. On resize, device removal, or interop failure, Direct tears down native state and continues through vanilla rendering. Transient initialization/render failures are retried after a short cooldown; missing WGL interop remains disabled until the config is toggled or the client restarts. A pass must not cache frame handles or assume consecutive frame ids imply resource identity.

`DirectFrameRenderEvent` is a read-only notification before/after the pass chain. `DirectFrameContext` exposes dimensions, time, render distance, pass id/index, and opaque color/depth target descriptions only. Texture handles never contain a D3D12 or OpenGL pointer.

## Capability checks

`DirectRenderCapabilities` reports the detected adapter label and whether the active device is hardware-backed, native terrain/pass support, bindless descriptor tier, pipeline-library availability, GPU semaphore synchronization, WGL interop, and depth sampling. `renderPasses` is always `false`: Direct binds render targets explicitly because a D3D12 render pass cannot span the separate opaque and cutout command lists. Optional features must be guarded by these values. The native renderer still requires Windows x86-64 and `WGL_NV_DX_interop2`; without it, Direct does not try an alternate import path.

## Integration status

Direct 1.4.0 exposes API 1.3.0. See [native contract](native-shader-contract.md) for geometry request/release, owner arbitration, feature names, V2 materials, transparent ordering, resource costs and the tested direct-import limitation. The older integration report is historical.
