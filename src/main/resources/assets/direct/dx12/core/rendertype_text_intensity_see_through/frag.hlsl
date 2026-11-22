cbuffer MinecraftUniforms : register(b0)
{
    float4 _42_ColorModulator : packoffset(c8);
};

Texture2D<float4> Sampler0 : register(t0);
SamplerState _Sampler0_sampler : register(s0);

static float2 texCoord0;
static float4 vertexColor;
static float4 fragColor;

struct SPIRV_Cross_Input
{
    float4 vertexColor : TEXCOORD0;
    float2 texCoord0 : TEXCOORD1;
};

struct SPIRV_Cross_Output
{
    float4 fragColor : SV_Target0;
};

void frag_main()
{
    float4 _19 = Sampler0.Sample(_Sampler0_sampler, texCoord0);
    float4 _24 = _19.xxxx * vertexColor;
    if (_24.w < 0.100000001490116119384765625f)
    {
        discard;
    }
    fragColor = _24 * _42_ColorModulator;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    texCoord0 = stage_input.texCoord0;
    vertexColor = stage_input.vertexColor;
    frag_main();
    SPIRV_Cross_Output stage_output;
    stage_output.fragColor = fragColor;
    return stage_output;
}
