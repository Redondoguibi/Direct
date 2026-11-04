package com.redondoguibi.direct;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/** Coalesced FIFO; caller owns synchronization. Obsolete work never consumes a frame budget. */
final class PendingUploadQueue<T> {
    private final Long2ObjectLinkedOpenHashMap<T> entries=new Long2ObjectLinkedOpenHashMap<>();
    private final ToLongFunction<T> size;
    private long bytes;
    PendingUploadQueue(ToLongFunction<T> size){this.size=size;}
    void put(long key,T value){
        T previous=entries.put(key,value);
        if(previous!=null)bytes-=size.applyAsLong(previous);
        bytes+=size.applyAsLong(value);
    }
    T poll(long budget,boolean allowOversized,Predicate<T> current){
        while(!entries.isEmpty()){
            T next=entries.get(entries.firstLongKey());
            long cost=size.applyAsLong(next);
            if(!current.test(next)){entries.removeFirst();bytes-=cost;continue;}
            if(!allowOversized&&cost>budget)return null;
            entries.removeFirst();bytes-=cost;return next;
        }
        return null;
    }
    boolean containsKey(long key){return entries.containsKey(key);}
    boolean isEmpty(){return entries.isEmpty();}
    int size(){return entries.size();}
    long bytes(){return bytes;}
    void clear(){entries.clear();bytes=0;}
}
