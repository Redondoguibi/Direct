package com.redondoguibi.direct.backend;

import java.nio.ByteBuffer;

/** JNI surface of src/main/cpp/dx12_backend.cpp. Render thread only; every call may throw IllegalStateException. */
final class Dx12BackendNative {
    private Dx12BackendNative() {}

    /** @param debug enable the D3D12 debug layer; validation messages are appended to pipeline errors */
    static native void create(boolean debug, boolean profileUploads);
    static native void destroy();
    /** Executes everything recorded so far and waits for the GPU. */
    static native void flush();
    static native long draws();
    /** GPU milliseconds of the backend's own command lists completed since the last call. */
    static native double takeGpuMs();
    /** GPU ms since the last call by operation: composite, export, import, present, copy, clear, upload. */
    static native void takeCategoryMs(double[] out);
    /** With the debug layer: validation messages collected at fence waits since the last call ("" otherwise). */
    static native String messages();

    static native void textureImage(int id, int level, int glInternalFormat, int width, int height);
    static native void textureSubImage(int id, int level, int x, int y, int width, int height, int glFormat, long pixels,
            int rowLength, int skipPixels, int skipRows, int alignment);
    static native void textureParams(int id, int minFilter, int magFilter, int wrapS, int wrapT, int maxLevel,
            float lodBias, float minLod, float maxLod);
    static native void textureDelete(int id);

    /** slot 0 = color attachment 0, slot 1 = depth (or depth-stencil) attachment. */
    static native void attach(int framebuffer, int slot, int texture);
    static native void deleteFramebuffer(int framebuffer);
    /** scissor = {enabled, x, y, width, height}, OpenGL bottom-up rows. */
    static native void clear(int framebuffer, int mask, float r, float g, float b, float a, float depth, int[] scissor,
            int colorWrite);

    /** Static mesh storage (VertexBuffer.Usage.STATIC). @param id 0 for a new mesh @return the mesh id */
    static native int meshUpload(int id, long vertices, int vertexBytes, long indices, int indexBytes);
    static native void meshIndices(int id, long indices, int indexBytes);
    static native void meshDelete(int id);
    static native long meshBytes();
    /** Debug: a static mesh's vertices (which 0) or indices (1), copied out by the GPU (runs the recorded commands first). */
    static native int meshGpuRead(int id, int which, ByteBuffer out);

    /** glBlitFramebuffer of whole, equally sized attachments. rect = src x0,y0,x1,y1, dst x0,y0,x1,y1. @return false if unsupported */
    static native boolean blit(int readFramebuffer, int drawFramebuffer, int[] rect, int mask);

    /**
     * Records the Direct terrain composite (as Dx12GlInterop draws it in OpenGL) into a framebuffer; the next flush
     * waits on the GPU for the terrain frame's fence to reach readyValue. copy: read copies made on the terrain queue
     * (needed while OpenGL also composites the shared image). @return false without a terrain image
     */
    static native boolean compositeTerrain(int framebuffer, long readyValue, boolean writeDepth, boolean copy);
    static native long terrainComposites();

    /**
     * The scene of a framebuffer becomes the shader pack's input (as InteropSceneCapture.capture writes it): the
     * recorded work is submitted and the terrain queue waits for it. handOff: D3D11On12 releases the shared images first.
     * @return false when the terrain renderer has no depth bridge or the sizes differ
     */
    static native boolean exportScene(int framebuffer, boolean handOff);
    /** After pack frame readyValue: its output replaces the framebuffer's color (and depth). */
    static native boolean importScene(int framebuffer, long readyValue, boolean withDepth);

    static native int createProgram(String name, byte[] vertexHlsl, byte[] fragmentHlsl, int cbufferBytes, int textures);

    /**
     * @param mesh   a static mesh id, or 0 to copy {@code vertices}/{@code indices} for this draw
     * @param layout {location, dxgiFormat, byteOffset} per vertex element the program reads
     * @param inputs every vertex input location of the program
     * @param state  blend[7], depth[3], cull, colorMask, scissor[5], viewport[4], polygonOffset, factor bits, units bits
     */
    static native void draw(int program, int framebuffer, int mesh, long vertices, int vertexBytes, int stride, int[] layout,
            int[] inputs, long indices, int indexBytes, boolean wideIndices, int indexCount, int glMode, int[] state,
            ByteBuffer cbuffer, int cbufferBytes, int[] textures);

    /** Runs the recorded frame and shows a texture on the window through a DXGI swap chain (V flipped). */
    /** @return with verify, how many of 64 desktop pixels in the window match the texture afterwards; else -1 */
    static native int present(long hwnd, int texture, int width, int height, boolean vsync, boolean verify, boolean pipelined);

    /** Level 0 of a texture, tightly packed, OpenGL row order (row 0 is the bottom row). */
    static native void readback(int texture, ByteBuffer out);
}
