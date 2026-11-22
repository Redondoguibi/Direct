cbuffer MinecraftUniforms : register(b0)
{
    float4 _80_ColorModulator : packoffset(c8);
    float _80_FogStart : packoffset(c9);
    float _80_FogEnd : packoffset(c9.y);
    float4 _80_FogColor : packoffset(c10);
};

Texture2D<float4> Sampler0 : register(t0);
SamplerState _Sampler0_sampler : register(s0);

static float2 texCoord0;
static float4 vertexColor;
static float4 fragColor;
static float vertexDistance;

struct SPIRV_Cross_Input
{
    float2 texCoord0 : TEXCOORD0;
    float vertexDistance : TEXCOORD1;
    float4 vertexColor : TEXCOORD2;
};

struct SPIRV_Cross_Output
{
    float4 fragColor : SV_Target0;
};

void frag_main()
{
    float4 _71 = Sampler0.Sample(_Sampler0_sampler, texCoord0);
    float4 _85 = (_71 * vertexColor) * _80_ColorModulator;
    float _87 = _85.w;
    if (_87 < 0.100000001490116119384765625f)
    {
        discard;
    }
    float4 _167;
    do
    {
        if (vertexDistance <= _80_FogStart)
        {
            _167 = _85;
            break;
        }
        float _166;
        if (vertexDistance < _80_FogEnd)
        {
            _166 = smoothstep(_80_FogStart, _80_FogEnd, vertexDistance);
        }
        else
        {
            _166 = 1.0f;
        }
        _167 = float4(lerp(_85.xyz, _80_FogColor.xyz, (_166 * _80_FogColor.w).xxx), _87);
        break;
    } while(false);
    fragColor = _167;
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
