cbuffer MinecraftUniforms : register(b0)
{
    row_major float4x4 _93_ModelViewMat : packoffset(c0);
    row_major float4x4 _93_ProjMat : packoffset(c4);
    float3 _93_Light0_Direction : packoffset(c9);
    float3 _93_Light1_Direction : packoffset(c10);
    int _93_FogShape : packoffset(c13);
};

Texture2D<float4> Sampler2 : register(t2);
SamplerState _Sampler2_sampler : register(s2);
Texture2D<float4> Sampler1 : register(t1);
SamplerState _Sampler1_sampler : register(s1);

static float4 gl_Position;
static float3 Position;
static float vertexDistance;
static float4 vertexColor;
static float3 Normal;
static float4 Color;
static float4 lightMapColor;
static int2 UV2;
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
    int2 UV2 : TEXCOORD4;
    float3 Normal : TEXCOORD5;
};

struct SPIRV_Cross_Output
{
    float vertexDistance : TEXCOORD0;
    float4 vertexColor : TEXCOORD1;
    float4 lightMapColor : TEXCOORD2;
    float4 overlayColor : TEXCOORD3;
    float2 texCoord0 : TEXCOORD4;
    float4 gl_Position : SV_Position;
};

void vert_main()
{
    gl_Position = mul(float4(Position, 1.0f), mul(_93_ModelViewMat, _93_ProjMat));
    float _229;
    do
    {
        if (_93_FogShape == 0)
        {
            _229 = length(Position);
            break;
        }
        else
        {
            _229 = max(length(Position.xz), abs(Position.y));
            break;
        }
        break; // unreachable workaround
    } while(false);
    vertexDistance = _229;
    vertexColor = float4(Color.xyz * min(1.0f, ((max(0.0f, dot(_93_Light0_Direction, Normal)) + max(0.0f, dot(_93_Light1_Direction, Normal))) * 0.60000002384185791015625f) + 0.4000000059604644775390625f), Color.w);
    lightMapColor = Sampler2.Load(int3(UV2 / int2(16, 16), 0));
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
    UV2 = stage_input.UV2;
    UV1 = stage_input.UV1;
    UV0 = stage_input.UV0;
    vert_main();
    SPIRV_Cross_Output stage_output;
    stage_output.gl_Position = gl_Position;
    stage_output.vertexDistance = vertexDistance;
    stage_output.vertexColor = vertexColor;
    stage_output.lightMapColor = lightMapColor;
    stage_output.overlayColor = overlayColor;
    stage_output.texCoord0 = texCoord0;
    return stage_output;
}
