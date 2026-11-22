cbuffer MinecraftUniforms : register(b0)
{
    row_major float4x4 _78_ProjMat : packoffset(c4);
    float4 _78_ColorModulator : packoffset(c8);
    float _78_FogStart : packoffset(c9);
    float _78_FogEnd : packoffset(c9.y);
    float4 _78_FogColor : packoffset(c10);
};

Texture2D<float4> Sampler0 : register(t0);
SamplerState _Sampler0_sampler : register(s0);

static float4 gl_FragCoord;
static float2 texCoord0;
static float4 vertexColor;
static float4 fragColor;

struct SPIRV_Cross_Input
{
    float4 vertexColor : TEXCOORD0;
    float2 texCoord0 : TEXCOORD1;
    float4 gl_FragCoord : SV_Position;
};

struct SPIRV_Cross_Output
{
    float4 fragColor : SV_Target0;
};

void frag_main()
{
    float4 _71 = Sampler0.Sample(_Sampler0_sampler, texCoord0);
    float4 _86 = _71 * (vertexColor * _78_ColorModulator);
    float _105 = (-_78_ProjMat[3].z) / (((gl_FragCoord.z * (-2.0f)) + 1.0f) - _78_ProjMat[2].z);
    float4 _176;
    do
    {
        if (_105 <= _78_FogStart)
        {
            _176 = _86;
            break;
        }
        float _175;
        if (_105 < _78_FogEnd)
        {
            _175 = smoothstep(_78_FogStart, _78_FogEnd, _105);
        }
        else
        {
            _175 = 1.0f;
        }
        _176 = float4(lerp(_86.xyz, _78_FogColor.xyz, (_175 * _78_FogColor.w).xxx), _86.w);
        break;
    } while(false);
    fragColor = _176;
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    gl_FragCoord = stage_input.gl_FragCoord;
    gl_FragCoord.w = 1.0 / gl_FragCoord.w;
    texCoord0 = stage_input.texCoord0;
    vertexColor = stage_input.vertexColor;
    frag_main();
    SPIRV_Cross_Output stage_output;
    stage_output.fragColor = fragColor;
    return stage_output;
}
