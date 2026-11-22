package com.redondoguibi.direct;

import java.nio.*;
import java.util.*;

/** CPU-only comparison of the old and current sequential index generators. */
public final class ChunkIndexBenchmark {
    private static volatile int check;
    public static void main(String[] args) {
        int count=4096*6;
        var buffer=ByteBuffer.allocateDirect(count*4).order(ByteOrder.nativeOrder());
        for(int round=0;round<8;round++)for(boolean old:new boolean[]{true,false}){
            long start=System.nanoTime();
            for(int n=0;n<1000;n++){
                buffer.clear();
                if(old){int[] pattern={0,1,2,2,3,0};for(int i=0;i<count;i++)buffer.putInt(i/6*4+pattern[i%6]);}
                else TerrainIndexPacker.packSequentialQuadIndices(buffer,count,0);
                check=buffer.getInt((count-1)*4);
            }
            if(round>=3)System.out.printf(Locale.ROOT,"INDEX old=%s ms=%.3f checksum=%d%n",old,(System.nanoTime()-start)/1e6,check);
        }
    }
}
