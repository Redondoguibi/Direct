package com.redondoguibi.direct;

import java.util.*;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/** Called under the mesh-store lock. Captures are usable only after actual vanilla upload/commit. */
final class TerrainOwnership<T> {
    static final class Slot { final long key; Slot(long key){this.key=key;} }
    static final class Capture<T> {
        final Slot slot; T payload;
        Capture(Slot slot,T payload){this.slot=slot;this.payload=payload;}
        T take(){T value=payload;payload=null;return value;}
    }
    private final Long2ObjectOpenHashMap<Slot> slots=new Long2ObjectOpenHashMap<>();
    private final WeakHashMap<Object,Capture<T>> sources=new WeakHashMap<>();
    private final WeakHashMap<Object,Capture<T>> buffers=new WeakHashMap<>();
    private final Long2ObjectOpenHashMap<Capture<T>> committed=new Long2ObjectOpenHashMap<>(),nativeCopies=new Long2ObjectOpenHashMap<>();
    private long[] nativeKeySnapshot;
    Slot begin(long key){Slot slot=slots.get(key);if(slot==null){slot=new Slot(key);slots.put(key,slot);}return slot;}
    void stage(Slot slot,T payload,Collection<?> meshes){
        if(slots.get(slot.key)!=slot)return;
        var capture=new Capture<>(slot,payload);
        for(Object mesh:meshes)sources.put(mesh,capture);
    }
    void uploaded(Object mesh,Object buffer){
        nativeKeySnapshot=null;
        var old=buffers.remove(buffer);
        if(old!=null&&committed.get(old.slot.key)==old)committed.remove(old.slot.key);
        var capture=sources.remove(mesh);
        if(capture!=null&&slots.get(capture.slot.key)==capture.slot){
            buffers.put(buffer,capture);
        }
    }
    Capture<T> commit(long key,Collection<?> layers){
        nativeKeySnapshot=null;
        Capture<T> result=null;
        for(Object buffer:layers){
            var capture=buffers.get(buffer);
            if(capture==null||capture.slot.key!=key||slots.get(key)!=capture.slot||(result!=null&&capture!=result)){
                committed.remove(key);return null;
            }
            result=capture;
        }
        if(result!=null)committed.put(key,result);else committed.remove(key);
        return result;
    }
    void nativeUploaded(long key,Object receipt){
        nativeKeySnapshot=null;
        var capture=committed.get(key);
        if(capture!=null&&capture==receipt)nativeCopies.put(key,capture);else nativeCopies.remove(key);
    }
    boolean current(long key){var capture=committed.get(key);return capture!=null&&capture==nativeCopies.get(key);}
    /** Copies values into caller-owned scratch storage; never exposes ownership maps or receipts. */
    void copyCurrent(long[] keys,int count,boolean[] current){
        Objects.checkFromIndexSize(0,count,keys.length);
        Objects.checkFromIndexSize(0,count,current.length);
        for(int i=0;i<count;i++)current[i]=current(keys[i]);
    }
    boolean accepts(long key,Object receipt){return receipt!=null&&committed.get(key)==receipt;}
    // Callers only read this snapshot under the store lock. Invalidate on every ownership mutation.
    long[] nativeKeys(){
        if(nativeKeySnapshot==null){
            long[] keys=new long[nativeCopies.size()];int count=0;
            for(var entry:nativeCopies.long2ObjectEntrySet())if(committed.get(entry.getLongKey())==entry.getValue())keys[count++]=entry.getLongKey();
            nativeKeySnapshot=count==keys.length?keys:Arrays.copyOf(keys,count);
        }
        return nativeKeySnapshot;
    }
    void remove(long key){nativeKeySnapshot=null;slots.remove(key);committed.remove(key);nativeCopies.remove(key);}
    void clear(){nativeKeySnapshot=null;slots.clear();sources.clear();buffers.clear();committed.clear();nativeCopies.clear();}
}
