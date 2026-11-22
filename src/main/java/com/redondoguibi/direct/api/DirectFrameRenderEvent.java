package com.redondoguibi.direct.api;

import net.neoforged.bus.api.Event;

/** Read-only notification before or after a frame's registered extension passes execute. */
public final class DirectFrameRenderEvent extends Event {
    /** The notification point relative to the registered pass chain. */
    public enum Stage {
        /** Terrain is ready and extension passes are about to execute. */
        BEFORE_PASSES,
        /** The extension pass chain completed and the color target is ready for OpenGL. */
        AFTER_PASSES
    }

    private final Stage stage;
    private final DirectFrameContext context;

    /**
     * Creates a frame notification.
     *
     * @param stage notification stage
     * @param context frame metadata
     */
    public DirectFrameRenderEvent(Stage stage, DirectFrameContext context) {
        this.stage = stage;
        this.context = context;
    }

    /**
     * Returns the notification stage.
     *
     * @return notification stage
     */
    public Stage stage() {
        return stage;
    }

    /**
     * Returns frame metadata. The context's pass id is empty for frame-wide notifications.
     *
     * @return frame context
     */
    public DirectFrameContext context() {
        return context;
    }
}
