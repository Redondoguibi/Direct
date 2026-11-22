cbuffer MinecraftUniforms : register(b0)
{
    float4 _69_ColorModulator : packoffset(c8);
    float _69_FogStart : packoffset(c9);
    float _69_FogEnd : packoffset(c9.y);
    float4 _69_FogColor : packoffset(c10);
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
    float4 _74 = vertexColor * _69_ColorModulator;
    float _76 = _74.w;
    if (_76 < 0.100000001490116119384765625f)
    {
        discard;
    }
    float4 _163;
    do
    {
        if (vertexDistance <= _69_FogStart)
        {
            _163 = _74;
            break;
        }
        float _162;
        if (vertexDistance < _69_FogEnd)
        {
            _162 = smoothstep(_69_FogStart, _69_FogEnd, vertexDistance);
        }
        else
        {
            _162 = 1.0f;
        }
        _163 = float4(lerp(_74.xyz, _69_FogColor.xyz, (_162 * _69_FogColor.w).xxx), _76);
        break;
    } while(false);
    fragColor = _163;
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
