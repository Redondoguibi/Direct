Texture2D<float4> DiffuseSampler : register(t0);
SamplerState _DiffuseSampler_sampler : register(s0);
Texture2D<float4> DiffuseDepthSampler : register(t1);
SamplerState _DiffuseDepthSampler_sampler : register(s1);
Texture2D<float4> TranslucentSampler : register(t2);
SamplerState _TranslucentSampler_sampler : register(s2);
Texture2D<float4> TranslucentDepthSampler : register(t3);
SamplerState _TranslucentDepthSampler_sampler : register(s3);
Texture2D<float4> ItemEntitySampler : register(t4);
SamplerState _ItemEntitySampler_sampler : register(s4);
Texture2D<float4> ItemEntityDepthSampler : register(t5);
SamplerState _ItemEntityDepthSampler_sampler : register(s5);
Texture2D<float4> ParticlesSampler : register(t6);
SamplerState _ParticlesSampler_sampler : register(s6);
Texture2D<float4> ParticlesDepthSampler : register(t7);
SamplerState _ParticlesDepthSampler_sampler : register(s7);
Texture2D<float4> WeatherSampler : register(t10);
SamplerState _WeatherSampler_sampler : register(s10);
Texture2D<float4> WeatherDepthSampler : register(t11);
SamplerState _WeatherDepthSampler_sampler : register(s11);
Texture2D<float4> CloudsSampler : register(t8);
SamplerState _CloudsSampler_sampler : register(s8);
Texture2D<float4> CloudsDepthSampler : register(t9);
SamplerState _CloudsDepthSampler_sampler : register(s9);

static float2 texCoord;
static float4 fragColor;

struct SPIRV_Cross_Input
{
    float2 texCoord : TEXCOORD0;
};

struct SPIRV_Cross_Output
{
    float4 fragColor : SV_Target0;
};

void frag_main()
{
    float4 color_layers[6];
    color_layers[0] = float4(DiffuseSampler.Sample(_DiffuseSampler_sampler, texCoord).xyz, 1.0f);
    float depth_layers[6];
    depth_layers[0] = DiffuseDepthSampler.Sample(_DiffuseDepthSampler_sampler, texCoord).x;
    float4 _139 = TranslucentSampler.Sample(_TranslucentSampler_sampler, texCoord);
    float4 _143 = TranslucentDepthSampler.Sample(_TranslucentDepthSampler_sampler, texCoord);
    int _571;
    do
    {
        if (_139.w == 0.0f)
        {
            _571 = 1;
            break;
        }
        color_layers[1] = _139;
        depth_layers[1] = _143.x;
        int _568_copy;
        int _567 = 1;
        int _568 = 0;
        for (;;)
        {
            bool _260 = _567 > 0;
            bool _270;
            if (_260)
            {
                _270 = depth_layers[_567] > depth_layers[_568];
            }
            else
            {
                _270 = _260;
            }
            if (_270)
            {
                float _274 = depth_layers[_568];
                depth_layers[_568] = depth_layers[_567];
                depth_layers[_567] = _274;
                float4 _285 = color_layers[_568];
                color_layers[_568] = color_layers[_567];
                color_layers[_567] = _285;
                _568_copy = _568;
                _568--;
                _567 = _568_copy;
                continue;
            }
            else
            {
                break;
            }
        }
        _571 = 2;
        break;
    } while(false);
    float4 _151 = ItemEntitySampler.Sample(_ItemEntitySampler_sampler, texCoord);
    float4 _155 = ItemEntityDepthSampler.Sample(_ItemEntityDepthSampler_sampler, texCoord);
    int _578;
    do
    {
        if (_151.w == 0.0f)
        {
            _578 = _571;
            break;
        }
        color_layers[_571] = _151;
        depth_layers[_571] = _155.x;
        int _320 = _571 - 1;
        int _575_copy;
        int _574 = _571;
        int _575 = _320;
        for (;;)
        {
            bool _324 = _574 > 0;
            bool _334;
            if (_324)
            {
                _334 = depth_layers[_574] > depth_layers[_575];
            }
            else
            {
                _334 = _324;
            }
            if (_334)
            {
                float _338 = depth_layers[_575];
                depth_layers[_575] = depth_layers[_574];
                depth_layers[_574] = _338;
                float4 _349 = color_layers[_575];
                color_layers[_575] = color_layers[_574];
                color_layers[_574] = _349;
                _575_copy = _575;
                _575--;
                _574 = _575_copy;
                continue;
            }
            else
            {
                break;
            }
        }
        _578 = _571 + 1;
        break;
    } while(false);
    float4 _163 = ParticlesSampler.Sample(_ParticlesSampler_sampler, texCoord);
    float4 _167 = ParticlesDepthSampler.Sample(_ParticlesDepthSampler_sampler, texCoord);
    int _585;
    do
    {
        if (_163.w == 0.0f)
        {
            _585 = _578;
            break;
        }
        color_layers[_578] = _163;
        depth_layers[_578] = _167.x;
        int _384 = _578 - 1;
        int _582_copy;
        int _581 = _578;
        int _582 = _384;
        for (;;)
        {
            bool _388 = _581 > 0;
            bool _398;
            if (_388)
            {
                _398 = depth_layers[_581] > depth_layers[_582];
            }
            else
            {
                _398 = _388;
            }
            if (_398)
            {
                float _402 = depth_layers[_582];
                depth_layers[_582] = depth_layers[_581];
                depth_layers[_581] = _402;
                float4 _413 = color_layers[_582];
                color_layers[_582] = color_layers[_581];
                color_layers[_581] = _413;
                _582_copy = _582;
                _582--;
                _581 = _582_copy;
                continue;
            }
            else
            {
                break;
            }
        }
        _585 = _578 + 1;
        break;
    } while(false);
    float4 _175 = WeatherSampler.Sample(_WeatherSampler_sampler, texCoord);
    float4 _179 = WeatherDepthSampler.Sample(_WeatherDepthSampler_sampler, texCoord);
    int _592;
    do
    {
        if (_175.w == 0.0f)
        {
            _592 = _585;
            break;
        }
        color_layers[_585] = _175;
        depth_layers[_585] = _179.x;
        int _448 = _585 - 1;
        int _589_copy;
        int _588 = _585;
        int _589 = _448;
        for (;;)
        {
            bool _452 = _588 > 0;
            bool _462;
            if (_452)
            {
                _462 = depth_layers[_588] > depth_layers[_589];
            }
            else
            {
                _462 = _452;
            }
            if (_462)
            {
                float _466 = depth_layers[_589];
                depth_layers[_589] = depth_layers[_588];
                depth_layers[_588] = _466;
                float4 _477 = color_layers[_589];
                color_layers[_589] = color_layers[_588];
                color_layers[_588] = _477;
                _589_copy = _589;
                _589--;
                _588 = _589_copy;
                continue;
            }
            else
            {
                break;
            }
        }
        _592 = _585 + 1;
        break;
    } while(false);
    float4 _187 = CloudsSampler.Sample(_CloudsSampler_sampler, texCoord);
    float4 _191 = CloudsDepthSampler.Sample(_CloudsDepthSampler_sampler, texCoord);
    int _601;
    do
    {
        if (_187.w == 0.0f)
        {
            _601 = _592;
            break;
        }
        color_layers[_592] = _187;
        depth_layers[_592] = _191.x;
        int _512 = _592 - 1;
        int _596_copy;
        int _595 = _592;
        int _596 = _512;
        for (;;)
        {
            bool _516 = _595 > 0;
            bool _526;
            if (_516)
            {
                _526 = depth_layers[_595] > depth_layers[_596];
            }
            else
            {
                _526 = _516;
            }
            if (_526)
            {
                float _530 = depth_layers[_596];
                depth_layers[_596] = depth_layers[_595];
                depth_layers[_595] = _530;
                float4 _541 = color_layers[_596];
                color_layers[_596] = color_layers[_595];
                color_layers[_595] = _541;
                _596_copy = _596;
                _596--;
                _595 = _596_copy;
                continue;
            }
            else
            {
                break;
            }
        }
        _601 = _592 + 1;
        break;
    } while(false);
    float3 _604;
    _604 = color_layers[0].xyz;
    for (int _599 = 1; _599 < _601; )
    {
        _604 = (_604 * (1.0f - color_layers[_599].w)) + color_layers[_599].xyz;
        _599++;
        continue;
    }
    fragColor = float4(_604, 1.0f);
}

SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)
{
    texCoord = stage_input.texCoord;
    frag_main();
    SPIRV_Cross_Output stage_output;
    stage_output.fragColor = fragColor;
    return stage_output;
}
