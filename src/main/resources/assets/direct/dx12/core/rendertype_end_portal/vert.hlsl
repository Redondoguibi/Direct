cbuffer MinecraftUniforms : register(b0)
{
    row_major float4x4 _57_ModelViewMat : packoffset(c0);
    row_major float4x4 _57_ProjMat : packoffset(c4);
};


static float4 gl_Position;
static float3 Position;
static float4 texProj0;

struct SPIRV_Cross_Input
{
    float3 Position : TEXCOORD0;
};

struct SPIRV_Cross_Output
{
    float4 texProj0 : TEXCOORD0;
    float4 gl_Position : SV_Position;
};

void vert_main()
{
    gl_Position = mul(float4(Position, 1.0f), mul(_57_ModelViewMat, _57_ProjMat));
    float4 _86 = gl_Position * 0.5f;
    float _90 = _86.w;
    texProj0 = float4(_86.x + _90, _86.y + _90, gl_Position.zw);
    gl_Position.y = -gl_Position.y;
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    Position = stage_input.Position;
    vert_main();
    SPIRV_Cross_Output stage_output;
    stage_output.gl_Position = gl_Position;
    stage_output.texProj0 = texProj0;
    return stage_output;
}
