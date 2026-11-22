cbuffer MinecraftUniforms : register(b0)
{
    float4 _105_ColorModulator : packoffset(c8);
    float _105_FogStart : packoffset(c10.w);
    float _105_FogEnd : packoffset(c11);
    float4 _105_FogColor : packoffset(c12);
};

Texture2D<float4> Sampler0 : register(t0);
SamplerState _Sampler0_sampler : register(s0);

static float2 texCoord0;
static float4 overlayColor;
static float4 vertexColor;
static float4 fragColor;
static float vertexDistance;

struct SPIRV_Cross_Input
{
    float vertexDistance : TEXCOORD0;
    float4 vertexColor : TEXCOORD1;
    float4 overlayColor : TEXCOORD2;
    float2 texCoord0 : TEXCOORD3;
};

struct SPIRV_Cross_Output
{
    float4 fragColor : SV_Target0;
};

void frag_main()
{
    float4 _71 = Sampler0.Sample(_Sampler0_sampler, texCoord0);
    if (_71.w < 0.100000001490116119384765625f)
    {
        discard;
    }
    float3 _89 = lerp(overlayColor.xyz, _71.xyz, overlayColor.w.xxx);
    _71.x = _89.x;
    _71.y = _89.y;
    _71.z = _89.z;
    float4 _112 = _71 * (vertexColor * _105_ColorModulator);
    float4 _185;
    do
    {
        if (vertexDistance <= _105_FogStart)
        {
            _185 = _112;
            break;
        }
        float _184;
        if (vertexDistance < _105_FogEnd)
        {
            _184 = smoothstep(_105_FogStart, _105_FogEnd, vertexDistance);
        }
        else
        {
            _184 = 1.0f;
        }
        _185 = float4(lerp(_112.xyz, _105_FogColor.xyz, (_184 * _105_FogColor.w).xxx), _112.w);
        break;
    } while(false);
    fragColor = _185;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    texCoord0 = stage_input.texCoord0;
    overlayColor = stage_input.overlayColor;
    vertexColor = stage_input.vertexColor;
    vertexDistance = stage_input.vertexDistance;
    frag_main();
    SPIRV_Cross_Output stage_output;
    stage_output.fragColor = fragColor;
    return stage_output;
}
