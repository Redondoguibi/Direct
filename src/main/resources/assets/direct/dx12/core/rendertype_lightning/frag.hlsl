cbuffer MinecraftUniforms : register(b0)
{
    float4 _46_ColorModulator : packoffset(c8);
    float _46_FogStart : packoffset(c9);
    float _46_FogEnd : packoffset(c9.y);
};


static float4 fragColor;
static float4 vertexColor;
static float vertexDistance;

struct SPIRV_Cross_Input
{
    float vertexDistance : TEXCOORD0;
    float4 vertexColor : TEXCOORD1;
};

struct SPIRV_Cross_Output
{
    float4 fragColor : SV_Target0;
};

void frag_main()
{
    float _99;
    do
    {
        if (vertexDistance <= _46_FogStart)
        {
            _99 = 1.0f;
            break;
        }
        else
        {
            if (vertexDistance >= _46_FogEnd)
            {
                _99 = 0.0f;
                break;
            }
        }
        _99 = smoothstep(_46_FogEnd, _46_FogStart, vertexDistance);
        break;
    } while(false);
    fragColor = (vertexColor * _46_ColorModulator) * _99;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    vertexColor = stage_input.vertexColor;
    vertexDistance = stage_input.vertexDistance;
    frag_main();
    SPIRV_Cross_Output stage_output;
    stage_output.fragColor = fragColor;
    return stage_output;
}
