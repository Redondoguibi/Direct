cbuffer MinecraftUniforms : register(b0)
{
    float4 _81_ColorModulator : packoffset(c8);
    float _81_FogStart : packoffset(c9);
    float _81_FogEnd : packoffset(c9.y);
    float4 _81_FogColor : packoffset(c10);
};

Texture2D<float4> Sampler0 : register(t0);
SamplerState _Sampler0_sampler : register(s0);

static float2 texCoord0;
static float4 vertexColor;
static float4 fragColor;
static float vertexDistance;

struct SPIRV_Cross_Input
{
    float vertexDistance : TEXCOORD0;
    float4 vertexColor : TEXCOORD1;
    float2 texCoord0 : TEXCOORD2;
};

struct SPIRV_Cross_Output
{
    float4 fragColor : SV_Target0;
};

void frag_main()
{
    float4 _71 = Sampler0.Sample(_Sampler0_sampler, texCoord0);
    float4 _86 = (_71.xxxx * vertexColor) * _81_ColorModulator;
    float _88 = _86.w;
    if (_88 < 0.100000001490116119384765625f)
    {
        discard;
    }
    float4 _168;
    do
    {
        if (vertexDistance <= _81_FogStart)
        {
            _168 = _86;
            break;
        }
        float _167;
        if (vertexDistance < _81_FogEnd)
        {
            _167 = smoothstep(_81_FogStart, _81_FogEnd, vertexDistance);
        }
        else
        {
            _167 = 1.0f;
        }
        _168 = float4(lerp(_86.xyz, _81_FogColor.xyz, (_167 * _81_FogColor.w).xxx), _88);
        break;
    } while(false);
    fragColor = _168;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    texCoord0 = stage_input.texCoord0;
    vertexColor = stage_input.vertexColor;
    vertexDistance = stage_input.vertexDistance;
    frag_main();
    SPIRV_Cross_Output stage_output;
    stage_output.fragColor = fragColor;
    return stage_output;
}
