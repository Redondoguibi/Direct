cbuffer MinecraftUniforms : register(b0)
{
    float _18_Radius : packoffset(c5.z);
    float _18_RadiusMultiplier : packoffset(c5.w);
};

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
    float _28 = round(_18_Radius * _18_RadiusMultiplier);
    float _33 = 0.5f - _28;
    float4 _82;
    _82 = 0.0f.xxxx;
    for (float _80 = _33; _80 <= _28; )
    {
        _82 += DiffuseSampler.Sample(_DiffuseSampler_sampler, texCoord + (sampleStep * _80));
        _80 += 2.0f;
        continue;
    }
    fragColor = (_82 + (DiffuseSampler.Sample(_DiffuseSampler_sampler, texCoord + (sampleStep * _28)) * 0.5f.xxxx)) / (_28 + 0.5f).xxxx;
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
