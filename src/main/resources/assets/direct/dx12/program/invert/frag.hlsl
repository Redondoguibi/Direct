cbuffer MinecraftUniforms : register(b0)
{
    float _31_InverseAmount : packoffset(c4.z);
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
    float4 _19 = DiffuseSampler.Sample(_DiffuseSampler_sampler, texCoord);
    fragColor = float4(lerp(_19, 1.0f.xxxx - _19, _31_InverseAmount.xxxx).xyz, 1.0f);
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    texCoord = stage_input.texCoord;
    frag_main();
    SPIRV_Cross_Output stage_output;
    stage_output.fragColor = fragColor;
    return stage_output;
}
