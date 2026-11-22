cbuffer MinecraftUniforms : register(b0)
{
    float4 _86_ColorModulator : packoffset(c8);
    float _86_FogStart : packoffset(c10.w);
    float _86_FogEnd : packoffset(c11);
    float4 _86_FogColor : packoffset(c12);
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
    float4 _71 = Sampler0.Sample(_Sampler0_sampler, texCoord0);
    if (_71.w < 0.100000001490116119384765625f)
    {
        discard;
    }
    float4 _93 = _71 * (vertexColor * _86_ColorModulator);
    float3 _103 = lerp(overlayColor.xyz, _93.xyz, overlayColor.w.xxx);
    float4 _192 = _93;
    _192.x = _103.x;
    _192.y = _103.y;
    _192.z = _103.z;
    float4 _116 = _192 * lightMapColor;
    float4 _189;
    do
    {
        if (vertexDistance <= _86_FogStart)
        {
            _189 = _116;
            break;
        }
        float _188;
        if (vertexDistance < _86_FogEnd)
        {
            _188 = smoothstep(_86_FogStart, _86_FogEnd, vertexDistance);
        }
        else
        {
            _188 = 1.0f;
        }
        _189 = float4(lerp(_116.xyz, _86_FogColor.xyz, (_188 * _86_FogColor.w).xxx), _116.w);
        break;
    } while(false);
    fragColor = _189;
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
