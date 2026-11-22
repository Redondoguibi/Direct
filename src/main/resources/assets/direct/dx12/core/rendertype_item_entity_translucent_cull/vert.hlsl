cbuffer MinecraftUniforms : register(b0)
{
    row_major float4x4 _93_ModelViewMat : packoffset(c0);
    row_major float4x4 _93_ProjMat : packoffset(c4);
    float3 _93_Light0_Direction : packoffset(c9);
    float3 _93_Light1_Direction : packoffset(c10);
    int _93_FogShape : packoffset(c13);
};

Texture2D<float4> Sampler2 : register(t1);
SamplerState _Sampler2_sampler : register(s1);

static float4 gl_Position;
static float3 Position;
static float vertexDistance;
static float4 vertexColor;
static float3 Normal;
static float4 Color;
static int2 UV2;
static float2 texCoord0;
static float2 UV0;
static float2 texCoord1;
static float2 UV1;
static float2 texCoord2;

struct SPIRV_Cross_Input
{
    float3 Position : TEXCOORD0;
    float4 Color : TEXCOORD1;
    float2 UV0 : TEXCOORD2;
    float2 UV1 : TEXCOORD3;
    int2 UV2 : TEXCOORD4;
    float3 Normal : TEXCOORD5;
};

struct SPIRV_Cross_Output
{
    float vertexDistance : TEXCOORD0;
    float4 vertexColor : TEXCOORD1;
    float2 texCoord0 : TEXCOORD2;
    float2 texCoord1 : TEXCOORD3;
    float2 texCoord2 : TEXCOORD4;
    float4 gl_Position : SV_Position;
};

void vert_main()
{
    gl_Position = mul(float4(Position, 1.0f), mul(_93_ModelViewMat, _93_ProjMat));
    float _228;
    do
    {
        if (_93_FogShape == 0)
        {
            _228 = length(Position);
            break;
        }
        else
        {
            _228 = max(length(Position.xz), abs(Position.y));
            break;
        }
        break; // unreachable workaround
    } while(false);
    vertexDistance = _228;
    vertexColor = float4(Color.xyz * min(1.0f, ((max(0.0f, dot(_93_Light0_Direction, Normal)) + max(0.0f, dot(_93_Light1_Direction, Normal))) * 0.60000002384185791015625f) + 0.4000000059604644775390625f), Color.w) * Sampler2.Load(int3(UV2 / int2(16, 16), 0));
    texCoord0 = UV0;
    texCoord1 = UV1;
    texCoord2 = float2(UV2);
    gl_Position.y = -gl_Position.y;
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    Position = stage_input.Position;
    Normal = stage_input.Normal;
    Color = stage_input.Color;
    UV2 = stage_input.UV2;
    UV0 = stage_input.UV0;
    UV1 = stage_input.UV1;
    vert_main();
    SPIRV_Cross_Output stage_output;
    stage_output.gl_Position = gl_Position;
    stage_output.vertexDistance = vertexDistance;
    stage_output.vertexColor = vertexColor;
    stage_output.texCoord0 = texCoord0;
    stage_output.texCoord1 = texCoord1;
    stage_output.texCoord2 = texCoord2;
    return stage_output;
}
