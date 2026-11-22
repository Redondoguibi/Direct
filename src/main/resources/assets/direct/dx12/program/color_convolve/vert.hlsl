cbuffer MinecraftUniforms : register(b0)
{
    row_major float4x4 _15_ProjMat : packoffset(c0);
    float2 _15_InSize : packoffset(c4);
    float2 _15_OutSize : packoffset(c4.z);
};


static float4 gl_Position;
static float4 Position;
static float2 oneTexel;
static float2 texCoord;

struct SPIRV_Cross_Input
{
    float4 Position : TEXCOORD0;
};

struct SPIRV_Cross_Output
{
    float2 texCoord : TEXCOORD0;
    float2 oneTexel : TEXCOORD1;
    float4 gl_Position : SV_Position;
};

void vert_main()
{
    gl_Position = float4(mul(float4(Position.xy, 0.0f, 1.0f), _15_ProjMat).xy, 0.20000000298023223876953125f, 1.0f);
    oneTexel = 1.0f.xx / _15_InSize;
    texCoord = Position.xy / _15_OutSize;
    gl_Position.y = -gl_Position.y;
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    Position = stage_input.Position;
    vert_main();
    SPIRV_Cross_Output stage_output;
    stage_output.gl_Position = gl_Position;
    stage_output.oneTexel = oneTexel;
    stage_output.texCoord = texCoord;
    return stage_output;
}
