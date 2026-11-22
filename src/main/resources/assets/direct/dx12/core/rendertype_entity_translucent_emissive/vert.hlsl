cbuffer MinecraftUniforms : register(b0)
{
    row_major float4x4 _64_ModelViewMat : packoffset(c0);
    row_major float4x4 _64_ProjMat : packoffset(c4);
    float3 _64_Light0_Direction : packoffset(c9);
    float3 _64_Light1_Direction : packoffset(c10);
};

Texture2D<float4> Sampler1 : register(t1);
SamplerState _Sampler1_sampler : register(s1);

static float4 gl_Position;
static float3 Position;
static float vertexDistance;
static float4 vertexColor;
static float3 Normal;
static float4 Color;
static float4 overlayColor;
static int2 UV1;
static float2 texCoord0;
static float2 UV0;

struct SPIRV_Cross_Input
{
    float3 Position : TEXCOORD0;
    float4 Color : TEXCOORD1;
    float2 UV0 : TEXCOORD2;
    int2 UV1 : TEXCOORD3;
    float3 Normal : TEXCOORD5;
};

struct SPIRV_Cross_Output
{
    float vertexDistance : TEXCOORD0;
    float4 vertexColor : TEXCOORD1;
    float4 overlayColor : TEXCOORD2;
    float2 texCoord0 : TEXCOORD3;
    float4 gl_Position : SV_Position;
};

void vert_main()
{
    float4 _78 = float4(Position, 1.0f);
    gl_Position = mul(_78, mul(_64_ModelViewMat, _64_ProjMat));
    vertexDistance = length(mul(_78, _64_ModelViewMat).xyz);
    vertexColor = float4(Color.xyz * min(1.0f, ((max(0.0f, dot(_64_Light0_Direction, Normal)) + max(0.0f, dot(_64_Light1_Direction, Normal))) * 0.60000002384185791015625f) + 0.4000000059604644775390625f), Color.w);
    overlayColor = Sampler1.Load(int3(UV1, 0));
    texCoord0 = UV0;
    gl_Position.y = -gl_Position.y;
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    Position = stage_input.Position;
    Normal = stage_input.Normal;
    Color = stage_input.Color;
    UV1 = stage_input.UV1;
    UV0 = stage_input.UV0;
    vert_main();
    SPIRV_Cross_Output stage_output;
    stage_output.gl_Position = gl_Position;
    stage_output.vertexDistance = vertexDistance;
    stage_output.vertexColor = vertexColor;
    stage_output.overlayColor = overlayColor;
    stage_output.texCoord0 = texCoord0;
    return stage_output;
}
