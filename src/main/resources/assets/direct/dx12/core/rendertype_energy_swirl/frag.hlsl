cbuffer MinecraftUniforms : register(b0)
{
    float4 _57_ColorModulator : packoffset(c12);
    float _57_FogStart : packoffset(c13);
    float _57_FogEnd : packoffset(c13.y);
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
    float4 _48 = Sampler0.Sample(_Sampler0_sampler, texCoord0);
    float4 _62 = (_48 * vertexColor) * _57_ColorModulator;
    if (_62.w < 0.100000001490116119384765625f)
    {
        discard;
    }
    float _121;
    do
    {
        if (vertexDistance <= _57_FogStart)
        {
            _121 = 1.0f;
            break;
        }
        else
        {
            if (vertexDistance >= _57_FogEnd)
            {
                _121 = 0.0f;
                break;
            }
        }
        _121 = smoothstep(_57_FogEnd, _57_FogStart, vertexDistance);
        break;
    } while(false);
    fragColor = _62 * _121;
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
