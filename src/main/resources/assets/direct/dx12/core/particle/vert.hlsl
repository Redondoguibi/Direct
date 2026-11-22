cbuffer MinecraftUniforms : register(b0)
{
    row_major float4x4 _51_ModelViewMat : packoffset(c0);
    row_major float4x4 _51_ProjMat : packoffset(c4);
    int _51_FogShape : packoffset(c11);
};

Texture2D<float4> Sampler2 : register(t1);
SamplerState _Sampler2_sampler : register(s1);

static float4 gl_Position;
static float3 Position;
static float vertexDistance;
static float2 texCoord0;
static float2 UV0;
static float4 vertexColor;
static float4 Color;
static int2 UV2;

struct SPIRV_Cross_Input
{
    float3 Position : TEXCOORD0;
    float2 UV0 : TEXCOORD1;
    float4 Color : TEXCOORD2;
    int2 UV2 : TEXCOORD3;
};

struct SPIRV_Cross_Output
{
    float vertexDistance : TEXCOORD0;
    float2 texCoord0 : TEXCOORD1;
    float4 vertexColor : TEXCOORD2;
    float4 gl_Position : SV_Position;
};

void vert_main()
{
    gl_Position = mul(float4(Position, 1.0f), mul(_51_ModelViewMat, _51_ProjMat));
    float _138;
    do
    {
        if (_51_FogShape == 0)
        {
            _138 = length(Position);
            break;
        }
        else
        {
            _138 = max(length(Position.xz), abs(Position.y));
            break;
        }
        break; // unreachable workaround
    } while(false);
    vertexDistance = _138;
    texCoord0 = UV0;
    vertexColor = Color * Sampler2.Load(int3(UV2 / int2(16, 16), 0));
    gl_Position.y = -gl_Position.y;
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    Position = stage_input.Position;
    UV0 = stage_input.UV0;
    Color = stage_input.Color;
    UV2 = stage_input.UV2;
    vert_main();
    SPIRV_Cross_Output stage_output;
    stage_output.gl_Position = gl_Position;
    stage_output.vertexDistance = vertexDistance;
    stage_output.texCoord0 = texCoord0;
    stage_output.vertexColor = vertexColor;
    return stage_output;
}
