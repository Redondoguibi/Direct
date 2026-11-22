cbuffer MinecraftUniforms : register(b0)
{
    row_major float4x4 _14_ProjMat : packoffset(c0);
    float2 _14_InScale : packoffset(c4.z);
    float2 _14_InOffset : packoffset(c5);
    float _14_InRotation : packoffset(c5.z);
    float2 _14_OutSize : packoffset(c8);
};


static float4 gl_Position;
static float4 Position;
static float2 texCoord;
static float2 scaledCoord;

struct SPIRV_Cross_Input
{
    float4 Position : TEXCOORD0;
};

struct SPIRV_Cross_Output
{
    float2 texCoord : TEXCOORD0;
    float2 scaledCoord : TEXCOORD1;
    float4 gl_Position : SV_Position;
};

void vert_main()
{
    gl_Position = float4(mul(float4(Position.xy, 0.0f, 1.0f), _14_ProjMat).xy, 0.20000000298023223876953125f, 1.0f);
    texCoord = Position.xy / _14_OutSize;
    float _62 = _14_InRotation * 0.01745329238474369049072265625f;
    float _65 = cos(_62);
    float _68 = sin(_62);
    scaledCoord = (float2((texCoord.x * _65) - (texCoord.y * _68), (texCoord.y * _65) + (texCoord.x * _68)) * _14_InScale) + _14_InOffset;
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
    stage_output.scaledCoord = scaledCoord;
    return stage_output;
}
