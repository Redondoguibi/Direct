package com.redondoguibi.direct;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;

class CaptureWorkspaceTest {
    @Test void reusesScratchButDoesNotRetainOversizedArrays(){
        var workspace=new CaptureWorkspace<Object>();
        var first=workspace.materials(1024);
        assertSame(first,workspace.materials(32));
        assertEquals(65537,workspace.materials(65537).length);
        assertSame(first,workspace.materials(32));
        assertThrows(IllegalArgumentException.class,()->workspace.materials(-1));
    }
    @Test void cachesByIdentityAcrossLayersAndClearsBetweenJobs(){
        var workspace=new CaptureWorkspace<String>();var calls=new AtomicInteger();
        String a=new String("same"),b=new String("same");
        assertEquals(1,workspace.material(a,k->calls.incrementAndGet()));
        assertEquals(1,workspace.material(a,k->calls.incrementAndGet()));
        assertEquals(2,workspace.material(b,k->calls.incrementAndGet()));
        workspace.clearStates();
        assertEquals(3,workspace.material(a,k->calls.incrementAndGet()));
    }
    @Test void stateRetentionIsBoundedWithoutChangingUncachedResults(){
        var workspace=new CaptureWorkspace<Object>();Object[] states=new Object[513];
        for(int i=0;i<states.length;i++){states[i]=new Object();assertEquals(7,workspace.material(states[i],k->7));}
        assertEquals(7,workspace.material(states[0],k->8));
        assertEquals(8,workspace.material(states[512],k->8));
    }
}
