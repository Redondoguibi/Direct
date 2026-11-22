cbuffer MinecraftUniforms : register(b0)
{
    float4 _65_ColorModulator : packoffset(c8);
    float _65_FogStart : packoffset(c10.w);
    float _65_FogEnd : packoffset(c11);
};

Texture2D<float4> Sampler0 : register(t0);
SamplerState _Sampler0_sampler : register(s0);

static float2 texCoord0;
static float4 vertexColor;
static float4 overlayColor;
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
    float4 _48 = Sampler0.Sample(_Sampler0_sampler, texCoord0);
    if (_48.w < 0.100000001490116119384765625f)
    {
        discard;
    }
    float4 _73 = _48 * (vertexColor * _65_ColorModulator);
    float3 _83 = lerp(overlayColor.xyz, _73.xyz, overlayColor.w.xxx);
    float4 _143 = _73;
    _143.x = _83.x;
    _143.y = _83.y;
    _143.z = _83.z;
    float _140;
    do
    {
        if (vertexDistance <= _65_FogStart)
        {
            _140 = 1.0f;
            break;
        }
        else
        {
            if (vertexDistance >= _65_FogEnd)
            {
                _140 = 0.0f;
                break;
            }
        }
        _140 = smoothstep(_65_FogEnd, _65_FogStart, vertexDistance);
        break;
    } while(false);
    fragColor = _143 * _140;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    texCoord0 = stage_input.texCoord0;
    vertexColor = stage_input.vertexColor;
    overlayColor = stage_input.overlayColor;
    vertexDistance = stage_input.vertexDistance;
    frag_main();
    SPIRV_Cross_Output stage_output;
    stage_output.fragColor = fragColor;
    return stage_output;
}
