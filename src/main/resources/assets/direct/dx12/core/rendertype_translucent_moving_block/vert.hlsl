cbuffer MinecraftUniforms : register(b0)
{
    row_major float4x4 _19_ModelViewMat : packoffset(c0);
    row_major float4x4 _19_ProjMat : packoffset(c4);
};

Texture2D<float4> Sampler2 : register(t1);
SamplerState _Sampler2_sampler : register(s1);

static float4 gl_Position;
static float3 Position;
static float4 vertexColor;
static float4 Color;
static int2 UV2;
static float2 texCoord0;
static float2 UV0;

struct SPIRV_Cross_Input
{
    float3 Position : TEXCOORD0;
    float4 Color : TEXCOORD1;
    float2 UV0 : TEXCOORD2;
    int2 UV2 : TEXCOORD3;
};

struct SPIRV_Cross_Output
{
    float4 vertexColor : TEXCOORD0;
    float2 texCoord0 : TEXCOORD1;
    float4 gl_Position : SV_Position;
};

void vert_main()
{
    gl_Position = mul(float4(Position, 1.0f), mul(_19_ModelViewMat, _19_ProjMat));
    vertexColor = Color * Sampler2.Load(int3(UV2 / int2(16, 16), 0));
    texCoord0 = UV0;
    gl_Position.y = -gl_Position.y;
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    Position = stage_input.Position;
    Color = stage_input.Color;
    UV2 = stage_input.UV2;
    UV0 = stage_input.UV0;
    vert_main();
    SPIRV_Cross_Output stage_output;
    stage_output.gl_Position = gl_Position;
    stage_output.vertexColor = vertexColor;
    stage_output.texCoord0 = texCoord0;
    return stage_output;
}
