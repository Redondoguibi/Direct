cbuffer MinecraftUniforms : register(b0)
{
    row_major float4x4 _51_ModelViewMat : packoffset(c0);
    row_major float4x4 _51_ProjMat : packoffset(c4);
    int _51_FogShape : packoffset(c9.z);
};


static float4 gl_Position;
static float3 Position;
static float vertexDistance;
static float4 vertexColor;
static float4 Color;

struct SPIRV_Cross_Input
{
    float3 Position : TEXCOORD0;
    float4 Color : TEXCOORD1;
};

struct SPIRV_Cross_Output
{
    float vertexDistance : TEXCOORD0;
    float4 vertexColor : TEXCOORD1;
    float4 gl_Position : SV_Position;
};

void vert_main()
{
    gl_Position = mul(float4(Position, 1.0f), mul(_51_ModelViewMat, _51_ProjMat));
    float _118;
    do
    {
        if (_51_FogShape == 0)
        {
            _118 = length(Position);
            break;
        }
        else
        {
            _118 = max(length(Position.xz), abs(Position.y));
            break;
        }
        break; // unreachable workaround
    } while(false);
    vertexDistance = _118;
    vertexColor = Color;
    gl_Position.y = -gl_Position.y;
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    Position = stage_input.Position;
    Color = stage_input.Color;
    vert_main();
    SPIRV_Cross_Output stage_output;
    stage_output.gl_Position = gl_Position;
    stage_output.vertexDistance = vertexDistance;
    stage_output.vertexColor = vertexColor;
    return stage_output;
}
