package com.redondoguibi.direct;

import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Only the GL/DX transfer; transparent geometry and material shading execute in D3D12. */
final class InteropSceneCapture implements AutoCloseable {
    private int fbo,program,vao,depthProgram;
    private int shader(int type,String source){int shader=GL20.glCreateShader(type);GL20.glShaderSource(shader,source);GL20.glCompileShader(shader);if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0){String error=GL20.glGetShaderInfoLog(shader);GL20.glDeleteShader(shader);throw new IllegalStateException(error);}return shader;}
    private void initialize(){
        if(program!=0)return;
        int vertex=shader(GL20.GL_VERTEX_SHADER,Dx12GlInterop.VERTEX_SHADER),fragment=0,candidate=0;
        try{
            fragment=shader(GL20.GL_FRAGMENT_SHADER,"#version 150\nuniform sampler2D SceneDepth;in vec2 texCoord;out float depth;void main(){depth=texture(SceneDepth,vec2(texCoord.x,1.0-texCoord.y)).r;}");
            candidate=GL20.glCreateProgram();GL20.glAttachShader(candidate,vertex);GL20.glAttachShader(candidate,fragment);GL20.glLinkProgram(candidate);
            if(GL20.glGetProgrami(candidate,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(candidate));
            fbo=GL30.glGenFramebuffers();vao=GL30.glGenVertexArrays();program=candidate;candidate=0;
        }finally{if(candidate!=0)GL20.glDeleteProgram(candidate);GL20.glDeleteShader(vertex);if(fragment!=0)GL20.glDeleteShader(fragment);}
    }
    private void attach(int color,int depth){GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT1,GL11.GL_TEXTURE_2D,depth,0);if(GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Shared scene FBO incomplete");}
    void capture(int sceneFbo,int sceneDepth,int sharedColor,int sharedDepth,int width,int height){
        int read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),draw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),oldProgram=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),oldVao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        boolean blend0=GL30.glIsEnabledi(GL11.GL_BLEND,0);
        int[] viewport=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
        int[] flags={GL11.GL_DEPTH_TEST,GL11.GL_CULL_FACE,GL11.GL_SCISSOR_TEST,GL11.GL_STENCIL_TEST,GL30.GL_FRAMEBUFFER_SRGB};boolean[] enabled=new boolean[flags.length];for(int i=0;i<flags.length;i++)enabled[i]=GL11.glIsEnabled(flags[i]);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);int texture=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),sampler=GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        try(MemoryStack stack=MemoryStack.stackPush()){
            var mask=stack.malloc(4);GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK,mask);
            try{
                initialize();initializeDepth();for(int flag:flags)GL11.glDisable(flag);GL30.glDisablei(GL11.GL_BLEND,0);GL30.glColorMaski(0,true,true,true,true);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,sceneFbo);GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,fbo);attach(sharedColor,sharedDepth);GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
                GL30.glBlitFramebuffer(0,height,width,0,0,0,width,height,GL11.GL_COLOR_BUFFER_BIT,GL11.GL_NEAREST);
                GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT1);GL11.glViewport(0,0,width,height);GL20.glUseProgram(program);GL30.glBindVertexArray(vao);GL33.glBindSampler(0,0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,sceneDepth);GL20.glUniform1i(GL20.glGetUniformLocation(program,"SceneDepth"),0);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
            }finally{GL30.glColorMaski(0,mask.get(0)!=0,mask.get(1)!=0,mask.get(2)!=0,mask.get(3)!=0);}
        }finally{
            GL20.glUseProgram(oldProgram);GL30.glBindVertexArray(oldVao);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL33.glBindSampler(0,sampler);GL13.glActiveTexture(active);GL11.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);for(int i=0;i<flags.length;i++){if(enabled[i])GL11.glEnable(flags[i]);else GL11.glDisable(flags[i]);}if(blend0)GL30.glEnablei(GL11.GL_BLEND,0);else GL30.glDisablei(GL11.GL_BLEND,0);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,draw);
        }
    }
    void present(int sceneFbo,int sharedColor,int sharedDepth,int width,int height){
        int read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),draw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);boolean scissor=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        try{initialize();initializeDepth();GL11.glDisable(GL11.GL_SCISSOR_TEST);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,fbo);GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,sharedColor,0);GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,sceneFbo);GL30.glBlitFramebuffer(0,height,width,0,0,0,width,height,GL11.GL_COLOR_BUFFER_BIT,GL11.GL_NEAREST);writeDepth(sharedDepth,width,height);}
        finally{GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,draw);if(scissor)GL11.glEnable(GL11.GL_SCISSOR_TEST);}
    }
    private void initializeDepth(){
        if(depthProgram==0){int v=shader(GL20.GL_VERTEX_SHADER,Dx12GlInterop.VERTEX_SHADER),f=0,p=0;try{f=shader(GL20.GL_FRAGMENT_SHADER,"#version 150\nuniform sampler2D SceneDepth;in vec2 texCoord;out vec4 color;void main(){gl_FragDepth=texture(SceneDepth,vec2(texCoord.x,1.0-texCoord.y)).r;color=vec4(0);}");p=GL20.glCreateProgram();GL20.glAttachShader(p,v);GL20.glAttachShader(p,f);GL20.glLinkProgram(p);if(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(p));depthProgram=p;p=0;}finally{GL20.glDeleteShader(v);if(f!=0)GL20.glDeleteShader(f);if(p!=0)GL20.glDeleteProgram(p);}}
    }
    private void writeDepth(int depth,int width,int height){
        int previousProgram=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),previousVao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),depthFunc=GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean blend0=GL30.glIsEnabledi(GL11.GL_BLEND,0);
        int[] viewport=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);int[] flags={GL11.GL_DEPTH_TEST,GL11.GL_CULL_FACE,GL11.GL_STENCIL_TEST};boolean[] enabled=new boolean[flags.length];for(int i=0;i<flags.length;i++)enabled[i]=GL11.glIsEnabled(flags[i]);boolean depthMask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);int texture=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),sampler=GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        try(MemoryStack stack=MemoryStack.stackPush()){
            var mask=stack.malloc(4);GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK,mask);
            try{
                initializeDepth();
                for(int flag:flags)GL11.glDisable(flag);GL30.glDisablei(GL11.GL_BLEND,0);GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_ALWAYS);GL11.glDepthMask(true);GL30.glColorMaski(0,false,false,false,false);GL11.glViewport(0,0,width,height);GL20.glUseProgram(depthProgram);GL30.glBindVertexArray(vao);GL11.glBindTexture(GL11.GL_TEXTURE_2D,depth);GL33.glBindSampler(0,0);GL20.glUniform1i(GL20.glGetUniformLocation(depthProgram,"SceneDepth"),0);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
            }finally{GL30.glColorMaski(0,mask.get(0)!=0,mask.get(1)!=0,mask.get(2)!=0,mask.get(3)!=0);}
        }finally{GL20.glUseProgram(previousProgram);GL30.glBindVertexArray(previousVao);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL33.glBindSampler(0,sampler);GL13.glActiveTexture(active);GL11.glDepthFunc(depthFunc);GL11.glDepthMask(depthMask);GL11.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);for(int i=0;i<flags.length;i++){if(enabled[i])GL11.glEnable(flags[i]);else GL11.glDisable(flags[i]);}if(blend0)GL30.glEnablei(GL11.GL_BLEND,0);else GL30.glDisablei(GL11.GL_BLEND,0);}
    }
    public void close(){if(program!=0)GL20.glDeleteProgram(program);if(depthProgram!=0)GL20.glDeleteProgram(depthProgram);if(fbo!=0)GL30.glDeleteFramebuffers(fbo);if(vao!=0)GL30.glDeleteVertexArrays(vao);program=fbo=vao=depthProgram=0;}
}
