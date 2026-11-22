cbuffer MinecraftUniforms : register(b0)
{
    float4 _37_ColorModulator : packoffset(c8);
};

Texture2D<float4> Sampler0 : register(t0);
SamplerState _Sampler0_sampler : register(s0);

static float2 texCoord0;
static float4 fragColor;

struct SPIRV_Cross_Input
{
    float2 texCoord0 : TEXCOORD0;
};

struct SPIRV_Cross_Output
{
    float4 fragColor : SV_Target0;
};

void frag_main()
{
    float4 _19 = Sampler0.Sample(_Sampler0_sampler, texCoord0);
    if (_19.w == 0.0f)
    {
        discard;
    }
    fragColor = _19 * _37_ColorModulator;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    texCoord0 = stage_input.texCoord0;
    frag_main();
    SPIRV_Cross_Output stage_output;
    stage_output.fragColor = fragColor;
    return stage_output;
}
