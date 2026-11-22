cbuffer MinecraftUniforms : register(b0)
{
    float3 _28_Gray : packoffset(c5);
    float3 _28_RedMatrix : packoffset(c6);
    float3 _28_GreenMatrix : packoffset(c7);
    float3 _28_BlueMatrix : packoffset(c8);
    float3 _28_Offset : packoffset(c9);
    float3 _28_ColorScale : packoffset(c10);
    float _28_Saturation : packoffset(c10.w);
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
    float3 _24 = DiffuseSampler.Sample(_DiffuseSampler_sampler, texCoord).xyz;
    float3 _63 = (float3(dot(_24, _28_RedMatrix), dot(_24, _28_GreenMatrix), dot(_24, _28_BlueMatrix)) * _28_ColorScale) + _28_Offset;
    float3 _73 = dot(_63, _28_Gray).xxx;
    fragColor = float4(((_63 - _73) * _28_Saturation) + _73, 1.0f);
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    texCoord = stage_input.texCoord;
    frag_main();
    SPIRV_Cross_Output stage_output;
    stage_output.fragColor = fragColor;
    return stage_output;
}
