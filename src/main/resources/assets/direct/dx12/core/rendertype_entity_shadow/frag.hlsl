cbuffer MinecraftUniforms : register(b0)
{
    float4 _83_ColorModulator : packoffset(c8);
    float _83_FogStart : packoffset(c9);
    float _83_FogEnd : packoffset(c9.y);
    float4 _83_FogColor : packoffset(c10);
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
    float4 _75 = Sampler0.Sample(_Sampler0_sampler, clamp(texCoord0, 0.0f.xx, 1.0f.xx));
    float4 _90 = _75 * (vertexColor * _83_ColorModulator);
    float4 _165;
    do
    {
        if (vertexDistance <= _83_FogStart)
        {
            _165 = _90;
            break;
        }
        float _164;
        if (vertexDistance < _83_FogEnd)
        {
            _164 = smoothstep(_83_FogStart, _83_FogEnd, vertexDistance);
        }
        else
        {
            _164 = 1.0f;
        }
        _165 = float4(lerp(_90.xyz, _83_FogColor.xyz, (_164 * _83_FogColor.w).xxx), _90.w);
        break;
    } while(false);
    fragColor = _165;
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
