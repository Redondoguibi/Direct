float4 main(float4 position : SV_Position) : SV_Target0
{
    float2 uv = float2(position.x / u_Frame.y, 1.0 - position.y / u_Frame.z);
    return u_Scene.Sample(u_SceneSampler, uv) * u_Tint;
}
