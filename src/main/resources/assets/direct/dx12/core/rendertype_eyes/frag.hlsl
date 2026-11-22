cbuffer MinecraftUniforms : register(b0)
{
    float4 _60_ColorModulator : packoffset(c8);
    float _60_FogStart : packoffset(c9);
    float _60_FogEnd : packoffset(c9.y);
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
    float _113;
    do
    {
        if (vertexDistance <= _60_FogStart)
        {
            _113 = 1.0f;
            break;
        }
        else
        {
            if (vertexDistance >= _60_FogEnd)
            {
                _113 = 0.0f;
                break;
            }
        }
        _113 = smoothstep(_60_FogEnd, _60_FogStart, vertexDistance);
        break;
    } while(false);
    fragColor = ((_48 * vertexColor) * _60_ColorModulator) * _113;
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
