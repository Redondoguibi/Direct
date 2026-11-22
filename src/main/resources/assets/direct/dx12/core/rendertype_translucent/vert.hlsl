cbuffer MinecraftUniforms : register(b0)
{
    row_major float4x4 _76_ModelViewMat : packoffset(c0);
    row_major float4x4 _76_ProjMat : packoffset(c4);
    float3 _76_ChunkOffset : packoffset(c8);
    int _76_FogShape : packoffset(c12);
};

Texture2D<float4> Sampler2 : register(t1);
SamplerState _Sampler2_sampler : register(s1);

static float4 gl_Position;
static float3 Position;
static float vertexDistance;
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
    float vertexDistance : TEXCOORD0;
    float4 vertexColor : TEXCOORD1;
    float2 texCoord0 : TEXCOORD2;
    float4 gl_Position : SV_Position;
};

void vert_main()
{
    float3 _81 = Position + _76_ChunkOffset;
    gl_Position = mul(float4(_81, 1.0f), mul(_76_ModelViewMat, _76_ProjMat));
    float _173;
    do
    {
        if (_76_FogShape == 0)
        {
            _173 = length(_81);
            break;
        }
        else
        {
            _173 = max(length(_81.xz), abs(_81.y));
            break;
        }
        break; // unreachable workaround
    } while(false);
    vertexDistance = _173;
    vertexColor = Color * Sampler2.SampleLevel(_Sampler2_sampler, clamp(float2(UV2) * 0.00390625f.xx, 0.03125f.xx, 0.96875f.xx), 0.0f);
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
    stage_output.vertexDistance = vertexDistance;
    stage_output.vertexColor = vertexColor;
    stage_output.texCoord0 = texCoord0;
    return stage_output;
}
