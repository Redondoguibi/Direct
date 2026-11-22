cbuffer MinecraftUniforms : register(b0)
{
    float4 _13_ColorModulator : packoffset(c8);
};


static float4 fragColor;

struct SPIRV_Cross_Output
{
    float4 fragColor : SV_Target0;
};

void frag_main()
{
    fragColor = _13_ColorModulator;
}

SPIRV_Cross_Output main()
{
    frag_main();
    SPIRV_Cross_Output stage_output;
    stage_output.fragColor = fragColor;
    return stage_output;
}
