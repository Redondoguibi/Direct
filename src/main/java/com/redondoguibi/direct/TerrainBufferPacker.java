package com.redondoguibi.direct;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Packs Minecraft block-format mesh buffers into Direct's compact vertex/index layout.
 */
final class TerrainBufferPacker {
    private static final int OUTPUT_VERTEX_SIZE = 16;
    private static final float POSITION_SCALE = 1024.0f;
    private static final float POSITION_ORIGIN = -16.0f;

    private TerrainBufferPacker() {
    }

    static void packVertices(ByteBuffer destination, ByteBuffer source, int vertexCount, int stride,
            int positionOffset, int colorOffset, int uvOffset) {
        packVertices(destination, source, vertexCount, stride, positionOffset, colorOffset, uvOffset, -1);
    }

    static void packVertices(ByteBuffer destination, ByteBuffer source, int vertexCount, int stride,
            int positionOffset, int colorOffset, int uvOffset, int lightOffset) {
        long sourceBytes = (long) vertexCount * stride;
        long destinationBytes = (long) vertexCount * OUTPUT_VERTEX_SIZE;
        if (vertexCount < 0 || stride <= 0 || positionOffset < 0 || colorOffset < 0 || uvOffset < 0
                || (long) positionOffset + 12 > stride || (long) colorOffset + 4 > stride
                || (long) uvOffset + 8 > stride || lightOffset < -1
                || (lightOffset >= 0 && (long) lightOffset + 4 > stride)
                || sourceBytes > source.remaining() || destinationBytes > destination.remaining()) {
            throw new IllegalArgumentException("Terrain vertex pack would exceed a buffer's remaining range.");
        }

        // Use bounded NIO reads/writes. Each layer advances the shared destination exactly once.
        ByteBuffer sourceVertices = source.duplicate().order(ByteOrder.nativeOrder());
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int offset = sourceVertices.position() + vertex * stride;
            writeVertex(destination,sourceVertices,offset,positionOffset,colorOffset,uvOffset,lightOffset);
        }
    }

    private static void writeVertex(ByteBuffer destination,ByteBuffer sourceVertices,int offset,int positionOffset,int colorOffset,int uvOffset,int lightOffset){
            destination.putShort(packPosition(sourceVertices.getFloat(offset + positionOffset)));
            destination.putShort(packPosition(sourceVertices.getFloat(offset + positionOffset + Float.BYTES)));
            destination.putShort(packPosition(sourceVertices.getFloat(offset + positionOffset + 2 * Float.BYTES)));
            int lightU = lightOffset < 0 ? 0 : Math.clamp(sourceVertices.getShort(offset + lightOffset), 0, 255);
            int lightV = lightOffset < 0 ? 0 : Math.clamp(sourceVertices.getShort(offset + lightOffset + 2), 0, 255);
            destination.putShort((short) (lightU | lightV << 8));
            destination.putInt(sourceVertices.getInt(offset + colorOffset));
            destination.putShort(packUnorm16(sourceVertices.getFloat(offset + uvOffset)));
            destination.putShort(packUnorm16(sourceVertices.getFloat(offset + uvOffset + Float.BYTES)));
    }
    static void packVertices20(ByteBuffer destination,ByteBuffer source,int count,int stride,int position,int color,int uv,int light,int normal,byte[] materials){
        if(count<0||stride<1||position<0||(long)position+12>stride||color<0||(long)color+4>stride||uv<0||(long)uv+8>stride||light<0||(long)light+4>stride||normal<0||(long)normal+3>stride
                ||(long)count*stride>source.remaining()||(long)count*20>destination.remaining()||materials.length<((long)count+3)/4)throw new IllegalArgumentException("Invalid V2 vertex range");
        ByteBuffer input=source.duplicate().order(ByteOrder.nativeOrder());
        float minY=0;boolean waterTop=false;
        for(int i=0;i<count;i++){
            int offset=input.position()+i*stride;
            if(i%4==0){
                minY=Float.POSITIVE_INFINITY;
                if(materials[i/4]==5||materials[i/4]==8)for(int v=i;v<Math.min(i+4,count);v++)minY=Math.min(minY,input.getFloat(input.position()+v*stride+position+4));
                waterTop=false;
                if(materials[i/4]==5&&i+2<count){
                    int a=offset+position,b=a+stride,c=b+stride;
                    float ax=input.getFloat(b)-input.getFloat(a),ay=input.getFloat(b+4)-input.getFloat(a+4),az=input.getFloat(b+8)-input.getFloat(a+8);
                    float bx=input.getFloat(c)-input.getFloat(a),by=input.getFloat(c+4)-input.getFloat(a+4),bz=input.getFloat(c+8)-input.getFloat(a+8);
                    float nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx;
                    waterTop=ny>0&&ny*ny>(nx*nx+ny*ny+nz*nz)*.5f;
                }
            }
            writeVertex(destination,input,offset,position,color,uv,light);
            byte material=materials[i/4];
            boolean elevated=(material==5||material==8)&&input.getFloat(offset+position+4)>minY+.05f;
            if((material==8&&elevated)||(material==5&&(waterTop||elevated)))material|=(byte)128;
            destination.put(input.get(offset+normal)).put(input.get(offset+normal+1)).put(input.get(offset+normal+2)).put(material);
        }
    }

    static void packIndices(ByteBuffer destination, ByteBuffer source, int indexCount, int indexSize, int vertexBase) {
        long sourceBytes = (long) indexCount * indexSize;
        long destinationBytes = (long) indexCount * Integer.BYTES;
        if (indexCount < 0 || (indexSize != Short.BYTES && indexSize != Integer.BYTES)
                || sourceBytes > source.remaining() || destinationBytes > destination.remaining()) {
            throw new IllegalArgumentException("Terrain index pack would exceed a buffer's remaining range.");
        }

        ByteBuffer sourceIndices = source.duplicate().order(ByteOrder.nativeOrder());
        for (int index = 0; index < indexCount; index++) {
            int sourceIndex = sourceIndices.position() + index * indexSize;
            int value = indexSize == Short.BYTES
                    ? Short.toUnsignedInt(sourceIndices.getShort(sourceIndex))
                    : sourceIndices.getInt(sourceIndex);
            destination.putInt(value + vertexBase);
        }
    }

    private static short packPosition(float value) {
        int packed = Math.round((value - POSITION_ORIGIN) * POSITION_SCALE);
        return (short) Math.max(0, Math.min(0xFFFF, packed));
    }

    private static short packUnorm16(float value) {
        return (short) Math.round(Math.max(0.0f, Math.min(1.0f, value)) * 0xFFFF);
    }
}
