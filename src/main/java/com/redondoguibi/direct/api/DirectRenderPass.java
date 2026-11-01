package com.redondoguibi.direct.api;

/**
 * An extension-owned full-screen HLSL pass executed after Direct's terrain pass.
 *
 * <p>The pixel shader source must declare {@code DirectInput} at {@code t0}, a
 * {@code DirectSampler} at {@code s0}, and {@code DirectFrameData} plus
 * {@code DirectUniforms[15]} in a {@code b0} constant buffer. The entry point is
 * {@code float4 main(float4 position : SV_Position) : SV_Target0}. Passes sample the previous
 * pass output and write a distinct color target.</p>
 */
public interface DirectRenderPass {
    /**
     * Returns a stable, case-sensitive identifier unique across all registered extensions.
     *
     * @return pass id
     */
    String id();

    /**
     * Returns HLSL pixel shader source compiled by Direct for the current D3D12 device.
     *
     * @return HLSL source
     */
    String pixelShaderSource();

    /**
     * Returns the sort order; lower values run first, then ids are sorted lexicographically.
     *
     * @return pass order
     */
    default int order() {
        return 0;
    }

    /**
     * Returns packed float4 uniform values, up to 15 vectors (60 floats).
     * Unspecified values are zero-filled for each frame.
     *
     * @param frame frame- and pass-specific metadata; valid only during this callback
     * @return packed float values
     */
    default float[] uniforms(DirectFrameContext frame) {
        return new float[0];
    }
}
