// Links between the DX12 backend (dx12_backend.cpp) and the hybrid terrain renderer (dx12_gl_interop.cpp), which live
// in the same DLL. When the backend runs (-Ddirect.phase1.shadow), the terrain renderer is created on the backend's
// device, so the backend can composite the terrain image itself instead of going through OpenGL.
#pragma once
#include <d3d12.h>

/** The backend's device with a reference added for the caller, or nullptr when the backend is not running. */
ID3D12Device* directBackendDevice();

/** The terrain renderer's latest output; false when it is not initialized. No references are added. */
struct DirectTerrainOutput {
    ID3D12Resource* color;   // kColorFormat, top-down rows, RENDER_TARGET at rest
    ID3D12Resource* depth;   // R32_TYPELESS (D32_FLOAT), OpenGL window depth, DEPTH_WRITE at rest
    ID3D12Fence* fence;      // reaches the frame's ready value when its terrain is rendered
    ID3D12CommandQueue* queue;
    ID3D12Resource* depthBridge; // R32_FLOAT scene depth read by the pack passes, COMMON at rest; null without the bridge
    UINT width, height;
};
bool directTerrainOutput(DirectTerrainOutput* out);

/** Borrowed final world-pass texture for this exact submission, in PIXEL_SHADER_RESOURCE state. */
ID3D12Resource* directWorldColor(UINT64 readyValue);

/**
 * Before another queue writes the shared images (shadow mode): returns them from D3D11On12, which holds them after an
 * OpenGL lock, and signals fence to value on the terrain queue after that release.
 */
bool directTerrainHandOff(ID3D12Fence* fence, UINT64 value);

/**
 * DX12 alone renders (no OpenGL consumer): the renderer stops exporting its depth into the bridge after each frame and
 * stops handing its images to D3D11On12. The backend reads the renderer's color and depth targets directly.
 */
void directTerrainBackendOnly(bool enabled);

