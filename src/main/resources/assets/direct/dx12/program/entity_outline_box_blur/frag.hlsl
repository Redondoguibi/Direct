Texture2D<float4> DiffuseSampler : register(t0);
SamplerState _DiffuseSampler_sampler : register(s0);

static float2 texCoord;
static float2 sampleStep;
static float4 fragColor;

struct SPIRV_Cross_Input
{
    float2 texCoord : TEXCOORD0;
    float2 sampleStep : TEXCOORD1;
};

struct SPIRV_Cross_Output
{
    float4 fragColor : SV_Target0;
};

void frag_main()
{
    float4 _92;
    _92 = 0.0f.xxxx;
    for (float _80 = -1.5f; _80 <= 2.0f; )
    {
        _92 += DiffuseSampler.Sample(_DiffuseSampler_sampler, texCoord + (sampleStep * _80));
        _80 += 2.0f;
        continue;
    }
    float4 _58 = _92 + (DiffuseSampler.Sample(_DiffuseSampler_sampler, texCoord + (sampleStep * 2.0f)) * 0.5f.xxxx);
    fragColor = float4((_58 * 0.4000000059604644775390625f.xxxx).xyz, _58.w);
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    texCoord = stage_input.texCoord;
    sampleStep = stage_input.sampleStep;
    frag_main();
    SPIRV_Cross_Output stage_output;
    stage_output.fragColor = fragColor;
    return stage_output;
}
