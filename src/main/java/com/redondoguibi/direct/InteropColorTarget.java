package com.redondoguibi.direct;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.WGLNVDXInterop;
import org.lwjgl.system.MemoryStack;

/** WGL registration owns the GL name, while Direct retains the native resource. */
final class InteropColorTarget implements AutoCloseable {
    final long device;
    final long object;
    final int texture;
    final boolean direct;
    final String fallbackReason;

    private InteropColorTarget(long device, long object, int texture, boolean direct, String fallbackReason) {
        this.device = device;
        this.object = object;
        this.texture = texture;
        this.direct = direct;
        this.fallbackReason = fallbackReason;
    }

    static InteropColorTarget create(long device, long copiedColor, int width, int height, boolean preferDirect) {
        String rejection = null;
        if (preferDirect) {
            int name = GL11.glGenTextures();
            long object = 0;
            try {
                // Acquire before registration: some 11On12 drivers expose no texture storage otherwise.
                object = register(device, NativeDx12.acquireColorInteropTarget(), name);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    var objects = stack.pointers(object);
                    if (!WGLNVDXInterop.wglDXLockObjectsNV(device, objects)) {
                        throw new IllegalStateException("Cannot lock native color for validation");
                    }
                    try {
                        GL11.glBindTexture(GL11.GL_TEXTURE_2D, name);
                        int actualWidth = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                        int actualHeight = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                        if (actualWidth != width || actualHeight != height) {
                            throw new IllegalStateException("Native color storage is " + actualWidth + "x" + actualHeight
                                    + "; expected " + width + "x" + height);
                        }
                    } finally {
                        if (!WGLNVDXInterop.wglDXUnlockObjectsNV(device, objects)) {
                            throw new IllegalStateException("Cannot unlock native color after validation");
                        }
                    }
                }
            } catch (RuntimeException exception) {
                // Do not reuse a resource if the driver cannot relinquish its registration.
                if (object != 0 && !WGLNVDXInterop.wglDXUnregisterObjectNV(device, object)) {
                    throw new IllegalStateException("Cannot unregister rejected native color", exception);
                }
                GL11.glDeleteTextures(name);
                rejection = exception.toString();
                name = 0;
            }
            if (name != 0) {
                // Only now may native code free the unused copied target.
                NativeDx12.configureColorAlias(true);
                return new InteropColorTarget(device, object, name, true, null);
            }
        }
        NativeDx12.configureColorAlias(false);
        int name = GL11.glGenTextures();
        try {
            return new InteropColorTarget(device, register(device, copiedColor, name), name, false, rejection);
        } catch (RuntimeException exception) {
            GL11.glDeleteTextures(name);
            throw exception;
        }
    }

    private static long register(long device, long resource, int name) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, name);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        long object = WGLNVDXInterop.wglDXRegisterObjectNV(device, resource, name,
                GL11.GL_TEXTURE_2D, WGLNVDXInterop.WGL_ACCESS_READ_WRITE_NV);
        if (object == 0) throw new IllegalStateException("Could not register terrain color for OpenGL");
        return object;
    }

    @Override
    public void close() {
        if (!WGLNVDXInterop.wglDXUnregisterObjectNV(device, object)) {
            throw new IllegalStateException("Could not unregister terrain color");
        }
        GL11.glDeleteTextures(texture);
    }
}
