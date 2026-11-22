package com.redondoguibi.direct;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PendingUploadQueueTest {
    private record Work(long bytes,boolean current){}
    @Test void replacementCoalescesAndPreservesFifo(){
        var q=new PendingUploadQueue<Work>(Work::bytes);
        q.put(1,new Work(10,true));q.put(2,new Work(20,true));q.put(1,new Work(30,true));
        assertEquals(2,q.size());assertEquals(50,q.bytes());
        assertEquals(30,q.poll(100,false,Work::current).bytes());
        assertEquals(20,q.poll(100,false,Work::current).bytes());assertEquals(0,q.bytes());
    }
    @Test void obsoleteOversizedHeadCannotBlockValidWork(){
        var q=new PendingUploadQueue<Work>(Work::bytes);
        q.put(1,new Work(1000,false));q.put(2,new Work(10,true));
        assertEquals(10,q.poll(10,false,Work::current).bytes());assertTrue(q.isEmpty());assertEquals(0,q.bytes());
    }
    @Test void liveOversizedHeadWaitsForNextFrameWithoutLosingItsPlace(){
        var q=new PendingUploadQueue<Work>(Work::bytes);q.put(1,new Work(100,true));q.put(2,new Work(10,true));
        assertNull(q.poll(10,false,Work::current));assertEquals(110,q.bytes());
        assertEquals(100,q.poll(10,true,Work::current).bytes());assertEquals(10,q.bytes());
        q.clear();assertFalse(q.containsKey(2));assertEquals(0,q.bytes());
    }
    @Test void deletionWithZeroCostStillRunsAtZeroBudget(){
        var q=new PendingUploadQueue<Work>(Work::bytes);q.put(1,new Work(0,true));
        assertNotNull(q.poll(0,false,Work::current));assertNull(q.poll(0,false,Work::current));
    }
}
