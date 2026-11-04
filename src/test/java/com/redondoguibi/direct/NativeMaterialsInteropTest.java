package com.redondoguibi.direct;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import org.joml.Matrix4f;
import java.nio.*;
import java.nio.file.Path;

@Tag("graphics")
abstract class NativeMaterialsInteropTestBase {
    boolean aliased(){return false;}
    private void registerTargets(long color,long depth){
        GL11.glDeleteTextures(sharedColor);
        var target=InteropColorTarget.create(device,color,64,64,aliased());
        assertEquals(aliased(),target.direct,target.fallbackReason);
        sharedColor=target.texture;colorObject=target.object;
        depthObject=register(depth,sharedDepth);
    }
    private long window,device,colorObject,depthObject;private int sharedColor,sharedDepth;
    @BeforeEach void open(){
        System.load(Path.of("build/generated/nativeResources/META-INF/native/windows-x86_64/direct_renderer.dll").toAbsolutePath().toString());
        assertTrue(GLFW.glfwInit());GLFW.glfwDefaultWindowHints();GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,3);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,3);
        window=GLFW.glfwCreateWindow(64,64,"Materials GPU test",0,0);assertNotEquals(0,window);GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        long[] handles=NativeDx12.initializeInterop(64,64,true,16,8,64);device=WGLNVDXInterop.wglDXOpenDeviceNV(handles[0]);assertNotEquals(0,device);
        sharedColor=GL11.glGenTextures();sharedDepth=GL11.glGenTextures();registerTargets(handles[1],handles[4]);
        var atlas=ByteBuffer.allocateDirect(64);for(int i=0;i<16;i++)atlas.putInt(-1);atlas.flip();NativeDx12.updateTerrainAtlas(new ByteBuffer[]{atlas},4,4,false,1);
        var light=ByteBuffer.allocateDirect(1024);for(int i=0;i<256;i++)light.putInt(-1);light.flip();NativeDx12.updateLightmap(light);
    }
    private long register(long resource,int texture){GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);long result=WGLNVDXInterop.wglDXRegisterObjectNV(device,resource,texture,GL11.GL_TEXTURE_2D,WGLNVDXInterop.WGL_ACCESS_READ_WRITE_NV);assertNotEquals(0,result);return result;}
    @AfterEach void close(){GL11.glFinish();if(colorObject!=0)WGLNVDXInterop.wglDXUnregisterObjectNV(device,colorObject);if(depthObject!=0)WGLNVDXInterop.wglDXUnregisterObjectNV(device,depthObject);if(device!=0)WGLNVDXInterop.wglDXCloseDeviceNV(device);NativeDx12.shutdownInterop();GL11.glDeleteTextures(sharedColor);GL11.glDeleteTextures(sharedDepth);GL.setCapabilities(null);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    private float[] matrix(Matrix4f projection){float[] result=new float[28];projection.get(result);result[20]=100;result[21]=200;for(int i=24;i<28;i++)result[i]=1;return result;}
    private void vertex(ByteBuffer b,float x,float y,float z,int color,int material){b.putShort((short)Math.round((x+16)*1024)).putShort((short)Math.round((y+16)*1024)).putShort((short)Math.round((z+16)*1024)).putShort((short)0xf0f0).putInt(color).putShort((short)32768).putShort((short)32768).put((byte)0).put((byte)127).put((byte)0).put((byte)material);}
    private ByteBuffer indices(int quads){var b=ByteBuffer.allocateDirect(quads*24).order(ByteOrder.nativeOrder());for(int q=0;q<quads;q++)for(int i:new int[]{0,1,2,2,3,0})b.putInt(i+q*4);return b.flip();}
    private void finish(long frame){assertTrue(NativeDx12.waitInteropFrame(frame+1));NativeDx12.signalInteropFence(frame+1);}
    private ByteBuffer readShared(){try(MemoryStack stack=MemoryStack.stackPush()){var objects=stack.pointers(colorObject,depthObject);assertTrue(WGLNVDXInterop.wglDXLockObjectsNV(device,objects));try{GL11.glBindTexture(GL11.GL_TEXTURE_2D,sharedColor);ByteBuffer image=ByteBuffer.allocateDirect(64*64*4);GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,image);return image;}finally{assertTrue(WGLNVDXInterop.wglDXUnlockObjectsNV(device,objects));GL11.glFinish();}}}
    private FloatBuffer readDepth(){try(MemoryStack stack=MemoryStack.stackPush()){var objects=stack.pointers(colorObject,depthObject);assertTrue(WGLNVDXInterop.wglDXLockObjectsNV(device,objects));try{GL11.glBindTexture(GL11.GL_TEXTURE_2D,sharedDepth);var data=ByteBuffer.allocateDirect(64*64*4).order(ByteOrder.nativeOrder()).asFloatBuffer();GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_RED,GL11.GL_FLOAT,data);return data;}finally{assertTrue(WGLNVDXInterop.wglDXUnlockObjectsNV(device,objects));GL11.glFinish();}}}
    @Test void rejectedNativeStorageRetainsCopiedBridgeAfterResize(){
        float[] frame=matrix(new Matrix4f());
        NativeDx12.configureRenderPasses(new String[]{"float4 main(float4 p:SV_Position):SV_Target0{return float4(1,0,0,1);}"});
        assertEquals(0,NativeDx12.renderInterop(frame,0,0,0,256,0,0,new float[60],false,true));finish(0);
        var before=readShared();assertEquals(255,Byte.toUnsignedInt(before.get(0)));
        assertTrue(WGLNVDXInterop.wglDXUnregisterObjectNV(device,colorObject));colorObject=0;
        assertTrue(WGLNVDXInterop.wglDXUnregisterObjectNV(device,depthObject));depthObject=0;
        GL11.glDeleteTextures(sharedColor);GL11.glDeleteTextures(sharedDepth);
        var resized=NativeDx12.resizeInterop(64,64);
        // Mismatching storage must be rejected even when WGL registration itself succeeds.
        var target=InteropColorTarget.create(device,resized[0],32,64,true);
        sharedColor=target.texture;colorObject=target.object;
        assertFalse(target.direct);assertTrue(target.fallbackReason.contains("64x64; expected 32x64"));
        sharedDepth=GL11.glGenTextures();depthObject=register(resized[1],sharedDepth);
        assertEquals(0,NativeDx12.renderInterop(frame,0,0,0,256,1,0,new float[60],false,true));finish(1);
        assertEquals(before,readShared(),"Rejected alias must preserve the copied output after resize");
        assertEquals(GL11.GL_NO_ERROR,GL11.glGetError());
    }
    @Test void sceneRoundTripPreservesRowsDepthAndGlState(){
        float[] frame=matrix(new Matrix4f());
        NativeDx12.setTerrainEffects(new float[]{1,1,.5f,0,1,.7f,.7f,0,1,0});
        NativeDx12.setProfilingEnabled(true);NativeDx12.renderInterop(frame,0,0,0,256,0,0,new float[0],false,true);finish(0);
        int fbo=GL30.glGenFramebuffers(),color=GL11.glGenTextures(),depth=GL11.glGenTextures();
        var seed=ByteBuffer.allocateDirect(64*64*4);var depthSeed=ByteBuffer.allocateDirect(64*64*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        for(int y=0;y<64;y++)for(int x=0;x<64;x++){seed.put((byte)(x*3)).put((byte)(y*3)).put((byte)(x+y)).put((byte)255);depthSeed.put(.2f+y*.009f);}seed.flip();depthSeed.flip();
        try(InteropSceneCapture capture=new InteropSceneCapture();MemoryStack stack=MemoryStack.stackPush()){
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,color);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,64,64,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,seed);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,depth);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_DEPTH_COMPONENT32F,64,64,0,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,depthSeed);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL11.GL_TEXTURE_2D,depth,0);
            GL11.glViewport(2,3,37,41);GL11.glEnable(GL11.GL_BLEND);GL11.glEnable(GL11.GL_CULL_FACE);GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glEnable(GL11.GL_SCISSOR_TEST);GL11.glScissor(7,8,12,13);GL11.glDepthMask(false);GL11.glColorMask(true,false,true,false);GL13.glActiveTexture(GL13.GL_TEXTURE3);
            GL30.glDisablei(GL11.GL_BLEND,1);GL30.glColorMaski(1,false,true,false,true);
            var objects=stack.pointers(colorObject,depthObject);assertTrue(WGLNVDXInterop.wglDXLockObjectsNV(device,objects));capture.capture(fbo,depth,sharedColor,sharedDepth,64,64);assertTrue(WGLNVDXInterop.wglDXUnlockObjectsNV(device,objects));GL11.glFinish();
            assertEquals(GL13.GL_TEXTURE3,GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE));assertTrue(GL11.glIsEnabled(GL11.GL_BLEND));assertFalse(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
            assertEquals(0,NativeDx12.renderTranslucentInterop(frame,0,0,0,1));assertTrue(NativeDx12.waitInteropFrame(2));assertTrue(NativeDx12.frameMetrics()[0]>=0);
            assertTrue(WGLNVDXInterop.wglDXLockObjectsNV(device,objects));capture.present(fbo,sharedColor,sharedDepth,64,64);assertTrue(WGLNVDXInterop.wglDXUnlockObjectsNV(device,objects));GL11.glFinish();NativeDx12.signalInteropFence(2);
            assertEquals(fbo,GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));assertEquals(GL13.GL_TEXTURE3,GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE));assertTrue(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST));assertTrue(GL11.glIsEnabled(GL11.GL_CULL_FACE));assertTrue(GL11.glIsEnabled(GL11.GL_BLEND));assertFalse(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
            int[] viewport=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);assertArrayEquals(new int[]{2,3,37,41},viewport);
            var mask=stack.malloc(4);GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK,mask);assertEquals(0,mask.get(1));assertNotEquals(0,mask.get(2));
            assertFalse(GL30.glIsEnabledi(GL11.GL_BLEND,1));GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK,1,mask);assertEquals(0,mask.get(0));assertNotEquals(0,mask.get(1));
            var pixels=stack.malloc(64*64*4);var depths=stack.mallocFloat(64*64);GL11.glReadPixels(0,0,64,64,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);GL11.glReadPixels(0,0,64,64,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,depths);
            assertEquals(seed,pixels,"Both vertical flips must preserve every color row");assertEquals(depthSeed,depths,"Depth must follow the same row orientation");
            assertEquals(GL11.GL_NO_ERROR,GL11.glGetError());
            // Resize while water snapshots and the native shadow target are still allocated.
            assertTrue(WGLNVDXInterop.wglDXUnregisterObjectNV(device,colorObject));colorObject=0;assertTrue(WGLNVDXInterop.wglDXUnregisterObjectNV(device,depthObject));depthObject=0;
            GL11.glDeleteTextures(sharedColor);GL11.glDeleteTextures(sharedDepth);sharedColor=GL11.glGenTextures();sharedDepth=GL11.glGenTextures();
            var resized=NativeDx12.resizeInterop(64,64);registerTargets(resized[0],resized[1]);
            assertEquals(0,NativeDx12.renderInterop(frame,0,0,0,256,2,0,new float[0],false,true));finish(2);
        }finally{GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL11.glColorMask(true,true,true,true);GL11.glDepthMask(true);GL30.glDeleteFramebuffers(fbo);GL11.glDeleteTextures(color);GL11.glDeleteTextures(depth);}
    }
    @Test void transparentBudgetRejectionDoesNotStrandTheRenderer(){
        var vertices=ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder());for(float[] p:new float[][]{{-.5f,-.5f},{.5f,-.5f},{.5f,.5f},{-.5f,.5f}})vertex(vertices,p[0],p[1],.4f,-1,4);vertices.flip();
        int quads=128*1024;var many=ByteBuffer.allocateDirect(quads*24).order(ByteOrder.nativeOrder());for(int q=0;q<quads;q++)for(int i:new int[]{0,1,2,2,3,0})many.putInt(i);many.flip();
        float[] frame=matrix(new Matrix4f());
        for(int i=0;i<6;i++){
            NativeDx12.updateTerrainSection(i+1,0,0,0,vertices,4,many,new int[]{0,0,0,quads*6});
            NativeDx12.setVisibleSections(new long[]{1,2,3,4,5,6},i+1,true);
            assertEquals(0,NativeDx12.renderInterop(frame,0,0,0,256,i,0,new float[0],false,true));finish(i);
        }
        var before=readShared();
        assertEquals(NativeDx12.FRAME_SKIPPED_BUDGET,NativeDx12.translucentFrameStatus());
        assertEquals(NativeDx12.FRAME_SKIPPED_BUDGET,NativeDx12.renderTranslucentInterop(frame,0,0,0,6));
        assertEquals(before,readShared(),"Rejected layer must not modify the exported scene");
        assertEquals(0,NativeDx12.renderInterop(frame,0,0,0,256,6,0,new float[0],false,true));finish(6);
        assertEquals(GL11.GL_NO_ERROR,GL11.glGetError());
    }
    @Test void sortedGlassRespectsEntityOcclusionAndReturnsDepth(){
        var vertices=ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder());
        for(int q=0;q<2;q++)for(float[] p:new float[][]{{-.9f,-.9f},{.9f,-.9f},{.9f,.9f},{-.9f,.9f}})vertex(vertices,p[0],p[1],q==0?.4f:.6f,q==0?0x80ff0000:0x800000ff,4);
        vertices.flip();NativeDx12.updateTerrainSection(1,0,0,0,vertices,8,indices(2),new int[]{0,0,0,12});NativeDx12.setVisibleSections(new long[]{1},1,true);
        float[] frame=matrix(new Matrix4f());NativeDx12.renderInterop(frame,0,0,0,256,0,0,new float[0],false,true);finish(0);
        int fbo=GL30.glGenFramebuffers(),color=GL11.glGenTextures(),depth=GL11.glGenTextures();
        try(InteropSceneCapture capture=new InteropSceneCapture();MemoryStack stack=MemoryStack.stackPush()){
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,color);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,64,64,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,depth);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_DEPTH_COMPONENT32F,64,64,0,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,(ByteBuffer)null);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL11.GL_TEXTURE_2D,depth,0);assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE,GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));
            GL11.glColorMask(true,true,true,true);GL11.glDepthMask(true);GL11.glClearColor(0,1,0,1);GL11.glClearDepth(.8);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);GL11.glScissor(0,0,32,64);GL11.glClearColor(1,0,0,1);GL11.glClearDepth(.2);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);GL11.glDisable(GL11.GL_SCISSOR_TEST);
            var objects=stack.pointers(colorObject,depthObject);assertTrue(WGLNVDXInterop.wglDXLockObjectsNV(device,objects));capture.capture(fbo,depth,sharedColor,sharedDepth,64,64);assertTrue(WGLNVDXInterop.wglDXUnlockObjectsNV(device,objects));GL11.glFinish();
            assertEquals(0,NativeDx12.renderTranslucentInterop(frame,0,0,0,1));assertTrue(NativeDx12.waitInteropFrame(2));
            assertTrue(WGLNVDXInterop.wglDXLockObjectsNV(device,objects));capture.present(fbo,sharedColor,sharedDepth,64,64);assertTrue(WGLNVDXInterop.wglDXUnlockObjectsNV(device,objects));GL11.glFinish();NativeDx12.signalInteropFence(2);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);var pixels=stack.malloc(64*64*4);var depths=stack.mallocFloat(64*64);GL11.glReadPixels(0,0,64,64,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);GL11.glReadPixels(0,0,64,64,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,depths);
            int left=(32*64+16)*4,right=(32*64+48)*4;assertEquals(255,Byte.toUnsignedInt(pixels.get(left)));assertEquals(0,Byte.toUnsignedInt(pixels.get(left+2)));assertEquals(.2,depths.get(32*64+16),.00001);
            assertEquals(64,Byte.toUnsignedInt(pixels.get(right)),2);assertEquals(64,Byte.toUnsignedInt(pixels.get(right+1)),2);assertEquals(128,Byte.toUnsignedInt(pixels.get(right+2)),2);assertEquals(.400390625,depths.get(32*64+48),.00001);
            // The same native water mesh responds to water/reflection settings without recompilation.
            var water=ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder());for(float[] p:new float[][]{{-.9f,-.9f},{.9f,-.9f},{.9f,.9f},{-.9f,.9f}})vertex(water,p[0],p[1],.4f,0x80ff0000,5);water.flip();
            NativeDx12.updateTerrainSection(1,0,0,0,water,4,indices(1),new int[]{0,0,0,6});
            int[] green=new int[5];
            for(int scenario=0;scenario<5;scenario++){
                if(scenario==2){
                    var reflector=ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder());
                    for(float[] p:new float[][]{{-.8f,-.09f,.2f},{.8f,-.09f,.2f},{.8f,.09f,.6f},{-.8f,.09f,.6f}})vertex(reflector,p[0],p[1],p[2],0x80ffffff,5);
                    reflector.flip();NativeDx12.updateTerrainSection(1,0,0,0,reflector,4,indices(1),new int[]{0,0,0,6});
                }
                if(scenario>=3)NativeDx12.updateTerrainSection(1,0,0,0,water,4,indices(1),new int[]{0,0,0,6});
                NativeDx12.setMaterialEffects(new float[]{0,0,0,scenario>=3?1:0});
                NativeDx12.setTerrainEffects(new float[]{0,scenario==0?1:0,scenario==0?.7f:scenario==2?1:0,0,0,.7f,.7f,0,1,0});
                long opaque=2+scenario*2;NativeDx12.renderInterop(frame,0,0,0,256,opaque,0,new float[0],false,true);finish(opaque);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL11.glDepthMask(true);GL11.glColorMask(true,true,true,true);GL11.glClearColor(0,1,0,1);GL11.glClearDepth(scenario==3?.5:scenario==4?.95:.8);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);
                GL11.glEnable(GL11.GL_SCISSOR_TEST);GL11.glScissor(0,0,32,64);GL11.glClearColor(1,0,0,1);GL11.glClearDepth(.2);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);GL11.glDisable(GL11.GL_SCISSOR_TEST);
                assertTrue(WGLNVDXInterop.wglDXLockObjectsNV(device,objects));capture.capture(fbo,depth,sharedColor,sharedDepth,64,64);assertTrue(WGLNVDXInterop.wglDXUnlockObjectsNV(device,objects));GL11.glFinish();
                assertEquals(0,NativeDx12.renderTranslucentInterop(frame,0,0,0,opaque+1));assertTrue(NativeDx12.waitInteropFrame(opaque+2));
                assertTrue(WGLNVDXInterop.wglDXLockObjectsNV(device,objects));capture.present(fbo,sharedColor,sharedDepth,64,64);assertTrue(WGLNVDXInterop.wglDXUnlockObjectsNV(device,objects));GL11.glFinish();NativeDx12.signalInteropFence(opaque+2);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL11.glReadPixels(0,0,64,64,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);green[scenario]=Byte.toUnsignedInt(pixels.get(right+1));assertEquals(255,Byte.toUnsignedInt(pixels.get(left)),"foreground entity must remain intact with water effects");
                if(scenario==2){int center=(32*64+32)*4;assertTrue(Byte.toUnsignedInt(pixels.get(center))<120,"Water must reflect the scene rather than remain white");assertEquals(255,Byte.toUnsignedInt(pixels.get(center+1)),1,"SSR must find the green scene; the black sky fallback would darken green");}
            }
            assertTrue(green[0]>green[1]+10,"Refraction must change native water output");
            assertTrue(green[3]>green[4]+1,"Deeper water must absorb more light even with waves and SSR disabled: "+green[3]+" vs "+green[4]);
            assertEquals(GL11.GL_NO_ERROR,GL11.glGetError());
        }finally{GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,0);GL30.glDeleteFramebuffers(fbo);GL11.glDeleteTextures(color);GL11.glDeleteTextures(depth);}
    }
    @Test void materialControlsChangeOnlyTheirSurfacesAndZeroRestoresOutput(){
        var vertices=ByteBuffer.allocateDirect(240).order(ByteOrder.nativeOrder());
        for(int q=0;q<3;q++)for(float[] p:new float[][]{{-.28f,-.6f},{.28f,-.6f},{.28f,.6f},{-.28f,.6f}}){
            vertex(vertices,p[0]+(q-1)*.62f,p[1],.4f,0xff808080,q==0?1:q==1?7:6);
            vertices.put(vertices.position()-3,(byte)90);vertices.put(vertices.position()-2,(byte)-90);
        }
        vertices.flip();NativeDx12.updateTerrainSection(1,0,0,0,vertices,12,indices(3),new int[]{18,0,0,0});NativeDx12.setVisibleSections(new long[]{1},1,true);
        var light=ByteBuffer.allocateDirect(1024).order(ByteOrder.nativeOrder());for(int i=0;i<256;i++)light.putInt(0xff404040);light.flip();NativeDx12.updateLightmap(light);
        float[] frame=matrix(new Matrix4f());ByteBuffer[] result=new ByteBuffer[7];FloatBuffer depth=null;
        for(int step=0;step<7;step++){
            if(step>=5){
                for(int v=0;v<12;v++)vertices.putShort(v*20+6,(short)(step==5?0x00f0:0xf0f0));
                NativeDx12.updateTerrainSection(1,0,0,0,vertices,12,indices(3),new int[]{18,0,0,0});
            }
            NativeDx12.setTerrainEffects(new float[]{0,0,0,0,0,0,step==6?-1:1,0,0,step==2||step==5?1:0});
            NativeDx12.setMaterialEffects(new float[]{step==1||step==6?1:0,step==2||step==5?1:0,step==3?1:0,0});
            assertEquals(0,NativeDx12.renderInterop(frame,0,0,0,256,step,0,new float[0],false,true));finish(step);
            result[step]=readShared();if(step==0)depth=readDepth();else assertEquals(depth,readDepth(),"Material shading cannot alter geometry depth");
        }
        int stone=(32*64+12)*4,metal=(32*64+32)*4,emissive=(32*64+51)*4;
        assertTrue(Byte.toUnsignedInt(result[1].get(metal))>Byte.toUnsignedInt(result[0].get(metal))+10,"Metal must receive a solar highlight");
        assertTrue(Byte.toUnsignedInt(result[2].get(stone))<Byte.toUnsignedInt(result[0].get(stone)),"Rain must darken exposed stone");
        assertEquals(result[0].get(emissive),result[2].get(emissive),"Wetness must not darken emissive material");
        assertTrue(Byte.toUnsignedInt(result[3].get(emissive))>Byte.toUnsignedInt(result[0].get(emissive))+50,"Emissive control must restore unlit surface color");
        assertEquals(result[0].get(stone),result[3].get(stone),"Emissive control cannot brighten ordinary stone");
        assertEquals(result[0],result[4],"Zero must restore every original pixel");
        assertEquals(result[0],result[5],"No skylight must prevent rain wetting interior faces");
        assertEquals(result[0],result[6],"Sun highlights must be disabled at night");
        assertEquals(GL11.GL_NO_ERROR,GL11.glGetError());
    }
    @Test void indexWidthsPreserveBoundaryVerticesAndDrawFallback(){
        float[] frame=matrix(new Matrix4f());long next=0;
        for(int count:new int[]{65536,65540}){
            int base=count-4;var vertices=ByteBuffer.allocateDirect(count*20).order(ByteOrder.nativeOrder());vertices.position(base*20);
            for(float[] p:new float[][]{{-.7f,-.7f},{.7f,-.7f},{.7f,.7f},{-.7f,.7f}})vertex(vertices,p[0],p[1],.4f,0xff0000ff,1);
            vertices.flip();var indices=ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder());for(int i:new int[]{0,1,2,2,3,0})indices.putInt(base+i);indices.flip();
            NativeDx12.updateTerrainSection(1,0,0,0,vertices,count,indices,new int[]{6,0,0,0});NativeDx12.setVisibleSections(new long[]{1},1,true);
            ByteBuffer expected=null;
            for(boolean indirect:new boolean[]{true,false}){
                assertEquals(0,NativeDx12.renderInterop(frame,0,0,0,256,next,0,new float[0],false,indirect));finish(next++);
                var image=readShared();int center=(32*64+32)*4;assertEquals(255,Byte.toUnsignedInt(image.get(center)));assertEquals(0,Byte.toUnsignedInt(image.get(center+1)));
                if(expected==null)expected=image;else assertEquals(expected,image,"Indirect and direct draw must agree at the index-width boundary");
            }
        }
        assertEquals(GL11.GL_NO_ERROR,GL11.glGetError());
    }
    @Test void frontOrderingPreservesColorDepthAndCoplanarTies(){
        for(int key=1;key<=3;key++){
            var vertices=ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder());
            for(float[] p:new float[][]{{-.8f,-.8f},{.8f,-.8f},{.8f,.8f},{-.8f,.8f}})vertex(vertices,p[0],p[1],key==1?.6f:.2f,key==1?0xff0000ff:key==2?0xff00ff00:0xffff0000,1);
            vertices.flip();NativeDx12.updateTerrainSection(key,0,0,key==1?16:0,vertices,4,indices(1),new int[]{6,0,0,0});
        }
        float[] frame=matrix(new Matrix4f().m22(.03f));ByteBuffer expected=null;FloatBuffer expectedDepth=null;
        long[][] orders={{1,2},{2,1},{1,2,3},{1,3,2}};
        for(int i=0;i<orders.length;i++){
            NativeDx12.setVisibleSections(orders[i],orders[i].length,true);NativeDx12.renderInterop(frame,0,0,0,256,i,0,new float[0],false,i%2==0);finish(i);
            var image=readShared();var depth=readDepth();int center=(32*64+32)*4;
            if(i==0){expected=image;expectedDepth=depth;assertEquals(255,Byte.toUnsignedInt(image.get(center+1)));}
            if(i==1){assertEquals(expected,image);assertEquals(expectedDepth,depth);}
            if(i==2)assertEquals(255,Byte.toUnsignedInt(image.get(center+2)),"Stable ties must preserve last coplanar draw");
            if(i==3)assertEquals(255,Byte.toUnsignedInt(image.get(center+1)));
        }
        assertEquals(GL11.GL_NO_ERROR,GL11.glGetError());
    }
    @Test void translucentOrderCacheTracksCameraAndReplacement(){
        var vertices=ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder());
        for(int q=0;q<2;q++)for(float[] p:new float[][]{{-.8f,-.8f},{.8f,-.8f},{.8f,.8f},{-.8f,.8f}})vertex(vertices,p[0],p[1],q==0?.4f:.6f,q==0?0x800000ff:0x80ff0000,4);
        vertices.flip();NativeDx12.updateTerrainSection(1,0,0,0,vertices,8,indices(2),new int[]{0,0,0,12});NativeDx12.setVisibleSections(new long[]{1},1,true);
        ByteBuffer previous=null;
        for(int step=0;step<4;step++){
            double camera=step<2?0:1;
            if(step==3){
                for(int i=0;i<8;i++)vertices.putInt(i*20+8,i<4?0x80ff0000:0x800000ff);
                NativeDx12.updateTerrainSection(1,0,0,0,vertices,8,indices(2),new int[]{0,0,0,12});
            }
            float[] frame=matrix(step<2?new Matrix4f():new Matrix4f().m22(-1));long opaque=step*2;
            assertEquals(0,NativeDx12.renderInterop(frame,0,0,camera,256,opaque,0,new float[0],false,true));finish(opaque);
            assertEquals(0,NativeDx12.renderTranslucentInterop(frame,0,0,camera,opaque+1));finish(opaque+1);
            var image=readShared();int center=(32*64+32)*4;
            if(step==1)assertEquals(previous,image,"Stationary camera must reuse the same sorted result");
            if(step==0||step==1||step==3)assertTrue(Byte.toUnsignedInt(image.get(center))>Byte.toUnsignedInt(image.get(center+2)),"Expected red in front, step="+step);
            else assertTrue(Byte.toUnsignedInt(image.get(center+2))>Byte.toUnsignedInt(image.get(center)),"Moving behind the quads must reverse the blend order");
            previous=image;
        }
        assertEquals(GL11.GL_NO_ERROR,GL11.glGetError());
    }
    @Test void nativeWindChangesFoliageGeometry(){
        var vertices=ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder());for(float[] p:new float[][]{{-.7f,-.7f},{.7f,-.7f},{.7f,.7f},{-.7f,.7f}})vertex(vertices,p[0],p[1],.4f,-1,3);vertices.flip();
        NativeDx12.updateTerrainSection(1,0,0,0,vertices,4,indices(1),new int[]{0,6,0,0});NativeDx12.setVisibleSections(new long[]{1},1,true);float[] frame=matrix(new Matrix4f());
        NativeDx12.setTerrainEffects(new float[]{0,0,0,1,0,.7f,.7f,0,0,0});NativeDx12.renderInterop(frame,0,0,0,256,0,0,new float[0],false,true);finish(0);var a=readShared();
        NativeDx12.setTerrainEffects(new float[]{0,0,0,1,0,.7f,.7f,0,2,0});NativeDx12.renderInterop(frame,0,0,0,256,1,0,new float[0],false,true);finish(1);var b=readShared();
        int different=0;for(int i=0;i<a.capacity();i++)if(a.get(i)!=b.get(i))different++;assertTrue(different>20,"Wind must move native foliage");
        NativeDx12.updateTerrainSection(1,4096,0,0,vertices,4,indices(1),new int[]{0,6,0,0});
        NativeDx12.renderInterop(frame,4095.9999,0,0,256,2,0,new float[0],false,true);finish(2);var nearWrap=readShared();
        NativeDx12.renderInterop(frame,4096.0001,0,0,256,3,0,new float[0],false,true);finish(3);var afterWrap=readShared();
        int wrapDifference=0;for(int i=0;i<nearWrap.capacity();i++)if(nearWrap.get(i)!=afterWrap.get(i))wrapDifference++;
        assertTrue(wrapDifference<10,"Wind phase must remain continuous across the camera origin wrap: "+wrapDifference);
    }
    @Test void nativeShadowMapDarkensReceiverWithoutChangingDepth(){
        var vertices=ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder());
        for(int q=0;q<2;q++){float size=q==0?2:.5f,y=q==0?0:1;for(float[] p:new float[][]{{-size,-size},{-size,size},{size,size},{size,-size}})vertex(vertices,p[0],y,p[1],-1,1);}
        vertices.flip();NativeDx12.updateTerrainSection(1,0,0,0,vertices,8,indices(2),new int[]{12,0,0,0});NativeDx12.setVisibleSections(new long[]{1},1,true);
        float[] frame=matrix(new Matrix4f().m22(.5f).m32(.5f).mul(new Matrix4f().ortho(-3,3,-3,3,.1f,20)).mul(new Matrix4f().lookAt(3,3,3,0,0,0,0,1,0)));
        NativeDx12.setTerrainEffects(new float[]{0,0,0,0,0,.707f,.707f,0,0,0});NativeDx12.renderInterop(frame,0,0,0,256,0,0,new float[0],false,true);finish(0);var before=readShared();var depthBefore=readDepth();
        NativeDx12.setTerrainEffects(new float[]{1,0,0,0,0,.707f,.707f,0,0,0});NativeDx12.renderInterop(frame,0,0,0,256,1,0,new float[0],false,true);finish(1);var after=readShared();
        int dark=0;for(int i=0;i<64*64;i++)if(Byte.toUnsignedInt(before.get(i*4))>Byte.toUnsignedInt(after.get(i*4))+10)dark++;
        assertTrue(dark>8,"DX12 shadow map must shadow receiver, dark pixels="+dark);assertEquals(GL11.GL_NO_ERROR,GL11.glGetError());
        assertEquals(depthBefore,readDepth(),"Shadow sampling must preserve terrain depth");
        NativeDx12.setTerrainEffects(new float[]{1,0,0,0,2,.707f,.707f,0,0,0});NativeDx12.renderInterop(frame,0,0,0,256,2,0,new float[0],false,true);finish(2);
        NativeDx12.setTerrainEffects(new float[10]);NativeDx12.renderInterop(frame,0,0,0,256,3,0,new float[0],false,true);finish(3);
        assertEquals(before,readShared(),"Disabling shadows must restore the original output");
        GL11.glFinish();assertTrue(WGLNVDXInterop.wglDXUnregisterObjectNV(device,colorObject));colorObject=0;assertTrue(WGLNVDXInterop.wglDXUnregisterObjectNV(device,depthObject));depthObject=0;
        GL11.glDeleteTextures(sharedColor);GL11.glDeleteTextures(sharedDepth);sharedColor=GL11.glGenTextures();sharedDepth=GL11.glGenTextures();
        long[] resized=NativeDx12.resizeInterop(64,64);registerTargets(resized[0],resized[1]);
        NativeDx12.setTerrainEffects(new float[]{1,0,0,0,1,.707f,.707f,0,0,0});NativeDx12.renderInterop(frame,0,0,0,256,4,0,new float[0],false,true);finish(4);
        assertEquals(depthBefore,readDepth());assertEquals(GL11.GL_NO_ERROR,GL11.glGetError());
        NativeDx12.setShadowSections(new long[0]);
        NativeDx12.renderInterop(frame,0,0,0,256,5,0,new float[0],false,true);finish(5);
        assertEquals(before,readShared(),"An obsolete or incompatible section must not cast a ghost shadow");
        NativeDx12.setShadowSections(new long[]{1});
        NativeDx12.renderInterop(frame,0,0,0,256,6,0,new float[0],false,true);finish(6);
        assertNotEquals(before,readShared(),"A committed offscreen-capable caster can rejoin the shadow set");
    }
}

class NativeMaterialsInteropTest extends NativeMaterialsInteropTestBase {}
class NativeAliasedMaterialsInteropTest extends NativeMaterialsInteropTestBase {
    @Override boolean aliased(){return true;}
}
