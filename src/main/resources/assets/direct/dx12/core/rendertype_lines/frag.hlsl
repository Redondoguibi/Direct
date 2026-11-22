cbuffer MinecraftUniforms : register(b0)
{
    float4 _70_ColorModulator : packoffset(c8);
    float _70_FogStart : packoffset(c10);
    float _70_FogEnd : packoffset(c10.y);
    float4 _70_FogColor : packoffset(c11);
};


static float4 vertexColor;
static float4 fragColor;
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
    float4 _75 = vertexColor * _70_ColorModulator;
    float4 _150;
    do
    {
        if (vertexDistance <= _70_FogStart)
        {
            _150 = _75;
            break;
        }
        float _149;
        if (vertexDistance < _70_FogEnd)
        {
            _149 = smoothstep(_70_FogStart, _70_FogEnd, vertexDistance);
        }
        else
        {
            _149 = 1.0f;
        }
        _150 = float4(lerp(_75.xyz, _70_FogColor.xyz, (_149 * _70_FogColor.w).xxx), _75.w);
        break;
    } while(false);
    fragColor = _150;
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
