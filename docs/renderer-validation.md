# Experimental terrain replacement validation

Version: 1.3.0-experimental. Java and the native DLL must be distributed together. The public extension API is unchanged.

## What is implemented

Current-frame DX12 color and R32_FLOAT depth are imported into OpenGL before the solid layer. Complete compatible visible terrain can bypass all three vanilla base-layer draw calls, including the former depth-only draws. Missing meshes, a changed visible selection during compilation or a renderer failure preserve vanilla. NeoForge render-stage notifications remain dispatched with the relevant render state.

Terrain retains packed vanilla light coordinates and vertex colors, samples the vanilla lightmap, applies vanilla fog and alpha cutoffs, and uses tested front-face winding. Visibility comes from vanilla's occlusion selection. Animated atlas changes use rectangle readback, background BC7 encoding and incremental uploads. The initial atlas upload/reload remains synchronous; ordinary animation compression does not run on the render thread.

## Automated checks

Run on Windows with JDK 21, CMake, Visual Studio C++ and an OpenGL/D3D12 graphics driver:

```powershell
.\gradlew.bat build test javadoc graphicsTest nativeSmokeTest
```

The graphics tests create hidden windows. They verify actual compositor scale/orientation and actual D3D12 → D3D11On12 → WGL transfer, including current-frame depth in the GL framebuffer, occlusion of later GL geometry, empty sky, cutout holes, lightmap updates, fog, compressed/uncompressed sprite patches, front/back face winding, GPU timestamp queries and resize/empty terrain. The WARP smoke test separately exercises native D3D12 setup. These checks do not establish full in-game visual parity or an FPS improvement.

## In-game validation and remaining limits

Minecraft was launched with an isolated world copy and the initial mixins loaded. Window control was stopped with Escape. Later changes have automated integration coverage, but the complete in-game entities/water/weather/reload visual checklist has not been completed.

At the user's request, FPS recording and the automated benchmark runner were removed. No before/after FPS figures are supplied and no measured improvement over vanilla is claimed. The release therefore remains experimental. Optional `profileRenderer` diagnostics are off by default and report renderer CPU/GPU costs, atlas traffic and actual native/vanilla draw counts without collecting FPS or frame-time samples.

For functional validation, enable `enableDx12Preview=true` and `replaceVanillaTerrain=true`. A pre-existing config with `replaceVanillaTerrain=false` intentionally keeps the diagnostic overlay and its duplicate terrain draws. Check movement, entities behind blocks, block entities, leaves, water, fog, changing light, resize and resource reload at unchanged graphics settings. Missing/incompatible meshes or synchronization failures must retain vanilla before cancellation.
