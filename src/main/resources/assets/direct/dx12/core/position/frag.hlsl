cbuffer MinecraftUniforms : register(b0)
{
    float4 _67_ColorModulator : packoffset(c8);
    float _67_FogStart : packoffset(c9);
    float _67_FogEnd : packoffset(c9.y);
    float4 _67_FogColor : packoffset(c10);
};


static float4 fragColor;
static float vertexDistance;

struct SPIRV_Cross_Input
{
    float vertexDistance : TEXCOORD0;
};

struct SPIRV_Cross_Output
{
    float4 fragColor : SV_Target0;
};

void frag_main()
{
    float4 _143;
    do
    {
        if (vertexDistance <= _67_FogStart)
        {
            _143 = _67_ColorModulator;
            break;
        }
        float _142;
        if (vertexDistance < _67_FogEnd)
        {
            _142 = smoothstep(_67_FogStart, _67_FogEnd, vertexDistance);
        }
        else
        {
            _142 = 1.0f;
        }
        _143 = float4(lerp(_67_ColorModulator.xyz, _67_FogColor.xyz, (_142 * _67_FogColor.w).xxx), _67_ColorModulator.w);
        break;
    } while(false);
    fragColor = _143;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    vertexDistance = stage_input.vertexDistance;
    frag_main();
    SPIRV_Cross_Output stage_output;
    stage_output.fragColor = fragColor;
    return stage_output;
}
