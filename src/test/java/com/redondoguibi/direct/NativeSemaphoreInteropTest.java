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
            var result=InteropSemaphore.importFence(handles[3]);semaphore=result.semaphore();
            System.out.println("D3D12 fence import error=0x"+Integer.toHexString(result.error()));
            if(result.error()==GL11.GL_INVALID_ENUM){
                assertEquals(0,semaphore);var repeated=InteropSemaphore.importFence(handles[3]);
                assertTrue(repeated.cached());assertEquals(GL11.GL_INVALID_ENUM,repeated.error());
                NativeDx12.signalInteropFence(1); // CPU fallback fence remains usable after rejected import.
            }else{assertEquals(0,result.error());assertNotEquals(0,semaphore);}
            assertEquals(0,GL11.glGetError(),"fallback must not leave stale GL errors");
        }finally{
            if(semaphore!=0)EXTSemaphore.glDeleteSemaphoresEXT(semaphore);
            NativeDx12.shutdownInterop();GL.setCapabilities(null);GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();
        }
    }
}
