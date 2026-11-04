package com.redondoguibi.direct.api;

/**
 * Describes the active backend and the features that have been verified on the current device.
 *
 * @param backendName backend label
 * @param adapterName detected adapter label, or {@code unknown}
 * @param hardwareAdapter whether the selected adapter is not software-rendered
 * @param nativeTerrain whether the terrain path uses native D3D12
 * @param shaderPasses whether extension HLSL passes can be dispatched
 * @param bindlessDescriptors whether the selected adapter supports the required descriptor tier
 * @param renderPasses whether native D3D12 render-pass commands are used; always {@code false} because
 *        Direct binds targets explicitly (a render pass cannot span its separate command lists)
 * @param pipelineLibrary whether the D3D12 pipeline library is available
 * @param gpuInteropSemaphores whether GPU-side OpenGL/D3D12 semaphores are active
 * @param wglInterop whether {@code WGL_NV_DX_interop2} is available
 * @param depthInput whether passes can sample the depth target
 */
public record DirectRenderCapabilities(
        String backendName,
        String adapterName,
        boolean hardwareAdapter,
        boolean nativeTerrain,
        boolean shaderPasses,
        boolean bindlessDescriptors,
        boolean renderPasses,
        boolean pipelineLibrary,
        boolean gpuInteropSemaphores,
        boolean wglInterop,
        boolean depthInput) {
    /**
     * Returns an unavailable capabilities value before the renderer is initialized.
     *
     * @return unavailable capabilities
     */
    public static DirectRenderCapabilities unavailable() {
        return new DirectRenderCapabilities("Unavailable", "unknown", false, false, false,
                false, false, false, false, false, false);
    }
}
