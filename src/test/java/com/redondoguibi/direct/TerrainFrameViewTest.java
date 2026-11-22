package com.redondoguibi.direct;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class TerrainFrameViewTest {
    @Test
    void requiresACapturedView() {
        assertFalse(new TerrainFrameView().matches(new Matrix4f(), new Matrix4f(), 0, 0, 0));
    }

    @Test
    void acceptsTheSameViewInDifferentMatrixInstances() {
        TerrainFrameView frame = new TerrainFrameView();
        Matrix4f view = new Matrix4f().rotateY(0.4f);
        Matrix4f projection = new Matrix4f().perspective(1.2f, 1.5f, 0.05f, 1000.0f);
        frame.set(view, projection, 10, 80, -20);
        assertTrue(frame.matches(new Matrix4f(view), new Matrix4f(projection), 10, 80, -20));
    }

    @Test
    void rejectsMovementOnAnyAxisIncludingSmallChangesFarFromOrigin() {
        TerrainFrameView frame = new TerrainFrameView();
        Matrix4f matrix = new Matrix4f();
        frame.set(matrix, matrix, 20_000_000, 80, -20_000_000);
        assertFalse(frame.matches(matrix, matrix, 20_000_000.01, 80, -20_000_000));
        assertFalse(frame.matches(matrix, matrix, 20_000_000, 80.01, -20_000_000));
        assertFalse(frame.matches(matrix, matrix, 20_000_000, 80, -20_000_000.01));
    }

    @Test
    void rejectsRotationWithoutSharingMutableMatrices() {
        TerrainFrameView frame = new TerrainFrameView();
        Matrix4f view = new Matrix4f();
        Matrix4f projection = new Matrix4f();
        frame.set(view, projection, 0, 0, 0);
        view.rotateY(0.1f);
        assertFalse(frame.matches(view, projection, 0, 0, 0));
        assertTrue(frame.matches(new Matrix4f(), projection, 0, 0, 0));
    }

    @Test
    void rejectsFovAndAspectRatioChanges() {
        TerrainFrameView frame = new TerrainFrameView();
        Matrix4f view = new Matrix4f();
        Matrix4f projection = new Matrix4f().perspective(1.2f, 1.5f, 0.05f, 1000.0f);
        frame.set(view, projection, 0, 0, 0);
        projection.perspective(0.9f, 1.5f, 0.05f, 1000.0f);
        assertFalse(frame.matches(view, projection, 0, 0, 0));
        assertFalse(frame.matches(view, new Matrix4f().perspective(1.2f, 2.0f, 0.05f, 1000.0f), 0, 0, 0));
    }

    @Test
    void acceptsANewViewAfterReplacingThePendingImage() {
        TerrainFrameView frame = new TerrainFrameView();
        Matrix4f matrix = new Matrix4f();
        frame.set(matrix, matrix, 0, 0, 0);
        frame.set(matrix, matrix, 1, 2, 3);
        assertTrue(frame.matches(matrix, matrix, 1, 2, 3));
        assertFalse(frame.matches(matrix, matrix, 0, 0, 0));
    }
}
