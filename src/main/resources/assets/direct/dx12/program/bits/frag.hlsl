cbuffer MinecraftUniforms : register(b0)
{
    float2 _14_InSize : packoffset(c4);
    float _14_Resolution : packoffset(c5);
    float _14_Saturation : packoffset(c5.y);
    float _14_MosaicSize : packoffset(c5.z);
};

Texture2D<float4> DiffuseSampler : register(t0);
SamplerState _DiffuseSampler_sampler : register(s0);

static float2 texCoord;
static float4 fragColor;

struct SPIRV_Cross_Input
{
    float2 texCoord : TEXCOORD0;
};

struct SPIRV_Cross_Output
{
    float4 fragColor : SV_Target0;
};

void frag_main()
{
    float2 _25 = _14_InSize / _14_MosaicSize.xx;
    float3 _50 = DiffuseSampler.Sample(_DiffuseSampler_sampler, texCoord - (frac(texCoord * _25) / _25)).xyz;
    float3 _62 = _50 - (frac(_50 * _14_Resolution) / _14_Resolution.xxx);
    float3 _74 = dot(_62, float3(0.300000011920928955078125f, 0.589999973773956298828125f, 0.10999999940395355224609375f)).xxx;
    fragColor = float4(_74 + ((_62 - _74) * _14_Saturation), 1.0f);
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    texCoord = stage_input.texCoord;
    frag_main();
    SPIRV_Cross_Output stage_output;
    stage_output.fragColor = fragColor;
    return stage_output;
}
