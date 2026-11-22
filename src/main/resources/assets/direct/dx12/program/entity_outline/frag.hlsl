Texture2D<float4> DiffuseSampler : register(t0);
SamplerState _DiffuseSampler_sampler : register(s0);

static float2 texCoord;
static float2 oneTexel;
static float4 fragColor;

struct SPIRV_Cross_Input
{
    float2 texCoord : TEXCOORD0;
    float2 oneTexel : TEXCOORD1;
};

struct SPIRV_Cross_Output
{
    float4 fragColor : SV_Target0;
};

void frag_main()
{
    float4 _19 = DiffuseSampler.Sample(_DiffuseSampler_sampler, texCoord);
    float2 _30 = float2(oneTexel.x, 0.0f);
    float4 _32 = DiffuseSampler.Sample(_DiffuseSampler_sampler, texCoord - _30);
    float4 _40 = DiffuseSampler.Sample(_DiffuseSampler_sampler, texCoord + _30);
    float2 _47 = float2(0.0f, oneTexel.y);
    float4 _49 = DiffuseSampler.Sample(_DiffuseSampler_sampler, texCoord - _47);
    float4 _57 = DiffuseSampler.Sample(_DiffuseSampler_sampler, texCoord + _47);
    float _62 = _19.w;
    float _64 = _32.w;
    float _71 = _40.w;
    float _78 = _49.w;
    float _85 = _57.w;
    fragColor = float4((((((_19.xyz * _62) + (_32.xyz * _64)) + (_40.xyz * _71)) + (_49.xyz * _78)) + (_57.xyz * _85)) * 0.20000000298023223876953125f, clamp(((abs(_62 - _64) + abs(_62 - _71)) + abs(_62 - _78)) + abs(_62 - _85), 0.0f, 1.0f));
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    texCoord = stage_input.texCoord;
    oneTexel = stage_input.oneTexel;
    frag_main();
    SPIRV_Cross_Output stage_output;
    stage_output.fragColor = fragColor;
    return stage_output;
}
