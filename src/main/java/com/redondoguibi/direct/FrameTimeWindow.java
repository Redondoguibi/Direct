package com.redondoguibi.direct;

import java.util.Arrays;

/** Bounded recent percentile samples and complete-window mean, with explicit sample count. */
final class FrameTimeWindow {
    record Summary(long frames,int samples,double fps,double meanMs,double p50Ms,double p95Ms,double p99Ms,double maxMs){}
    private final long[] samples;
    private int index,size;
    private long count,total,maximum;
    FrameTimeWindow(int capacity){if(capacity<1)throw new IllegalArgumentException("capacity");samples=new long[capacity];}
    void add(long nanos){if(nanos<=0)return;samples[index]=nanos;index=(index+1)%samples.length;size=Math.min(size+1,samples.length);count++;total+=nanos;maximum=Math.max(maximum,nanos);}
    Summary summary(){
        long[] ordered=Arrays.copyOf(samples,size);Arrays.sort(ordered);
        return new Summary(count,size,total==0?0:1e9*count/total,count==0?0:total/1e6/count,
                percentile(ordered,.50),percentile(ordered,.95),percentile(ordered,.99),maximum/1e6);
    }
    private static double percentile(long[] data,double rank){return data.length==0?0:data[Math.max(0,(int)Math.ceil(data.length*rank)-1)]/1e6;}
    void clear(){index=size=0;count=total=maximum=0;}
}
