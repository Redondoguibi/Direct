cbuffer MinecraftUniforms : register(b0)
{
    float _71_FogStart : packoffset(c9);
    float _71_FogEnd : packoffset(c9.y);
    float4 _71_FogColor : packoffset(c10);
};


static float4 fragColor;
static float4 vertexColor;
static float vertexDistance;

struct SPIRV_Cross_Input
{
    float vertexDistance : TEXCOORD0;
    nointerpolation float4 vertexColor : TEXCOORD1;
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
        if (vertexDistance <= _71_FogStart)
        {
            _143 = vertexColor;
            break;
        }
        float _142;
        if (vertexDistance < _71_FogEnd)
        {
            _142 = smoothstep(_71_FogStart, _71_FogEnd, vertexDistance);
        }
        else
        {
            _142 = 1.0f;
        }
        _143 = float4(lerp(vertexColor.xyz, _71_FogColor.xyz, (_142 * _71_FogColor.w).xxx), vertexColor.w);
        break;
    } while(false);
    fragColor = _143;
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
