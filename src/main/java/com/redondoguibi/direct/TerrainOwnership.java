package com.redondoguibi.direct;

import java.util.*;

/** Called under the mesh-store lock. Captures are usable only after actual vanilla upload/commit. */
final class TerrainOwnership<T> {
    static final class Slot { final long key; Slot(long key){this.key=key;} }
    static final class Capture<T> {
        final Slot slot; T payload;
        Capture(Slot slot,T payload){this.slot=slot;this.payload=payload;}
        T take(){T value=payload;payload=null;return value;}
    }
    private final Map<Long,Slot> slots=new HashMap<>();
    private final WeakHashMap<Object,Capture<T>> sources=new WeakHashMap<>();
    private final WeakHashMap<Object,Capture<T>> buffers=new WeakHashMap<>();
    private final Map<Long,Capture<T>> committed=new HashMap<>(),nativeCopies=new HashMap<>();
    Slot begin(long key){return slots.computeIfAbsent(key,Slot::new);}
    void stage(Slot slot,T payload,Collection<?> meshes){
        if(slots.get(slot.key)!=slot)return;
        var capture=new Capture<>(slot,payload);
        for(Object mesh:meshes)sources.put(mesh,capture);
    }
    void uploaded(Object mesh,Object buffer){
        var old=buffers.remove(buffer);
        if(old!=null&&committed.get(old.slot.key)==old)committed.remove(old.slot.key);
        var capture=sources.remove(mesh);
        if(capture!=null&&slots.get(capture.slot.key)==capture.slot){
            buffers.put(buffer,capture);
        }
    }
    Capture<T> commit(long key,Collection<?> layers){
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
        var capture=committed.get(key);
        if(capture!=null&&capture==receipt)nativeCopies.put(key,capture);else nativeCopies.remove(key);
    }
    boolean current(long key){return committed.containsKey(key)&&committed.get(key)==nativeCopies.get(key);}
    boolean accepts(long key,Object receipt){return receipt!=null&&committed.get(key)==receipt;}
    long[] nativeKeys(){return nativeCopies.keySet().stream().filter(this::current).mapToLong(Long::longValue).toArray();}
    void remove(long key){slots.remove(key);committed.remove(key);nativeCopies.remove(key);}
    void clear(){slots.clear();sources.clear();buffers.clear();committed.clear();nativeCopies.clear();}
}
