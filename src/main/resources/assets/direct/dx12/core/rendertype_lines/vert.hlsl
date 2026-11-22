cbuffer MinecraftUniforms : register(b0)
{
    row_major float4x4 _49_ModelViewMat : packoffset(c0);
    row_major float4x4 _49_ProjMat : packoffset(c4);
    float _49_LineWidth : packoffset(c9);
    float2 _49_ScreenSize : packoffset(c9.z);
    int _49_FogShape : packoffset(c12);
};


static float4 gl_Position;
static int gl_VertexIndex;
static float3 Position;
static float3 Normal;
static float vertexDistance;
static float4 vertexColor;
static float4 Color;

struct SPIRV_Cross_Input
{
    float3 Position : TEXCOORD0;
    float4 Color : TEXCOORD1;
    float3 Normal : TEXCOORD2;
    uint gl_VertexIndex : SV_VertexID;
};

struct SPIRV_Cross_Output
{
    float vertexDistance : TEXCOORD0;
    float4 vertexColor : TEXCOORD1;
    float4 gl_Position : SV_Position;
};

void vert_main()
{
    float4x4 _65 = mul(_49_ModelViewMat, mul(float4x4(float4(0.99609375f, 0.0f, 0.0f, 0.0f), float4(0.0f, 0.99609375f, 0.0f, 0.0f), float4(0.0f, 0.0f, 0.99609375f, 0.0f), float4(0.0f, 0.0f, 0.0f, 1.0f)), _49_ProjMat));
    float4 _73 = mul(float4(Position, 1.0f), _65);
    float4 _89 = mul(float4(Position + Normal, 1.0f), _65);
    float _95 = _73.w;
    float3 _97 = _73.xyz / _95.xxx;
    float2 _117 = normalize(((_89.xyz / _89.w.xxx).xy - _97.xy) * _49_ScreenSize);
    float2 _133 = (float2(-_117.y, _117.x) * _49_LineWidth) / _49_ScreenSize;
    float2 _251;
    if (_133.x < 0.0f)
    {
        _251 = _133 * (-1.0f);
    }
    else
    {
        _251 = _133;
    }
    if ((gl_VertexIndex % 2) == 0)
    {
        gl_Position = float4((_97 + float3(_251, 0.0f)) * _95, _95);
    }
    else
    {
        gl_Position = float4((_97 - float3(_251, 0.0f)) * _95, _95);
    }
    float _236;
    do
    {
        if (_49_FogShape == 0)
        {
            _236 = length(Position);
            break;
        }
        else
        {
            _236 = max(length(Position.xz), abs(Position.y));
            break;
        }
        break; // unreachable workaround
    } while(false);
    vertexDistance = _236;
    vertexColor = Color;
    gl_Position.y = -gl_Position.y;
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    gl_VertexIndex = int(stage_input.gl_VertexIndex);
    Position = stage_input.Position;
    Normal = stage_input.Normal;
    Color = stage_input.Color;
    vert_main();
    SPIRV_Cross_Output stage_output;
    stage_output.gl_Position = gl_Position;
    stage_output.vertexDistance = vertexDistance;
    stage_output.vertexColor = vertexColor;
    return stage_output;
}
