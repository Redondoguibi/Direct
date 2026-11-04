package com.redondoguibi.direct;

import java.nio.*;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Compares the explicit CPU wait with the WGL ownership barrier; never launches Minecraft. */
public final class InteropWaitBenchmark {
    public static void main(String[] args){
        System.load(args[0]);if(!GLFW.glfwInit())throw new AssertionError("GLFW");
        GLFW.glfwDefaultWindowHints();GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);long window=GLFW.glfwCreateWindow(32,32,"Interop timing",0,0);
        if(window==0)throw new AssertionError("GL window");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        long[] handles=NativeDx12.initializeInterop(1920,1080,false,16,8,64);long device=WGLNVDXInterop.wglDXOpenDeviceNV(handles[0]);
        int color=GL11.glGenTextures(),depth=GL11.glGenTextures(),fbo=GL30.glGenFramebuffers();long c=0,d=0;
        try(MemoryStack stack=MemoryStack.stackPush()){
            c=WGLNVDXInterop.wglDXRegisterObjectNV(device,handles[1],color,GL11.GL_TEXTURE_2D,WGLNVDXInterop.WGL_ACCESS_READ_ONLY_NV);
            d=WGLNVDXInterop.wglDXRegisterObjectNV(device,handles[4],depth,GL11.GL_TEXTURE_2D,WGLNVDXInterop.WGL_ACCESS_READ_ONLY_NV);if(c==0||d==0)throw new AssertionError("WGL registration");
            var objects=stack.pointers(c,d);var pixel=stack.malloc(4);
            var atlas=ByteBuffer.allocateDirect(64);for(int i=0;i<16;i++)atlas.putInt(-1);atlas.flip();NativeDx12.updateTerrainAtlas(new ByteBuffer[]{atlas},4,4,false,1);
            var light=ByteBuffer.allocateDirect(1024);for(int i=0;i<256;i++)light.putInt(-1);light.flip();NativeDx12.updateLightmap(light);
            var vertices=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
            for(float[] p:new float[][]{{-.9f,-.9f},{.9f,-.9f},{.9f,.9f},{-.9f,.9f}})vertices.putShort((short)Math.round((p[0]+16)*1024)).putShort((short)Math.round((p[1]+16)*1024)).putShort((short)Math.round(16.25*1024)).putShort((short)0xf0f0).putInt(-1).putShort((short)32768).putShort((short)32768);
            vertices.flip();var indices=ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder());for(int i:new int[]{0,1,2,2,3,0})indices.putInt(i);indices.flip();
            long[] keys=new long[16];for(int i=0;i<16;i++){keys[i]=i+1;NativeDx12.updateTerrainSection(i+1,0,0,0,vertices,4,indices,new int[]{6,0,0});}NativeDx12.setVisibleSections(keys,16,true);
            float[] matrix=new float[28];new Matrix4f().get(matrix);matrix[20]=100;matrix[21]=200;for(int i=24;i<28;i++)matrix[i]=1;
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);
            int frame=0;NativeDx12.setProfilingEnabled(true);
            for(boolean explicit:new boolean[]{true,false,false,true}){
                double[] total=new double[90],sync=new double[90];int pendingAfterLock=0;
                for(int i=0;i<110;i++,frame++){
                    matrix[24]=frame%2==0?1:0;matrix[25]=frame%2==0?0:1;
                    long start=System.nanoTime();if(NativeDx12.renderInterop(matrix,0,0,0,256,frame,0,new float[0],false,true)!=0)throw new AssertionError("Submit");
                    long before=System.nanoTime();if(explicit&&!NativeDx12.waitInteropFrame(frame+1))throw new AssertionError("Fence");
                    if(!WGLNVDXInterop.wglDXLockObjectsNV(device,objects))throw new AssertionError("Lock");
                    long locked=System.nanoTime();if(NativeDx12.pollInteropFence(frame+1)==0)pendingAfterLock++;
                    try{GL11.glReadPixels(960,540,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixel);
                        if(Byte.toUnsignedInt(pixel.get(0))!=(frame%2==0?255:0)||Byte.toUnsignedInt(pixel.get(1))!=(frame%2==0?0:255))throw new AssertionError("Stale color at "+frame);
                    }finally{if(!WGLNVDXInterop.wglDXUnlockObjectsNV(device,objects))throw new AssertionError("Unlock");}
                    GL11.glFinish();if(!NativeDx12.waitInteropFrame(frame+1))throw new AssertionError("End fence");NativeDx12.signalInteropFence(frame+1);
                    if(i>=20){total[i-20]=(System.nanoTime()-start)/1e6;sync[i-20]=(locked-before)/1e6;}
                }
                java.util.Arrays.sort(total);java.util.Arrays.sort(sync);System.out.printf(java.util.Locale.ROOT,"explicitWait=%s frameP50=%.4f frameP95=%.4f syncP50=%.4f pendingAfterWglLock=%d%n",explicit,total[45],total[85],sync[45],pendingAfterLock);
            }
        }finally{
            GL11.glFinish();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL30.glDeleteFramebuffers(fbo);if(c!=0)WGLNVDXInterop.wglDXUnregisterObjectNV(device,c);if(d!=0)WGLNVDXInterop.wglDXUnregisterObjectNV(device,d);WGLNVDXInterop.wglDXCloseDeviceNV(device);NativeDx12.shutdownInterop();GL11.glDeleteTextures(color);GL11.glDeleteTextures(depth);GL.setCapabilities(null);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();
        }
    }
}
