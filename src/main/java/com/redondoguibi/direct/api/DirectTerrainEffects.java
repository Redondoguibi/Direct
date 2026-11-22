package com.redondoguibi.direct.api;

/**
 * Bounded geometry effects. Direct owns resources and execution; zero skips each effect.
 * @param shadows projected sun-shadow intensity, 0..1
 * @param water surface-wave/refraction intensity, 0..1
 * @param reflections screen-space water reflection intensity, 0..1
 * @param wind foliage/plant motion intensity, 0..1
 * @param quality 0 performance, 1 balanced, 2 quality
 */
public record DirectTerrainEffects(float shadows, float water, float reflections, float wind, int quality) {
    /** No additional geometry effects or effect targets. */
    public static final DirectTerrainEffects NONE = new DirectTerrainEffects(0, 0, 0, 0, 0);
    /** Validates finite strengths and the supported quality range. */
    public DirectTerrainEffects {
        for (float value : new float[]{shadows, water, reflections, wind}) {
            if (!Float.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException("Effect strength must be 0..1");
            }
        }
        if (quality < 0 || quality > 2) throw new IllegalArgumentException("Quality must be 0..2");
    }
}
