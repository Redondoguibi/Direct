cbuffer MinecraftUniforms : register(b0)
{
    float4 _53_ColorModulator : packoffset(c8);
    float _53_GlintAlpha : packoffset(c9);
    float _53_FogStart : packoffset(c9.y);
    float _53_FogEnd : packoffset(c9.z);
};

Texture2D<float4> Sampler0 : register(t0);
SamplerState _Sampler0_sampler : register(s0);

static float2 texCoord0;
static float vertexDistance;
static float4 fragColor;

struct SPIRV_Cross_Input
{
    float vertexDistance : TEXCOORD0;
    float2 texCoord0 : TEXCOORD1;
};

struct SPIRV_Cross_Output
{
    float4 fragColor : SV_Target0;
};

void frag_main()
{
    float4 _48 = Sampler0.Sample(_Sampler0_sampler, texCoord0);
    float4 _58 = _48 * _53_ColorModulator;
    float _62 = _58.w;
    if (_62 < 0.100000001490116119384765625f)
    {
        discard;
    }
    float _131;
    do
    {
        if (vertexDistance <= _53_FogStart)
        {
            _131 = 1.0f;
            break;
        }
        else
        {
            if (vertexDistance >= _53_FogEnd)
            {
                _131 = 0.0f;
                break;
            }
        }
        _131 = smoothstep(_53_FogEnd, _53_FogStart, vertexDistance);
        break;
    } while(false);
    fragColor = float4(_58.xyz * (_131 * _53_GlintAlpha), _62);
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    texCoord0 = stage_input.texCoord0;
    vertexDistance = stage_input.vertexDistance;
    frag_main();
    SPIRV_Cross_Output stage_output;
    stage_output.fragColor = fragColor;
    return stage_output;
}
