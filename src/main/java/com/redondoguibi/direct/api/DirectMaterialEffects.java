package com.redondoguibi.direct.api;

/**
 * Optional material shading, executed by Direct in existing terrain passes.
 * @param specular sunlight highlights by material, 0..1
 * @param wetness rain-driven surface darkening and gloss, 0..1
 * @param emissive unlit contribution of classified emissive surfaces, 0..1 (no emitted world light)
 * @param waterAbsorption water tint/opacity by scene depth, 0..1
 */
public record DirectMaterialEffects(float specular, float wetness, float emissive, float waterAbsorption) {
    /** No additional material shading. */
    public static final DirectMaterialEffects NONE = new DirectMaterialEffects(0, 0, 0, 0);
    /** Validates bounded finite settings. */
    public DirectMaterialEffects {
        for (float value : new float[]{specular, wetness, emissive, waterAbsorption}) {
            if (!Float.isFinite(value) || value < 0 || value > 1)
                throw new IllegalArgumentException("Material strength must be 0..1");
        }
    }
}
