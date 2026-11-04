package com.redondoguibi.direct;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import java.nio.file.Path;

@Tag("graphics")
class NativeSemaphoreInteropTest {
    @Test void diagnosesEachStageOfD3d12FenceImport() {
        System.load(Path.of("build/generated/nativeResources/META-INF/native/windows-x86_64/direct_renderer.dll").toAbsolutePath().toString());
        assertTrue(GLFW.glfwInit());GLFW.glfwDefaultWindowHints();GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);
        long window=GLFW.glfwCreateWindow(32,32,"Interop probe",0,0);assertNotEquals(0,window);
        int semaphore=0;
        try {
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            long[] handles=NativeDx12.initializeInterop(32,32,true,16,8,64);
            System.out.println("GL renderer="+GL11.glGetString(GL11.GL_RENDERER)+"; driver="+GL11.glGetString(GL11.GL_VERSION));
            assertEquals(0,GL11.glGetError(),"native initialization must not leave GL errors");
            assertTrue(GL.getCapabilities().GL_EXT_semaphore_win32);
            System.out.println("DX12 adapter="+NativeDx12.interopAdapterName());
            System.out.println("Direct memory import capability="+GL.getCapabilities().GL_EXT_memory_object_win32);
            var result=InteropSemaphore.importFence(handles[3]);semaphore=result.semaphore();
            System.out.println("D3D12 fence import error=0x"+Integer.toHexString(result.error()));
            if(result.error()==GL11.GL_INVALID_ENUM){
                assertEquals(0,semaphore);var repeated=InteropSemaphore.importFence(handles[3]);
                assertTrue(repeated.cached());assertEquals(GL11.GL_INVALID_ENUM,repeated.error());
                NativeDx12.signalInteropFence(1); // CPU fallback fence remains usable after rejected import.
            }else{assertEquals(0,result.error());assertNotEquals(0,semaphore);}
            assertEquals(0,GL11.glGetError(),"fallback must not leave stale GL errors");
            if(GL.getCapabilities().GL_EXT_memory_object_win32){
                long resource=NativeDx12.createDirectMemoryProbe();assertNotEquals(0,resource);
                int memory=EXTMemoryObject.glCreateMemoryObjectsEXT(),texture=GL11.glGenTextures();
                try{
                    EXTMemoryObject.glMemoryObjectParameteriEXT(memory,EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT,GL11.GL_TRUE);
                    assertEquals(0,GL11.glGetError());
                    // Revision 10 of EXT_external_objects_win32 requires size=0 for compatibility.
                    EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(memory,0,EXTMemoryObjectWin32.GL_HANDLE_TYPE_D3D12_RESOURCE_EXT,resource);
                    int importError=GL11.glGetError();System.out.println("D3D12 resource import error=0x"+Integer.toHexString(importError));
                    if(importError==0){
                        GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
                        EXTMemoryObject.glTexStorageMem2DEXT(GL11.GL_TEXTURE_2D,1,GL11.GL_RGBA8,32,32,memory,0);
                        assertEquals(0,GL11.glGetError(),"Imported memory must provide a valid texture");
                        var pixels=java.nio.ByteBuffer.allocateDirect(32*32*4);GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
                        assertEquals(64,Byte.toUnsignedInt(pixels.get(0)),1);assertEquals(128,Byte.toUnsignedInt(pixels.get(1)),1);assertEquals(191,Byte.toUnsignedInt(pixels.get(2)),1);
                    }else assertTrue(importError==GL11.GL_INVALID_ENUM||importError==GL11.GL_INVALID_OPERATION,"Unexpected memory import failure: "+importError);
                }finally{GL11.glDeleteTextures(texture);EXTMemoryObject.glDeleteMemoryObjectsEXT(memory);}
            }
        }finally{
            if(semaphore!=0)EXTSemaphore.glDeleteSemaphoresEXT(semaphore);
            NativeDx12.shutdownInterop();GL.setCapabilities(null);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();
        }
    }
}
