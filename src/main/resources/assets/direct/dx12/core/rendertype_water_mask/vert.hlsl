cbuffer MinecraftUniforms : register(b0)
{
    row_major float4x4 _19_ModelViewMat : packoffset(c0);
    row_major float4x4 _19_ProjMat : packoffset(c4);
};


static float4 gl_Position;
static float3 Position;

struct SPIRV_Cross_Input
{
    float3 Position : TEXCOORD0;
};

struct SPIRV_Cross_Output
{
    float4 gl_Position : SV_Position;
};

void vert_main()
{
    gl_Position = mul(float4(Position, 1.0f), mul(_19_ModelViewMat, _19_ProjMat));
    gl_Position.y = -gl_Position.y;
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    Position = stage_input.Position;
    vert_main();
    SPIRV_Cross_Output stage_output;
    stage_output.gl_Position = gl_Position;
    return stage_output;
}
