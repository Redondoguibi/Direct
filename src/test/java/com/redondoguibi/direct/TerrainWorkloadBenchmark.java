package com.redondoguibi.direct;

import java.nio.*;
import java.util.*;
import org.joml.Matrix4f;

/** Real native frames; controlled section overlap, visibility order and upload traffic. */
public final class TerrainWorkloadBenchmark {
    public static void main(String[] args) {
        System.load(args[0]);
        boolean parallel=Boolean.parseBoolean(args[1]);
        String order=args[2];
        NativeDx12.initializeInterop(1920,1080,parallel,32,8,128);
        try {
            NativeDx12.configureColorAlias(true);
            var atlas=ByteBuffer.allocateDirect(64);while(atlas.hasRemaining())atlas.putInt(-1);atlas.flip();
            NativeDx12.updateTerrainAtlas(new ByteBuffer[]{atlas},4,4,false,1);
            var light=ByteBuffer.allocateDirect(1024);while(light.hasRemaining())light.putInt(-1);light.flip();NativeDx12.updateLightmap(light);
            int quads=256,sections=1024;
            var vertices=ByteBuffer.allocateDirect(quads*80).order(ByteOrder.nativeOrder());
            var indices=ByteBuffer.allocateDirect(quads*24).order(ByteOrder.nativeOrder());
            for(int q=0;q<quads;q++){
                float x=(q%16),y=q/16;
                for(float[] xy:new float[][]{{x,y},{x+1,y},{x+1,y+1},{x,y+1}})
                    vertices.putShort((short)Math.round((xy[0]+16)*1024)).putShort((short)Math.round((xy[1]+16)*1024)).putShort((short)(16*1024)).putShort((short)0xf0f0).putInt(-1).putShort((short)32768).putShort((short)32768).putInt(0x01007f00);
                for(int i:new int[]{0,1,2,2,3,0})indices.putInt(q*4+i);
            }
            vertices.flip();indices.flip();
            float[] matrix=new float[28];new Matrix4f().orthoLH(0,128,0,128,.1f,300,true).get(matrix);matrix[20]=500;matrix[21]=600;for(int i=24;i<28;i++)matrix[i]=1;
            long[] keys=new long[sections];for(int i=0;i<sections;i++)keys[i]=i+1;
            if(order.equals("far")){for(int i=0;i<sections/2;i++){long a=keys[i];keys[i]=keys[sections-i-1];keys[sections-i-1]=a;}}
            if(order.equals("mixed")){var rng=new Random(42);for(int i=sections-1;i>0;i--){int j=rng.nextInt(i+1);long a=keys[i];keys[i]=keys[j];keys[j]=a;}}
            long uploadStart=System.nanoTime();
            for(int i=0;i<sections;i++)NativeDx12.updateTerrainSection(i+1,(i%8)*16,((i/8)%8)*16,16+(i/64)*16,vertices,quads*4,indices,new int[]{quads*6,0,0,0});
            double uploadMs=(System.nanoTime()-uploadStart)/1e6;
            NativeDx12.setVisibleSections(keys,sections,true);NativeDx12.setProfilingEnabled(true);
            double[] gpu=new double[100],cpu=new double[100],wall=new double[100];
            for(int frame=0;frame<125;frame++){
                long start=System.nanoTime();int status=NativeDx12.renderInterop(matrix,0,0,0,256,frame,0,new float[0],false,true);long submitted=System.nanoTime();
                if(status!=0||!NativeDx12.waitInteropFrame(frame+1))throw new AssertionError("Frame failed");
                if(frame>=25){int at=frame-25;gpu[at]=NativeDx12.frameMetrics()[0];cpu[at]=(submitted-start)/1e6;wall[at]=(System.nanoTime()-start)/1e6;}
                NativeDx12.signalInteropFence(frame+1);
            }
            Arrays.sort(gpu);Arrays.sort(cpu);Arrays.sort(wall);
            System.out.printf(Locale.ROOT,"TERRAIN order=%s parallel=%s uploadMs=%.3f arenaMiB=%.1f gpuP50=%.4f gpuP95=%.4f cpuP50=%.4f wallP50=%.4f%n",order,parallel,uploadMs,NativeDx12.frameMetrics()[3]/1048576,gpu[50],gpu[94],cpu[50],wall[50]);
        } finally { NativeDx12.shutdownInterop(); }
    }
}
