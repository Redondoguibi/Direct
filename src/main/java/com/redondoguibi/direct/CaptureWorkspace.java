package com.redondoguibi.direct;

import java.util.IdentityHashMap;
import java.util.function.ToIntFunction;

/** Thread-confined temporary storage. Never holds mesh buffers or states across jobs. */
final class CaptureWorkspace<T> {
    private static final int MAX_RETAINED_QUADS=65536;
    private static final int MAX_STATES=512;
    private byte[] materials=new byte[0];
    private final IdentityHashMap<T,Byte> states=new IdentityHashMap<>();

    byte[] materials(int count) {
        if(count<0)throw new IllegalArgumentException("Negative quad count");
        if(count>MAX_RETAINED_QUADS)return new byte[count];
        if(materials.length<count)materials=new byte[Math.min(MAX_RETAINED_QUADS,Math.max(count,Math.max(256,materials.length*2)))];
        return materials;
    }

    byte material(T state,ToIntFunction<T> classifier) {
        Byte cached=states.get(state);
        if(cached!=null)return cached;
        byte value=(byte)classifier.applyAsInt(state);
        if(states.size()<MAX_STATES)states.put(state,value);
        return value;
    }

    void clearStates(){states.clear();}
}
