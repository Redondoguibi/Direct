package com.redondoguibi.direct;

import java.lang.management.ManagementFactory;
import java.util.HashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/** Focused CPU benchmark; measures membership checks, never claims Minecraft FPS. */
public final class TerrainLookupBenchmark {
    private static volatile long sink;
    public static void main(String[] args){
        int count=4096,repeats=2000;long[] keys=new long[count];
        var boxed=new HashSet<Long>();var primitive=new LongOpenHashSet(count);
        for(int i=0;i<count;i++){keys[i]=(i*73856093L)^0x193aec780000L;if(i%7!=0){boxed.add(keys[i]);primitive.add(keys[i]);}}
        for(int i=0;i<4;i++){boxed(boxed,keys,repeats);primitive(primitive,keys,repeats);}
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        double[] a=new double[9],b=new double[9];long bytesA=0,bytesB=0;
        for(int i=0;i<9;i++){
            long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().threadId()),start=System.nanoTime();
            long x=boxed(boxed,keys,repeats);a[i]=(System.nanoTime()-start)/(double)(count*repeats);bytesA+=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocated;
            allocated=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());start=System.nanoTime();
            long y=primitive(primitive,keys,repeats);b[i]=(System.nanoTime()-start)/(double)(count*repeats);bytesB+=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocated;
            if(x!=y)throw new AssertionError("Membership changed");sink=x+y;
        }
        java.util.Arrays.sort(a);java.util.Arrays.sort(b);
        System.out.printf(java.util.Locale.ROOT,"keys=%d repeats=%d rounds=9; boxed median %.3f ns/lookup; primitive median %.3f ns/lookup; allocated boxed=%d primitive=%d bytes; checksum=%d%n",count,repeats,a[4],b[4],bytesA,bytesB,sink);
    }
    private static long boxed(HashSet<Long> set,long[] keys,int repeats){long sum=0;for(int j=0;j<repeats;j++)for(long key:keys)if(set.contains(key))sum++;return sum;}
    private static long primitive(LongOpenHashSet set,long[] keys,int repeats){long sum=0;for(int j=0;j<repeats;j++)for(long key:keys)if(set.contains(key))sum++;return sum;}
}
