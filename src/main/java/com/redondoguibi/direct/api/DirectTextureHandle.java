package com.redondoguibi.direct.api;

/** Opaque description of a texture owned by Direct. It never exposes a native resource pointer. */
public final class DirectTextureHandle {
    private final String role;
    private final int width;
    private final int height;
    private final long frameIndex;

    DirectTextureHandle(String role, int width, int height, long frameIndex) {
        this.role = role;
        this.width = width;
        this.height = height;
        this.frameIndex = frameIndex;
    }

    /**
     * Returns the stable semantic role, such as {@code scene-color} or {@code depth}.
     *
     * @return semantic role
     */
    public String role() {
        return role;
    }

    /**
     * Returns the texture width in pixels.
     *
     * @return width in pixels
     */
    public int width() {
        return width;
    }

    /**
     * Returns the texture height in pixels.
     *
     * @return height in pixels
     */
    public int height() {
        return height;
    }

    /**
     * Returns the frame for which this handle is valid.
     *
     * @return frame index
     */
    public long frameIndex() {
        return frameIndex;
    }
}
