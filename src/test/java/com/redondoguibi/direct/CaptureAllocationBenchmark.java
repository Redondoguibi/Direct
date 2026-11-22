package com.redondoguibi.direct;

import java.lang.management.ManagementFactory;
import java.util.*;

/** Allocation comparison of temporary material workspaces, not a Minecraft FPS benchmark. */
public final class CaptureAllocationBenchmark {
    private static volatile int checksum;
    public static void main(String[] args){
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().threadId();
        Object[] states=new Object[16];Arrays.setAll(states,i->new Object());
        var workspace=new CaptureWorkspace<Object>();
        for(int round=0;round<5;round++)for(boolean old:new boolean[]{true,false}){
            long before=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();int calls=0;
            for(int chunk=0;chunk<500;chunk++){
                for(int layer=0;layer<4;layer++){
                    byte[] materials=old?new byte[4096]:workspace.materials(4096);
                    var cache=old?new IdentityHashMap<Object,Byte>():null;
                    for(int q=0;q<4096;q++){
                        Object state=states[q%16];
                        if(old){Byte value=cache.get(state);if(value==null){value=1;cache.put(state,value);calls++;}materials[q]=value;}
                        else materials[q]=workspace.material(state,k->1);
                    }
                    checksum=materials[4095];
                }
                workspace.clearStates();
            }
            long allocated=bean.getThreadAllocatedBytes(thread)-before;
            if(round>=2)System.out.printf(Locale.ROOT,"CAPTURE old=%s bytes=%d ms=%.3f checksum=%d oldClassifications=%d%n",old,allocated,(System.nanoTime()-start)/1e6,checksum,calls);
        }
    }
}
