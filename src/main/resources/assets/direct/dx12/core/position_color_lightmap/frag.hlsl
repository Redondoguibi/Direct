cbuffer MinecraftUniforms : register(b0)
{
    float4 _30_ColorModulator : packoffset(c8);
};

Texture2D<float4> Sampler2 : register(t0);
SamplerState _Sampler2_sampler : register(s0);

static float2 texCoord2;
static float4 vertexColor;
static float4 fragColor;

struct SPIRV_Cross_Input
{
    float4 vertexColor : TEXCOORD0;
    float2 texCoord2 : TEXCOORD1;
};

struct SPIRV_Cross_Output
{
    float4 fragColor : SV_Target0;
};

void frag_main()
{
    fragColor = (Sampler2.Sample(_Sampler2_sampler, texCoord2) * vertexColor) * _30_ColorModulator;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    texCoord2 = stage_input.texCoord2;
    vertexColor = stage_input.vertexColor;
    frag_main();
    SPIRV_Cross_Output stage_output;
    stage_output.fragColor = fragColor;
    return stage_output;
}
