package com.redondoguibi.direct;

import org.joml.Matrix4f;

/** The view that produced an asynchronous terrain image, copied before the next frame mutates it. */
final class TerrainFrameView {
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private double x;
    private double y;
    private double z;
    private boolean captured;

    void set(Matrix4f view, Matrix4f projection, double x, double y, double z) {
        this.view.set(view);
        this.projection.set(projection);
        this.x = x;
        this.y = y;
        this.z = z;
        captured = true;
    }

    boolean matches(Matrix4f view, Matrix4f projection, double x, double y, double z) {
        // Comparing only camera position misses rotation, view bobbing and FOV changes.
        // Without depth reprojection, a different view must fall back to vanilla.
        return captured && this.x == x && this.y == y && this.z == z
                && this.view.equals(view) && this.projection.equals(projection);
    }
}
