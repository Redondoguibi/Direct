cbuffer MinecraftUniforms : register(b0)
{
    float4 _36_Scissor : packoffset(c6);
    float4 _36_Vignette : packoffset(c7);
};

Texture2D<float4> DiffuseSampler : register(t0);
SamplerState _DiffuseSampler_sampler : register(s0);
Texture2D<float4> BlurSampler : register(t1);
SamplerState _BlurSampler_sampler : register(s1);

static float2 scaledCoord;
static float2 texCoord;
static float4 fragColor;

struct SPIRV_Cross_Input
{
    float2 texCoord : TEXCOORD0;
    float2 scaledCoord : TEXCOORD1;
};

struct SPIRV_Cross_Output
{
    float4 fragColor : SV_Target0;
};

void frag_main()
{
    float4 _19 = DiffuseSampler.Sample(_DiffuseSampler_sampler, scaledCoord);
    float4 _25 = BlurSampler.Sample(_BlurSampler_sampler, texCoord);
    bool4 _196 = (scaledCoord.x < _36_Scissor.x).xxxx;
    float4 _197 = float4(_196.x ? _25.x : _19.x, _196.y ? _25.y : _19.y, _196.z ? _25.z : _19.z, _196.w ? _25.w : _19.w);
    bool4 _198 = (scaledCoord.y < _36_Scissor.y).xxxx;
    float4 _199 = float4(_198.x ? _25.x : _197.x, _198.y ? _25.y : _197.y, _198.z ? _25.z : _197.z, _198.w ? _25.w : _197.w);
    bool4 _200 = (scaledCoord.x > _36_Scissor.z).xxxx;
    float4 _201 = float4(_200.x ? _25.x : _199.x, _200.y ? _25.y : _199.y, _200.z ? _25.z : _199.z, _200.w ? _25.w : _199.w);
    bool4 _202 = (scaledCoord.y > _36_Scissor.w).xxxx;
    float4 _203 = float4(_202.x ? _25.x : _201.x, _202.y ? _25.y : _201.y, _202.z ? _25.z : _201.z, _202.w ? _25.w : _201.w);
    float4 _187;
    if (scaledCoord.x < _36_Vignette.x)
    {
        _187 = lerp(_25, _203, ((_36_Scissor.x - scaledCoord.x) / (_36_Scissor.x - _36_Vignette.x)).xxxx);
    }
    else
    {
        _187 = _203;
    }
    float4 _189;
    if (scaledCoord.y < _36_Vignette.y)
    {
        _189 = lerp(_25, _187, ((_36_Scissor.y - scaledCoord.y) / (_36_Scissor.y - _36_Vignette.y)).xxxx);
    }
    else
    {
        _189 = _187;
    }
    float4 _191;
    if (scaledCoord.x > _36_Vignette.z)
    {
        _191 = lerp(_25, _189, ((_36_Scissor.z - scaledCoord.x) / (_36_Scissor.z - _36_Vignette.z)).xxxx);
    }
    else
    {
        _191 = _189;
    }
    float4 _192;
    if (scaledCoord.y > _36_Vignette.w)
    {
        _192 = lerp(_25, _191, ((_36_Scissor.w - scaledCoord.y) / (_36_Scissor.w - _36_Vignette.w)).xxxx);
    }
    else
    {
        _192 = _191;
    }
    fragColor = float4(_192.xyz, 1.0f);
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    scaledCoord = stage_input.scaledCoord;
    texCoord = stage_input.texCoord;
    frag_main();
    SPIRV_Cross_Output stage_output;
    stage_output.fragColor = fragColor;
    return stage_output;
}
