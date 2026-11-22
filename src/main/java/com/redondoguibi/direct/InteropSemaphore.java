package com.redondoguibi.direct;

import java.util.Map;
import java.util.WeakHashMap;
import org.lwjgl.opengl.*;

/** A rejected handle type is a context capability failure, not a per-frame retry. */
final class InteropSemaphore {
    record Result(int semaphore,int error,boolean cached) {}
    private static final Map<GLCapabilities,Integer> rejected=new WeakHashMap<>();
    static Result importFence(long handle) {
        var capabilities=GL.getCapabilities();
        Integer previous=rejected.get(capabilities);
        if(previous!=null)return new Result(0,previous,true);
        int pending;
        for(int i=0;i<64&&(pending=GL11.glGetError())!=GL11.GL_NO_ERROR;i++)
            Direct.LOGGER.warn("Pre-existing OpenGL error before semaphore creation: 0x{}",Integer.toHexString(pending));
        int semaphore=EXTSemaphore.glGenSemaphoresEXT();
        int error=GL11.glGetError();
        if(error!=GL11.GL_NO_ERROR||semaphore==0)throw new IllegalStateException("Semaphore allocation failed: 0x"+Integer.toHexString(error));
        EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(semaphore,EXTSemaphoreWin32.GL_HANDLE_TYPE_D3D12_FENCE_EXT,handle);
        error=GL11.glGetError();
        if(error!=GL11.GL_NO_ERROR){
            EXTSemaphore.glDeleteSemaphoresEXT(semaphore);
            if(error==GL11.GL_INVALID_ENUM)rejected.put(capabilities,error);
            return new Result(0,error,false);
        }
        return new Result(semaphore,0,false);
    }
    private InteropSemaphore(){}
}
