cbuffer MinecraftUniforms : register(b0)
{
    float4 _80_ColorModulator : packoffset(c8);
    float _80_FogStart : packoffset(c10.w);
    float _80_FogEnd : packoffset(c11);
    float4 _80_FogColor : packoffset(c12);
};

Texture2D<float4> Sampler0 : register(t0);
SamplerState _Sampler0_sampler : register(s0);

static float2 texCoord0;
static float4 vertexColor;
static float4 overlayColor;
static float4 lightMapColor;
static float4 fragColor;
static float vertexDistance;

struct SPIRV_Cross_Input
{
    float vertexDistance : TEXCOORD0;
    float4 vertexColor : TEXCOORD1;
    float4 lightMapColor : TEXCOORD2;
    float4 overlayColor : TEXCOORD3;
    float2 texCoord0 : TEXCOORD4;
};

struct SPIRV_Cross_Output
{
    float4 fragColor : SV_Target0;
};

void frag_main()
{
    float4 _85 = (Sampler0.Sample(_Sampler0_sampler, texCoord0) * vertexColor) * _80_ColorModulator;
    float3 _95 = lerp(overlayColor.xyz, _85.xyz, overlayColor.w.xxx);
    float4 _183 = _85;
    _183.x = _95.x;
    _183.y = _95.y;
    _183.z = _95.z;
    float4 _108 = _183 * lightMapColor;
    float4 _181;
    do
    {
        if (vertexDistance <= _80_FogStart)
        {
            _181 = _108;
            break;
        }
        float _180;
        if (vertexDistance < _80_FogEnd)
        {
            _180 = smoothstep(_80_FogStart, _80_FogEnd, vertexDistance);
        }
        else
        {
            _180 = 1.0f;
        }
        _181 = float4(lerp(_108.xyz, _80_FogColor.xyz, (_180 * _80_FogColor.w).xxx), _108.w);
        break;
    } while(false);
    fragColor = _181;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    texCoord0 = stage_input.texCoord0;
    vertexColor = stage_input.vertexColor;
    overlayColor = stage_input.overlayColor;
    lightMapColor = stage_input.lightMapColor;
    vertexDistance = stage_input.vertexDistance;
    frag_main();
    SPIRV_Cross_Output stage_output;
    stage_output.fragColor = fragColor;
    return stage_output;
}
