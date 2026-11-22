cbuffer MinecraftUniforms : register(b0)
{
    row_major float4x4 _51_ModelViewMat : packoffset(c0);
    row_major float4x4 _51_ProjMat : packoffset(c4);
    row_major float4x4 _51_TextureMat : packoffset(c8);
    int _51_FogShape : packoffset(c13.z);
};


static float4 gl_Position;
static float3 Position;
static float vertexDistance;
static float4 vertexColor;
static float4 Color;
static float2 texCoord0;
static float2 UV0;

struct SPIRV_Cross_Input
{
    float3 Position : TEXCOORD0;
    float4 Color : TEXCOORD1;
    float2 UV0 : TEXCOORD2;
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
    gl_Position = mul(float4(Position, 1.0f), mul(_51_ModelViewMat, _51_ProjMat));
    float _132;
    do
    {
        if (_51_FogShape == 0)
        {
            _132 = length(Position);
            break;
        }
        else
        {
            _132 = max(length(Position.xz), abs(Position.y));
            break;
        }
        break; // unreachable workaround
    } while(false);
    vertexDistance = _132;
    vertexColor = Color;
    texCoord0 = mul(float4(UV0, 0.0f, 1.0f), _51_TextureMat).xy;
    gl_Position.y = -gl_Position.y;
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    Position = stage_input.Position;
    Color = stage_input.Color;
    UV0 = stage_input.UV0;
    vert_main();
    SPIRV_Cross_Output stage_output;
    stage_output.gl_Position = gl_Position;
    stage_output.vertexDistance = vertexDistance;
    stage_output.vertexColor = vertexColor;
    stage_output.texCoord0 = texCoord0;
    return stage_output;
}
