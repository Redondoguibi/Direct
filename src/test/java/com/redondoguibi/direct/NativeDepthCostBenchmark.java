package com.redondoguibi.direct;
import java.nio.*;
import org.joml.Matrix4f;
/** Isolated GPU bandwidth workload. Results are not a Minecraft FPS measurement. */
public final class NativeDepthCostBenchmark {
    public static void main(String[] args){
        System.load(args[0]);NativeDx12.initializeInterop(1920,1080,false,16,8,64);
        try{
            boolean aliased=args.length>1&&Boolean.parseBoolean(args[1]);
            if(aliased)NativeDx12.configureColorAlias(true);
            boolean materials=args.length>2&&Boolean.parseBoolean(args[2]);
            if(materials){NativeDx12.setMaterialEffects(new float[]{.3f,.4f,.5f,0});NativeDx12.setTerrainEffects(new float[]{0,0,0,0,0,0,1,0,0,1});}
            ByteBuffer atlas=ByteBuffer.allocateDirect(64);for(int i=0;i<16;i++)atlas.putInt(-1);atlas.flip();
            NativeDx12.updateTerrainAtlas(new ByteBuffer[]{atlas},4,4,false,1);
            ByteBuffer light=ByteBuffer.allocateDirect(1024);for(int i=0;i<256;i++)light.putInt(-1);light.flip();NativeDx12.updateLightmap(light);
            ByteBuffer vertices=ByteBuffer.allocateDirect(materials?80:64).order(ByteOrder.nativeOrder());
            for(float[] p:new float[][]{{-.9f,-.9f},{.9f,-.9f},{.9f,.9f},{-.9f,.9f}}){
                vertices.putShort((short)Math.round((p[0]+16)*1024)).putShort((short)Math.round((p[1]+16)*1024)).putShort((short)Math.round(16.25*1024));
                vertices.putShort((short)0xf0f0).putInt(-1).putShort((short)32768).putShort((short)32768);
                if(materials)vertices.put((byte)0).put((byte)90).put((byte)-90).put((byte)7);
            }vertices.flip();ByteBuffer indices=ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder());for(int i:new int[]{0,1,2,2,3,0})indices.putInt(i);indices.flip();
            long[] keys=new long[16];for(int i=0;i<16;i++){keys[i]=i+1;NativeDx12.updateTerrainSection(i+1,0,0,0,vertices,4,indices,new int[]{6,0,0});}NativeDx12.setVisibleSections(keys,16,true);
            float[] matrix=new float[28];new Matrix4f().get(matrix);matrix[20]=100;matrix[21]=200;for(int i=24;i<28;i++)matrix[i]=1;
            NativeDx12.setProfilingEnabled(true);double[] timings=new double[120],wall=new double[120];
            for(int i=0;i<150;i++){
                long start=System.nanoTime();int status=NativeDx12.renderInterop(matrix,0,0,0,256,i,0,new float[0],false,true);
                if(status!=0||!NativeDx12.waitInteropFrame(i+1))throw new AssertionError("frame unavailable");
                double elapsed=(System.nanoTime()-start)/1e6;double ms=NativeDx12.frameMetrics()[0];if(ms<0)throw new AssertionError("missing timestamp");if(i>=30){timings[i-30]=ms;wall[i-30]=elapsed;}
                NativeDx12.signalInteropFence(i+1);
            }
            java.util.Arrays.sort(timings);java.util.Arrays.sort(wall);System.out.printf(java.util.Locale.ROOT,"colorAlias=%s materials=%s adapter=%s GPU workload 1080p 16 overlapping quads: p50=%.4f ms p95=%.4f ms wallP50=%.4f ms wallP95=%.4f ms%n",aliased,materials,NativeDx12.interopAdapterName(),timings[60],timings[113],wall[60],wall[113]);
        }finally{NativeDx12.shutdownInterop();}
    }
}
