cbuffer MinecraftUniforms : register(b0)
{
    float4 _30_ColorModulator : packoffset(c8);
};


static float4 vertexColor;
static float4 fragColor;

struct SPIRV_Cross_Input
{
    float4 vertexColor : TEXCOORD0;
};

struct SPIRV_Cross_Output
{
    float4 fragColor : SV_Target0;
};

void frag_main()
{
    if (vertexColor.w == 0.0f)
    {
        discard;
    }
    fragColor = vertexColor * _30_ColorModulator;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    vertexColor = stage_input.vertexColor;
    frag_main();
    SPIRV_Cross_Output stage_output;
    stage_output.fragColor = fragColor;
    return stage_output;
}
