cbuffer MinecraftUniforms : register(b0)
{
    row_major float4x4 _51_ModelViewMat : packoffset(c0);
    row_major float4x4 _51_ProjMat : packoffset(c4);
    int _51_FogShape : packoffset(c11);
};


static float4 gl_Position;
static float3 Position;
static float vertexDistance;

struct SPIRV_Cross_Input
{
    float3 Position : TEXCOORD0;
};

struct SPIRV_Cross_Output
{
    float vertexDistance : TEXCOORD0;
    float4 gl_Position : SV_Position;
};

void vert_main()
{
    gl_Position = mul(float4(Position, 1.0f), mul(_51_ModelViewMat, _51_ProjMat));
    float _114;
    do
    {
        if (_51_FogShape == 0)
        {
            _114 = length(Position);
            break;
        }
        else
        {
            _114 = max(length(Position.xz), abs(Position.y));
            break;
        }
        break; // unreachable workaround
    } while(false);
    vertexDistance = _114;
    gl_Position.y = -gl_Position.y;
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    Position = stage_input.Position;
    vert_main();
    SPIRV_Cross_Output stage_output;
    stage_output.gl_Position = gl_Position;
    stage_output.vertexDistance = vertexDistance;
    return stage_output;
}
