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
    UINT width, height;
};
bool directTerrainOutput(DirectTerrainOutput* out);

