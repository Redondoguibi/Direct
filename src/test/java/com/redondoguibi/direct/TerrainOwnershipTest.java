package com.redondoguibi.direct;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TerrainOwnershipTest {
    private final TerrainOwnership<String> store=new TerrainOwnership<>();
    private TerrainOwnership.Capture<String> accept(long key,Object mesh,Object buffer,String payload){
        store.stage(store.begin(key),payload,List.of(mesh));store.uploaded(mesh,buffer);
        var receipt=store.commit(key,List.of(buffer));assertNotNull(receipt);
        assertEquals(payload,receipt.take());store.nativeUploaded(key,receipt);return receipt;
    }
    @Test void backgroundRebuildDoesNotEvictTheDisplayedVersion(){
        Object buffer=new Object();accept(1,new Object(),buffer,"old");
        var slot=store.begin(1);Object candidate=new Object();store.stage(slot,"new",List.of(candidate));
        assertTrue(store.current(1),"Compilation alone does not change the vanilla buffer");
        store.uploaded(candidate,buffer);assertFalse(store.current(1));
        var receipt=store.commit(1,List.of(buffer));assertEquals("new",receipt.take());
        assertFalse(store.current(1),"GL commit alone is insufficient before native upload");
        store.nativeUploaded(1,receipt);assertTrue(store.current(1));
    }
    @Test void onePendingSectionLeavesOtherSectionsOwnedByDx12(){
        Object a=new Object(),b=new Object();accept(1,new Object(),a,"a");accept(2,new Object(),b,"b");
        Object mesh=new Object();store.stage(store.begin(2),"updated b",List.of(mesh));store.uploaded(mesh,b);
        assertTrue(store.current(1));assertFalse(store.current(2));assertArrayEquals(new long[]{1},store.nativeKeys());
    }
    @Test void partialOrCancelledUploadCannotPublishAMixedMesh(){
        Object a=new Object(),b=new Object(),oldA=new Object(),oldB=new Object();var slot=store.begin(1);
        store.stage(slot,"old",List.of(oldA,oldB));store.uploaded(oldA,a);store.uploaded(oldB,b);
        var old=store.commit(1,List.of(a,b));old.take();store.nativeUploaded(1,old);
        Object newA=new Object(),newB=new Object();store.stage(slot,"new",List.of(newA,newB));
        store.uploaded(newA,a);assertNull(store.commit(1,List.of(a,b)));assertFalse(store.current(1));
        store.uploaded(newB,b);var next=store.commit(1,List.of(a,b));assertEquals("new",next.take());
        store.nativeUploaded(1,old);assertFalse(store.current(1),"An obsolete queued upload must not become current");
        store.nativeUploaded(1,next);assertTrue(store.current(1));
    }
    @Test void resetAndWorldSwitchRejectOldCaptureCompletions(){
        Object mesh=new Object(),buffer=new Object();var old=store.begin(1);store.stage(old,"old",List.of(mesh));
        store.remove(1);store.begin(1);store.uploaded(mesh,buffer);assertNull(store.commit(1,List.of(buffer)));
        accept(1,new Object(),buffer,"fresh");store.clear();assertFalse(store.current(1));assertEquals(0,store.nativeKeys().length);
        store.stage(old,"late",List.of(mesh));store.uploaded(mesh,buffer);assertNull(store.commit(1,List.of(buffer)));
    }
    @Test void externallyReplacedBuffersAndEmptyCommitsInvalidateOnlyTheirSection(){
        Object a=new Object(),b=new Object();accept(1,new Object(),a,"a");accept(2,new Object(),b,"b");
        store.uploaded(new Object(),a);assertFalse(store.current(1));assertTrue(store.current(2));
        assertNull(store.commit(2,List.of()));assertFalse(store.current(2));
    }
    @Test void lateUploadIntoAnOldBufferCannotInvalidateAReusedSection(){
        Object oldBuffer=new Object(),newBuffer=new Object();accept(1,new Object(),oldBuffer,"old");
        store.remove(1);accept(1,new Object(),newBuffer,"new");
        store.uploaded(new Object(),oldBuffer);assertTrue(store.current(1));
    }
}
