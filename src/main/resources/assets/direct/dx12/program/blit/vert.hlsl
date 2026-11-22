cbuffer MinecraftUniforms : register(b0)
{
    row_major float4x4 _14_ProjMat : packoffset(c0);
    float2 _14_OutSize : packoffset(c4);
};


static float4 gl_Position;
static float4 Position;
static float2 texCoord;

struct SPIRV_Cross_Input
{
    float4 Position : TEXCOORD0;
};

struct SPIRV_Cross_Output
{
    float2 texCoord : TEXCOORD0;
    float4 gl_Position : SV_Position;
};

void vert_main()
{
    gl_Position = float4(mul(float4(Position.xy, 0.0f, 1.0f), _14_ProjMat).xy, 0.20000000298023223876953125f, 1.0f);
    texCoord = Position.xy / _14_OutSize;
    gl_Position.y = -gl_Position.y;
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    Position = stage_input.Position;
    vert_main();
    SPIRV_Cross_Output stage_output;
    stage_output.gl_Position = gl_Position;
    stage_output.texCoord = texCoord;
    return stage_output;
}
