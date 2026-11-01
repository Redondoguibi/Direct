package com.redondoguibi.direct.api;

/**
 * Immutable metadata for one render frame and one extension pass.
 *
 * <p>Texture handles are frame-scoped descriptions only. Direct owns the resources and their
 * transitions; extensions must not cache handles or assume an underlying graphics API object.</p>
 */
public final class DirectFrameContext {
    private final long frameIndex;
    private final int width;
    private final int height;
    private final float elapsedSeconds;
    private final float renderDistance;
    private final String passId;
    private final int passIndex;
    private final DirectTextureHandle inputColor;
    private final DirectTextureHandle outputColor;
    private final DirectTextureHandle depthTarget;

    DirectFrameContext(long frameIndex, int width, int height, float elapsedSeconds, float renderDistance,
            String passId, int passIndex, DirectTextureHandle inputColor, DirectTextureHandle outputColor,
            DirectTextureHandle depthTarget) {
        this.frameIndex = frameIndex;
        this.width = width;
        this.height = height;
        this.elapsedSeconds = elapsedSeconds;
        this.renderDistance = renderDistance;
        this.passId = passId;
        this.passIndex = passIndex;
        this.inputColor = inputColor;
        this.outputColor = outputColor;
        this.depthTarget = depthTarget;
    }

    /**
     * Returns the monotonically increasing frame index.
     *
     * @return frame index
     */
    public long frameIndex() {
        return frameIndex;
    }

    /**
     * Returns the output width in pixels.
     *
     * @return width in pixels
     */
    public int width() {
        return width;
    }

    /**
     * Returns the output height in pixels.
     *
     * @return height in pixels
     */
    public int height() {
        return height;
    }

    /**
     * Returns elapsed renderer time in seconds.
     *
     * @return elapsed seconds
     */
    public float elapsedSeconds() {
        return elapsedSeconds;
    }

    /**
     * Returns the active Minecraft render distance in blocks.
     *
     * @return render distance in blocks
     */
    public float renderDistance() {
        return renderDistance;
    }

    /**
     * Returns the id of the pass currently being evaluated.
     *
     * @return pass id, or an empty string for frame-wide events
     */
    public String passId() {
        return passId;
    }

    /**
     * Returns the zero-based index of the pass currently being evaluated.
     *
     * @return pass index, or {@code -1} for frame-wide events
     */
    public int passIndex() {
        return passIndex;
    }

    /**
     * Returns the previous pass color input. This handle is valid only for this frame.
     *
     * @return opaque input texture description
     */
    public DirectTextureHandle inputColor() {
        return inputColor;
    }

    /**
     * Returns the color output target assigned to this pass. This handle is valid only for this frame.
     *
     * @return opaque output texture description
     */
    public DirectTextureHandle outputColor() {
        return outputColor;
    }

    /**
     * Returns the opaque depth attachment used by the terrain pass.
     *
     * <p>Depth sampling is not currently supported; check
     * {@link DirectRenderService#capabilities()} before planning a pass that needs it.</p>
     *
     * @return opaque depth attachment description
     */
    public DirectTextureHandle depthTarget() {
        return depthTarget;
    }
}
