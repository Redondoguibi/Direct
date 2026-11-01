#include <jni.h>
#include <windows.h>
#include <d3d11.h>
#include <d3d11on12.h>
#include <d3d12.h>
#include <d3dcompiler.h>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <limits>
#include <mutex>
#include <system_error>
#include <thread>
#include <unordered_map>

namespace {

struct InteropRenderer {
    UINT width;
    UINT height;
    UINT64 fenceValue;
    ID3D12Device* device;
    ID3D12CommandQueue* queue;
    ID3D12Resource* texture;
    ID3D12Fence* fence;
    ID3D12Fence* glDoneFence;
    HANDLE fenceEvent;
    HANDLE sharedFenceHandle;
    HANDLE sharedGlDoneFenceHandle;
    std::mutex watchdogMutex;
    std::condition_variable watchdogCondition;
    std::thread watchdogThread;
    UINT64 watchedFenceValue;
    UINT64 lastQueuedGlDoneValue;
    bool stopWatchdog;
    std::atomic<bool> gpuSyncFault;
    ID3D11Device* d3d11Device;
    ID3D11DeviceContext* d3d11Context;
    ID3D11On12Device* d3d11On12Device;
    ID3D11Resource* wrappedTexture;
    ID3D11Texture2D* interopTexture;
    ID3D11RenderTargetView* renderTarget;
    ID3D11Texture2D* depthTexture;
    ID3D11DepthStencilView* depthView;
    ID3D11VertexShader* terrainVertexShader;
    ID3D11PixelShader* terrainPixelShader;
    ID3D11InputLayout* terrainInputLayout;
    ID3D11Buffer* terrainConstants;
    ID3D11Texture2D* terrainAtlas;
    ID3D11ShaderResourceView* terrainAtlasView;
    ID3D11SamplerState* terrainAtlasSampler;
    std::unordered_map<jlong, struct TerrainSection*>* terrainSections;
};

InteropRenderer* g_interopRenderer;
constexpr DWORD kFenceWaitTimeoutMs = 5000;
constexpr auto kFenceWatchdogTimeout = std::chrono::seconds(5);
constexpr float kClearColor[4] = {0.08f, 0.32f, 0.92f, 1.0f};
constexpr char kTerrainVertexShader[] = R"(
cbuffer FrameConstants : register(b0) {
    float4x4 viewProjection;
};
struct VertexInput {
    float3 position : POSITION;
    float4 color : COLOR;
    float2 uv : TEXCOORD0;
};
struct VertexOutput {
    float4 position : SV_Position;
    float4 color : COLOR;
    float2 uv : TEXCOORD0;
};
VertexOutput main(VertexInput input) {
    VertexOutput output;
    output.position = mul(viewProjection, float4(input.position, 1.0));
    output.color = input.color;
    output.uv = input.uv;
    return output;
}
)";
constexpr char kTerrainPixelShader[] = R"(
Texture2D terrainAtlas : register(t0);
SamplerState terrainAtlasSampler : register(s0);
float4 main(float4 position : SV_Position, float4 color : COLOR, float2 uv : TEXCOORD0) : SV_Target {
    float4 texel = terrainAtlas.Sample(terrainAtlasSampler, uv);
    clip(texel.a * color.a - 0.1);
    return texel * color;
}
)";

struct TerrainSection {
    int x;
    int y;
    int z;
    UINT indexCount;
    ID3D11Buffer* vertexBuffer;
    ID3D11Buffer* indexBuffer;
};

void throwJava(JNIEnv* env, const char* message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

template <typename T>
void release(T*& object) {
    if (object != nullptr) {
        object->Release();
        object = nullptr;
    }
}

bool createSharedFence(ID3D12Device* device, ID3D12Fence** fence, HANDLE* sharedHandle) {
    HRESULT result = device->CreateFence(0, D3D12_FENCE_FLAG_SHARED, IID_PPV_ARGS(fence));
    const bool createdShared = SUCCEEDED(result);
    if (FAILED(result)) {
        result = device->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(fence));
    }
    if (FAILED(result)) {
        return false;
    }
    if (createdShared) {
        *sharedHandle = nullptr;
        device->CreateSharedHandle(*fence, nullptr, GENERIC_ALL, nullptr, sharedHandle);
    }
    return true;
}

void fenceWatchdog(InteropRenderer* renderer) {
    std::unique_lock<std::mutex> lock(renderer->watchdogMutex);
    while (!renderer->stopWatchdog) {
        renderer->watchdogCondition.wait(lock, [renderer] {
            return renderer->stopWatchdog || renderer->watchedFenceValue != 0;
        });
        if (renderer->stopWatchdog) {
            break;
        }

        const auto deadline = std::chrono::steady_clock::now() + kFenceWatchdogTimeout;
        while (!renderer->stopWatchdog && renderer->watchedFenceValue != 0) {
            const UINT64 targetValue = renderer->watchedFenceValue;
            const UINT64 completedValue = renderer->fence->GetCompletedValue();
            if (completedValue == std::numeric_limits<UINT64>::max()) {
                renderer->gpuSyncFault.store(true);
                renderer->watchedFenceValue = 0;
                break;
            }
            if (completedValue >= targetValue) {
                renderer->watchedFenceValue = 0;
                break;
            }
            if (renderer->watchdogCondition.wait_until(lock, deadline) == std::cv_status::timeout) {
                const UINT64 latestTarget = renderer->watchedFenceValue;
                if (latestTarget != 0) {
                    const UINT64 completed = renderer->fence->GetCompletedValue();
                    if ((completed == std::numeric_limits<UINT64>::max() || completed < latestTarget)
                            && FAILED(renderer->fence->Signal(latestTarget))) {
                        OutputDebugStringA("Direct interop watchdog could not CPU-signal the D3D12 fence.\n");
                    }
                    renderer->gpuSyncFault.store(true);
                }
                renderer->watchedFenceValue = 0;
                break;
            }
        }
    }
}

void releaseTerrainSections(InteropRenderer* renderer) {
    if (renderer->terrainSections == nullptr) {
        return;
    }
    for (auto& entry : *renderer->terrainSections) {
        release(entry.second->vertexBuffer);
        release(entry.second->indexBuffer);
        delete entry.second;
    }
    renderer->terrainSections->clear();
}

void destroyInteropRenderer(InteropRenderer* renderer) {
    if (renderer == nullptr) {
        return;
    }
    if (renderer->glDoneFence != nullptr && renderer->lastQueuedGlDoneValue > 0
            && renderer->glDoneFence->GetCompletedValue() < renderer->lastQueuedGlDoneValue) {
        renderer->glDoneFence->Signal(renderer->lastQueuedGlDoneValue);
    }
    {
        std::lock_guard<std::mutex> lock(renderer->watchdogMutex);
        renderer->stopWatchdog = true;
    }
    renderer->watchdogCondition.notify_all();
    if (renderer->watchdogThread.joinable()) {
        renderer->watchdogThread.join();
    }
    if (renderer->queue != nullptr && renderer->fence != nullptr && renderer->fenceEvent != nullptr) {
        const UINT64 completedValue = renderer->fence->GetCompletedValue();
        if (completedValue != std::numeric_limits<UINT64>::max()) {
            const UINT64 baseValue = completedValue > renderer->fenceValue
                    ? completedValue : renderer->fenceValue;
            if (baseValue != std::numeric_limits<UINT64>::max()) {
                const UINT64 value = baseValue + 1;
                if (SUCCEEDED(renderer->queue->Signal(renderer->fence, value))
                        && SUCCEEDED(renderer->fence->SetEventOnCompletion(value, renderer->fenceEvent))) {
                    WaitForSingleObject(renderer->fenceEvent, INFINITE);
                }
            }
        }
    }
    releaseTerrainSections(renderer);
    delete renderer->terrainSections;
    release(renderer->terrainConstants);
    release(renderer->terrainInputLayout);
    release(renderer->terrainVertexShader);
    release(renderer->terrainPixelShader);
    release(renderer->terrainAtlasSampler);
    release(renderer->terrainAtlasView);
    release(renderer->terrainAtlas);
    release(renderer->depthView);
    release(renderer->depthTexture);
    release(renderer->renderTarget);
    release(renderer->interopTexture);
    release(renderer->wrappedTexture);
    release(renderer->d3d11On12Device);
    release(renderer->d3d11Context);
    release(renderer->d3d11Device);
    release(renderer->texture);
    if (renderer->sharedGlDoneFenceHandle != nullptr) {
        CloseHandle(renderer->sharedGlDoneFenceHandle);
    }
    if (renderer->sharedFenceHandle != nullptr) {
        CloseHandle(renderer->sharedFenceHandle);
    }
    release(renderer->glDoneFence);
    release(renderer->fence);
    release(renderer->queue);
    release(renderer->device);
    if (renderer->fenceEvent != nullptr) {
        CloseHandle(renderer->fenceEvent);
    }
    delete renderer;
}

bool createTerrainPipeline(InteropRenderer* renderer, JNIEnv* env) {
    ID3DBlob* vertexBytecode = nullptr;
    ID3DBlob* errors = nullptr;
    HRESULT result = D3DCompile(kTerrainVertexShader, sizeof(kTerrainVertexShader) - 1,
            nullptr, nullptr, nullptr, "main", "vs_4_0", D3DCOMPILE_ENABLE_STRICTNESS,
            0, &vertexBytecode, &errors);
    if (FAILED(result)) {
        throwJava(env, "DX12 terrain vertex shader compilation failed.");
        release(errors);
        return false;
    }
    release(errors);
    result = renderer->d3d11Device->CreateVertexShader(vertexBytecode->GetBufferPointer(),
            vertexBytecode->GetBufferSize(), nullptr, &renderer->terrainVertexShader);
    if (FAILED(result)) {
        release(vertexBytecode);
        throwJava(env, "DX12 terrain vertex shader creation failed.");
        return false;
    }
    const D3D11_INPUT_ELEMENT_DESC elements[] = {
        {"POSITION", 0, DXGI_FORMAT_R32G32B32_FLOAT, 0, 0, D3D11_INPUT_PER_VERTEX_DATA, 0},
        {"COLOR", 0, DXGI_FORMAT_R8G8B8A8_UNORM, 0, 12, D3D11_INPUT_PER_VERTEX_DATA, 0},
        {"TEXCOORD", 0, DXGI_FORMAT_R32G32_FLOAT, 0, 16, D3D11_INPUT_PER_VERTEX_DATA, 0}
    };
    result = renderer->d3d11Device->CreateInputLayout(elements, ARRAYSIZE(elements),
            vertexBytecode->GetBufferPointer(), vertexBytecode->GetBufferSize(),
            &renderer->terrainInputLayout);
    release(vertexBytecode);
    if (FAILED(result)) {
        throwJava(env, "DX12 terrain vertex input layout creation failed.");
        return false;
    }

    ID3DBlob* pixelBytecode = nullptr;
    result = D3DCompile(kTerrainPixelShader, sizeof(kTerrainPixelShader) - 1,
            nullptr, nullptr, nullptr, "main", "ps_4_0", D3DCOMPILE_ENABLE_STRICTNESS,
            0, &pixelBytecode, &errors);
    if (FAILED(result)) {
        throwJava(env, "DX12 terrain pixel shader compilation failed.");
        release(errors);
        return false;
    }
    release(errors);
    result = renderer->d3d11Device->CreatePixelShader(pixelBytecode->GetBufferPointer(),
            pixelBytecode->GetBufferSize(), nullptr, &renderer->terrainPixelShader);
    release(pixelBytecode);
    if (FAILED(result)) {
        throwJava(env, "DX12 terrain pixel shader creation failed.");
        return false;
    }

    D3D11_BUFFER_DESC constantBuffer = {};
    constantBuffer.ByteWidth = 64;
    constantBuffer.Usage = D3D11_USAGE_DYNAMIC;
    constantBuffer.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
    constantBuffer.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
    if (FAILED(renderer->d3d11Device->CreateBuffer(&constantBuffer, nullptr, &renderer->terrainConstants))) {
        throwJava(env, "DX12 terrain camera constant buffer creation failed.");
        return false;
    }
    D3D11_SAMPLER_DESC samplerDescription = {};
    samplerDescription.Filter = D3D11_FILTER_MIN_MAG_MIP_LINEAR;
    samplerDescription.AddressU = D3D11_TEXTURE_ADDRESS_CLAMP;
    samplerDescription.AddressV = D3D11_TEXTURE_ADDRESS_CLAMP;
    samplerDescription.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
    samplerDescription.MaxLOD = D3D11_FLOAT32_MAX;
    if (FAILED(renderer->d3d11Device->CreateSamplerState(&samplerDescription,
            &renderer->terrainAtlasSampler))) {
        throwJava(env, "DX12 terrain atlas sampler creation failed.");
        return false;
    }
    return true;
}

bool initializeInteropRenderer(InteropRenderer* renderer, jint width, jint height, JNIEnv* env) {
    renderer->gpuSyncFault.store(false);
    if (width <= 0 || height <= 0) {
        throwJava(env, "DX12/OpenGL interop requires a non-zero framebuffer size.");
        return false;
    }
    renderer->width = static_cast<UINT>(width);
    renderer->height = static_cast<UINT>(height);
    if (FAILED(D3D12CreateDevice(nullptr, D3D_FEATURE_LEVEL_11_0,
            IID_PPV_ARGS(&renderer->device)))) {
        throwJava(env, "DX12/OpenGL interop could not create a Direct3D 12 device.");
        return false;
    }

    D3D12_COMMAND_QUEUE_DESC queueDescription = {};
    queueDescription.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
    if (FAILED(renderer->device->CreateCommandQueue(&queueDescription, IID_PPV_ARGS(&renderer->queue)))) {
        throwJava(env, "DX12/OpenGL interop could not create a Direct3D 12 command queue.");
        return false;
    }

    IUnknown* queues[] = {renderer->queue};
    D3D_FEATURE_LEVEL featureLevels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0};
    D3D_FEATURE_LEVEL selectedFeatureLevel = D3D_FEATURE_LEVEL_11_0;
    HRESULT result = D3D11On12CreateDevice(renderer->device, D3D11_CREATE_DEVICE_BGRA_SUPPORT,
            featureLevels, ARRAYSIZE(featureLevels), queues, ARRAYSIZE(queues), 0,
            &renderer->d3d11Device, &renderer->d3d11Context, &selectedFeatureLevel);
    if (FAILED(result)) {
        throwJava(env, "DX12/OpenGL interop could not create a D3D11On12 device.");
        return false;
    }
    result = renderer->d3d11Device->QueryInterface(IID_PPV_ARGS(&renderer->d3d11On12Device));
    if (FAILED(result)) {
        throwJava(env, "DX12/OpenGL interop could not query ID3D11On12Device.");
        return false;
    }

    D3D12_RESOURCE_DESC textureDescription = {};
    textureDescription.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
    textureDescription.Width = renderer->width;
    textureDescription.Height = renderer->height;
    textureDescription.DepthOrArraySize = 1;
    textureDescription.MipLevels = 1;
    textureDescription.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    textureDescription.SampleDesc.Count = 1;
    textureDescription.Layout = D3D12_TEXTURE_LAYOUT_UNKNOWN;
    textureDescription.Flags = D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET;

    D3D12_HEAP_PROPERTIES heapProperties = {};
    heapProperties.Type = D3D12_HEAP_TYPE_DEFAULT;
    D3D12_CLEAR_VALUE clearValue = {};
    clearValue.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    for (UINT component = 0; component < 4; ++component) {
        clearValue.Color[component] = kClearColor[component];
    }
    if (FAILED(renderer->device->CreateCommittedResource(&heapProperties, D3D12_HEAP_FLAG_NONE,
            &textureDescription, D3D12_RESOURCE_STATE_COMMON, &clearValue,
            IID_PPV_ARGS(&renderer->texture)))) {
        throwJava(env, "DX12/OpenGL interop could not create the offscreen texture.");
        return false;
    }

    D3D11_RESOURCE_FLAGS resourceFlags = {};
    resourceFlags.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    result = renderer->d3d11On12Device->CreateWrappedResource(renderer->texture, &resourceFlags,
            D3D12_RESOURCE_STATE_COMMON, D3D12_RESOURCE_STATE_COMMON,
            IID_PPV_ARGS(&renderer->wrappedTexture));
    if (FAILED(result)) {
        throwJava(env, "DX12/OpenGL interop could not expose the texture through D3D11On12.");
        return false;
    }
    result = renderer->d3d11Device->CreateRenderTargetView(renderer->wrappedTexture, nullptr,
            &renderer->renderTarget);
    if (FAILED(result)) {
        throwJava(env, "DX12/OpenGL interop could not create a render-target view.");
        return false;
    }
    D3D11_TEXTURE2D_DESC depthDescription = {};
    depthDescription.Width = renderer->width;
    depthDescription.Height = renderer->height;
    depthDescription.MipLevels = 1;
    depthDescription.ArraySize = 1;
    depthDescription.Format = DXGI_FORMAT_D24_UNORM_S8_UINT;
    depthDescription.SampleDesc.Count = 1;
    depthDescription.Usage = D3D11_USAGE_DEFAULT;
    depthDescription.BindFlags = D3D11_BIND_DEPTH_STENCIL;
    result = renderer->d3d11Device->CreateTexture2D(&depthDescription, nullptr, &renderer->depthTexture);
    if (FAILED(result) || FAILED(renderer->d3d11Device->CreateDepthStencilView(
            renderer->depthTexture, nullptr, &renderer->depthView))) {
        throwJava(env, "DX12 terrain depth buffer creation failed.");
        return false;
    }
    if (!createTerrainPipeline(renderer, env)) {
        return false;
    }
    renderer->terrainSections = new std::unordered_map<jlong, TerrainSection*>();
    D3D11_TEXTURE2D_DESC interopTextureDescription = {};
    interopTextureDescription.Width = renderer->width;
    interopTextureDescription.Height = renderer->height;
    interopTextureDescription.MipLevels = 1;
    interopTextureDescription.ArraySize = 1;
    interopTextureDescription.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    interopTextureDescription.SampleDesc.Count = 1;
    interopTextureDescription.Usage = D3D11_USAGE_DEFAULT;
    interopTextureDescription.BindFlags = D3D11_BIND_SHADER_RESOURCE | D3D11_BIND_RENDER_TARGET;
    result = renderer->d3d11Device->CreateTexture2D(&interopTextureDescription, nullptr,
            &renderer->interopTexture);
    if (FAILED(result)) {
        throwJava(env, "DX12/OpenGL interop could not create the D3D11 import texture.");
        return false;
    }
    if (!createSharedFence(renderer->device, &renderer->fence, &renderer->sharedFenceHandle)
            || !createSharedFence(renderer->device, &renderer->glDoneFence,
                    &renderer->sharedGlDoneFenceHandle)) {
        throwJava(env, "DX12/OpenGL interop could not create a Direct3D 12 fence.");
        return false;
    }
    renderer->fenceEvent = CreateEvent(nullptr, FALSE, FALSE, nullptr);
    if (renderer->fenceEvent == nullptr) {
        throwJava(env, "DX12/OpenGL interop could not create a fence event.");
        return false;
    }
    if (renderer->sharedFenceHandle != nullptr && renderer->sharedGlDoneFenceHandle != nullptr) {
        try {
            renderer->watchdogThread = std::thread(fenceWatchdog, renderer);
        } catch (const std::system_error&) {
            CloseHandle(renderer->sharedFenceHandle);
            CloseHandle(renderer->sharedGlDoneFenceHandle);
            renderer->sharedFenceHandle = nullptr;
            renderer->sharedGlDoneFenceHandle = nullptr;
        }
    }
    return true;
}

bool waitForInteropFence(InteropRenderer* renderer, UINT64 value) {
    const UINT64 completedValue = renderer->fence->GetCompletedValue();
    if (completedValue == std::numeric_limits<UINT64>::max()) {
        return false;
    }
    if (completedValue >= value) {
        return true;
    }
    if (FAILED(renderer->fence->SetEventOnCompletion(value, renderer->fenceEvent))) {
        return false;
    }
    const DWORD waitResult = WaitForSingleObject(renderer->fenceEvent, kFenceWaitTimeoutMs);
    if (waitResult == WAIT_OBJECT_0) {
        const UINT64 completedAfterWait = renderer->fence->GetCompletedValue();
        return completedAfterWait != std::numeric_limits<UINT64>::max() && completedAfterWait >= value;
    }
    if (waitResult == WAIT_TIMEOUT && SUCCEEDED(renderer->fence->Signal(value))) {
        renderer->gpuSyncFault.store(true);
        return true;
    }
    return false;
}

bool waitForGlDoneFence(InteropRenderer* renderer, UINT64 value) {
    const UINT64 completedValue = renderer->glDoneFence->GetCompletedValue();
    if (completedValue == std::numeric_limits<UINT64>::max()) {
        return false;
    }
    if (value == 0 || completedValue >= value) {
        return true;
    }
    if (FAILED(renderer->glDoneFence->SetEventOnCompletion(value, renderer->fenceEvent))) {
        return false;
    }
    const DWORD waitResult = WaitForSingleObject(renderer->fenceEvent, kFenceWaitTimeoutMs);
    if (waitResult == WAIT_OBJECT_0) {
        const UINT64 completedAfterWait = renderer->glDoneFence->GetCompletedValue();
        return completedAfterWait != std::numeric_limits<UINT64>::max() && completedAfterWait >= value;
    }
    if (waitResult == WAIT_TIMEOUT && SUCCEEDED(renderer->glDoneFence->Signal(value))) {
        renderer->gpuSyncFault.store(true);
        return true;
    }
    return false;
}

bool renderInteropFrame(InteropRenderer* renderer, const float* viewProjection,
        float cameraX, float cameraY, float cameraZ, float renderDistance,
        UINT64 frameIndex, bool useGpuSynchronization, JNIEnv* env, bool* gpuSynchronizationUsed) {
    if (frameIndex >= std::numeric_limits<UINT64>::max() - 1) {
        throwJava(env, "DX12/OpenGL interop fence timeline has exhausted its frame values.");
        return false;
    }
    const UINT64 glDoneValue = frameIndex;
    const UINT64 dxReadyValue = frameIndex + 1;
    *gpuSynchronizationUsed = useGpuSynchronization && renderer->sharedFenceHandle != nullptr
            && renderer->sharedGlDoneFenceHandle != nullptr && !renderer->gpuSyncFault.load();
    if (*gpuSynchronizationUsed && glDoneValue > 0) {
        const HRESULT waitResult = renderer->queue->Wait(renderer->glDoneFence, glDoneValue);
        if (FAILED(waitResult)) {
            if (!waitForGlDoneFence(renderer, glDoneValue)) {
                throwJava(env, "DX12/OpenGL interop failed to fall back to a CPU wait for the shared fence.");
                return false;
            }
            *gpuSynchronizationUsed = false;
        } else {
            renderer->lastQueuedGlDoneValue = glDoneValue;
        }
    }

    ID3D11Resource* resources[] = {renderer->wrappedTexture};
    renderer->d3d11On12Device->AcquireWrappedResources(resources, ARRAYSIZE(resources));
    renderer->d3d11Context->ClearRenderTargetView(renderer->renderTarget, kClearColor);
    renderer->d3d11Context->ClearDepthStencilView(renderer->depthView,
            D3D11_CLEAR_DEPTH | D3D11_CLEAR_STENCIL, 1.0f, 0);
    const float blendFactor[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    renderer->d3d11Context->OMSetRenderTargets(1, &renderer->renderTarget, renderer->depthView);
    renderer->d3d11Context->OMSetBlendState(nullptr, blendFactor, 0xffffffff);
    renderer->d3d11Context->OMSetDepthStencilState(nullptr, 0);
    D3D11_VIEWPORT viewport = {0.0f, 0.0f, static_cast<float>(renderer->width),
            static_cast<float>(renderer->height), 0.0f, 1.0f};
    renderer->d3d11Context->RSSetViewports(1, &viewport);
    renderer->d3d11Context->IASetInputLayout(renderer->terrainInputLayout);
    renderer->d3d11Context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    renderer->d3d11Context->VSSetShader(renderer->terrainVertexShader, nullptr, 0);
    renderer->d3d11Context->PSSetShader(renderer->terrainPixelShader, nullptr, 0);
    D3D11_MAPPED_SUBRESOURCE mappedConstants = {};
    if (FAILED(renderer->d3d11Context->Map(renderer->terrainConstants, 0,
            D3D11_MAP_WRITE_DISCARD, 0, &mappedConstants))) {
        renderer->d3d11On12Device->ReleaseWrappedResources(resources, ARRAYSIZE(resources));
        throwJava(env, "DX12 terrain camera constants could not be mapped.");
        return false;
    }
    CopyMemory(mappedConstants.pData, viewProjection, 64);
    renderer->d3d11Context->Unmap(renderer->terrainConstants, 0);
    ID3D11Buffer* constants[] = {renderer->terrainConstants};
    renderer->d3d11Context->VSSetConstantBuffers(0, 1, constants);
    ID3D11ShaderResourceView* terrainTextures[] = {renderer->terrainAtlasView};
    ID3D11SamplerState* terrainSamplers[] = {renderer->terrainAtlasSampler};
    renderer->d3d11Context->PSSetShaderResources(0, 1, terrainTextures);
    renderer->d3d11Context->PSSetSamplers(0, 1, terrainSamplers);
    const float maxDistanceSquared = renderDistance * renderDistance;
    for (const auto& entry : *renderer->terrainSections) {
        const TerrainSection* section = entry.second;
        const float dx = static_cast<float>(section->x) + 8.0f - cameraX;
        const float dy = static_cast<float>(section->y) + 8.0f - cameraY;
        const float dz = static_cast<float>(section->z) + 8.0f - cameraZ;
        if (dx * dx + dy * dy + dz * dz > maxDistanceSquared) {
            continue;
        }
        const UINT stride = 24;
        const UINT offset = 0;
        renderer->d3d11Context->IASetVertexBuffers(0, 1, &section->vertexBuffer, &stride, &offset);
        renderer->d3d11Context->IASetIndexBuffer(section->indexBuffer, DXGI_FORMAT_R32_UINT, 0);
        renderer->d3d11Context->DrawIndexed(section->indexCount, 0, 0);
    }
    renderer->d3d11Context->CopyResource(renderer->interopTexture, renderer->wrappedTexture);
    renderer->d3d11On12Device->ReleaseWrappedResources(resources, ARRAYSIZE(resources));
    renderer->d3d11Context->Flush();

    HRESULT signalResult;
    {
        std::lock_guard<std::mutex> lock(renderer->watchdogMutex);
        signalResult = renderer->queue->Signal(renderer->fence, dxReadyValue);
        if (SUCCEEDED(signalResult)) {
            renderer->fenceValue = dxReadyValue;
            if (*gpuSynchronizationUsed) {
                renderer->watchedFenceValue = dxReadyValue;
            }
        }
    }
    if (FAILED(signalResult)) {
        throwJava(env, "DX12/OpenGL interop failed to signal its frame fence.");
        return false;
    }
    if (*gpuSynchronizationUsed) {
        renderer->watchdogCondition.notify_one();
    }
    if (!*gpuSynchronizationUsed && !waitForInteropFence(renderer, dxReadyValue)) {
        throwJava(env, "DX12/OpenGL interop failed while waiting for its frame fence.");
        return false;
    }
    return true;
}

} // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_redondoguibi_direct_NativeDx12_initializeInterop(JNIEnv* env, jclass, jint width, jint height) {
    destroyInteropRenderer(g_interopRenderer);
    g_interopRenderer = nullptr;
    InteropRenderer* renderer = new InteropRenderer{};
    if (!initializeInteropRenderer(renderer, width, height, env)) {
        destroyInteropRenderer(renderer);
        return nullptr;
    }
    jlong handles[] = {
        static_cast<jlong>(reinterpret_cast<intptr_t>(renderer->d3d11Device)),
        static_cast<jlong>(reinterpret_cast<intptr_t>(renderer->interopTexture)),
        static_cast<jlong>(reinterpret_cast<intptr_t>(renderer->sharedFenceHandle)),
        static_cast<jlong>(reinterpret_cast<intptr_t>(renderer->sharedGlDoneFenceHandle))
    };
    jlongArray result = env->NewLongArray(4);
    if (result == nullptr) {
        destroyInteropRenderer(renderer);
        return nullptr;
    }
    env->SetLongArrayRegion(result, 0, 4, handles);
    if (env->ExceptionCheck()) {
        destroyInteropRenderer(renderer);
        return nullptr;
    }
    g_interopRenderer = renderer;
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_redondoguibi_direct_NativeDx12_renderInterop(JNIEnv* env, jclass, jfloatArray matrix,
        jfloat cameraX, jfloat cameraY, jfloat cameraZ, jfloat renderDistance,
        jlong frameIndex, jboolean useGpuSynchronization) {
    if (g_interopRenderer == nullptr) {
        throwJava(env, "DX12/OpenGL interop is not initialized.");
        return JNI_FALSE;
    }
    if (matrix == nullptr || env->GetArrayLength(matrix) != 16) {
        throwJava(env, "DX12 terrain rendering requires a 4x4 view-projection matrix.");
        return JNI_FALSE;
    }
    if (frameIndex < 0) {
        throwJava(env, "DX12/OpenGL interop received a negative frame index.");
        return JNI_FALSE;
    }
    jfloat values[16];
    env->GetFloatArrayRegion(matrix, 0, 16, values);
    if (env->ExceptionCheck()) {
        return JNI_FALSE;
    }
    bool gpuSynchronizationUsed = false;
    if (!renderInteropFrame(g_interopRenderer, values, cameraX, cameraY, cameraZ, renderDistance,
            static_cast<UINT64>(frameIndex), useGpuSynchronization == JNI_TRUE, env,
            &gpuSynchronizationUsed)) {
        return JNI_FALSE;
    }
    return gpuSynchronizationUsed ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_waitInteropFence(JNIEnv* env, jclass, jlong value) {
    if (g_interopRenderer == nullptr || value < 0
            || !waitForInteropFence(g_interopRenderer, static_cast<UINT64>(value))) {
        throwJava(env, "DX12/OpenGL interop could not wait for the requested fence value.");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_signalInteropFence(JNIEnv* env, jclass, jlong value) {
    if (g_interopRenderer == nullptr || value < 0
            || FAILED(g_interopRenderer->glDoneFence->Signal(static_cast<UINT64>(value)))) {
        throwJava(env, "DX12/OpenGL interop could not CPU-signal the shared fence fallback.");
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_redondoguibi_direct_NativeDx12_hasInteropGpuSyncFault(JNIEnv*, jclass) {
    return g_interopRenderer != nullptr && g_interopRenderer->gpuSyncFault.load()
            ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_updateTerrainSection(JNIEnv* env, jclass, jlong key,
        jint sectionX, jint sectionY, jint sectionZ, jobject vertexData, jint vertexCount,
        jobject indexData, jint indexCount) {
    InteropRenderer* renderer = g_interopRenderer;
    if (renderer == nullptr || renderer->terrainSections == nullptr) {
        return;
    }
    if (vertexCount <= 0 || indexCount <= 0 || vertexData == nullptr || indexData == nullptr) {
        throwJava(env, "DX12 terrain section data must contain vertices and indices.");
        return;
    }
    const jlong vertexCapacity = env->GetDirectBufferCapacity(vertexData);
    const jlong indexCapacity = env->GetDirectBufferCapacity(indexData);
    const void* vertices = env->GetDirectBufferAddress(vertexData);
    const void* indices = env->GetDirectBufferAddress(indexData);
    const jlong vertexBytes = static_cast<jlong>(vertexCount) * 24;
    const jlong indexBytes = static_cast<jlong>(indexCount) * sizeof(UINT32);
    if (vertices == nullptr || indices == nullptr || vertexCapacity < vertexBytes || indexCapacity < indexBytes
            || vertexBytes > std::numeric_limits<UINT>::max()
            || indexBytes > std::numeric_limits<UINT>::max()) {
        throwJava(env, "DX12 terrain section buffers have invalid direct-buffer sizes.");
        return;
    }

    D3D11_BUFFER_DESC vertexDescription = {};
    vertexDescription.ByteWidth = static_cast<UINT>(vertexBytes);
    vertexDescription.Usage = D3D11_USAGE_DEFAULT;
    vertexDescription.BindFlags = D3D11_BIND_VERTEX_BUFFER;
    D3D11_SUBRESOURCE_DATA vertexInitialData = {};
    vertexInitialData.pSysMem = vertices;
    TerrainSection* section = new TerrainSection{};
    if (FAILED(renderer->d3d11Device->CreateBuffer(&vertexDescription, &vertexInitialData,
            &section->vertexBuffer))) {
        delete section;
        throwJava(env, "DX12 terrain vertex buffer creation failed.");
        return;
    }

    D3D11_BUFFER_DESC indexDescription = {};
    indexDescription.ByteWidth = static_cast<UINT>(indexBytes);
    indexDescription.Usage = D3D11_USAGE_DEFAULT;
    indexDescription.BindFlags = D3D11_BIND_INDEX_BUFFER;
    D3D11_SUBRESOURCE_DATA indexInitialData = {};
    indexInitialData.pSysMem = indices;
    if (FAILED(renderer->d3d11Device->CreateBuffer(&indexDescription, &indexInitialData,
            &section->indexBuffer))) {
        release(section->vertexBuffer);
        delete section;
        throwJava(env, "DX12 terrain index buffer creation failed.");
        return;
    }
    section->x = sectionX;
    section->y = sectionY;
    section->z = sectionZ;
    section->indexCount = static_cast<UINT>(indexCount);
    auto existing = renderer->terrainSections->find(key);
    if (existing != renderer->terrainSections->end()) {
        release(existing->second->vertexBuffer);
        release(existing->second->indexBuffer);
        delete existing->second;
        existing->second = section;
    } else {
        renderer->terrainSections->emplace(key, section);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_updateTerrainAtlas(JNIEnv* env, jclass, jobject pixelData,
        jint width, jint height) {
    InteropRenderer* renderer = g_interopRenderer;
    if (renderer == nullptr) {
        throwJava(env, "DX12 terrain atlas upload requires an initialized renderer.");
        return;
    }
    if (pixelData == nullptr || width <= 0 || height <= 0
            || static_cast<uint64_t>(width) * 4 > std::numeric_limits<UINT>::max()) {
        throwJava(env, "DX12 terrain atlas dimensions or pixel buffer are invalid.");
        return;
    }
    const uint64_t rowPitch = static_cast<uint64_t>(width) * 4;
    const uint64_t byteCount = rowPitch * static_cast<uint64_t>(height);
    const jlong capacity = env->GetDirectBufferCapacity(pixelData);
    const void* pixels = env->GetDirectBufferAddress(pixelData);
    if (pixels == nullptr || capacity < 0 || byteCount > static_cast<uint64_t>(capacity)) {
        throwJava(env, "DX12 terrain atlas requires a sufficiently large direct pixel buffer.");
        return;
    }

    D3D11_TEXTURE2D_DESC description = {};
    description.Width = static_cast<UINT>(width);
    description.Height = static_cast<UINT>(height);
    description.MipLevels = 1;
    description.ArraySize = 1;
    description.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    description.SampleDesc.Count = 1;
    description.Usage = D3D11_USAGE_DEFAULT;
    description.BindFlags = D3D11_BIND_SHADER_RESOURCE;
    D3D11_SUBRESOURCE_DATA initialData = {};
    initialData.pSysMem = pixels;
    initialData.SysMemPitch = static_cast<UINT>(rowPitch);
    ID3D11Texture2D* atlas = nullptr;
    ID3D11ShaderResourceView* atlasView = nullptr;
    HRESULT result = renderer->d3d11Device->CreateTexture2D(&description, &initialData, &atlas);
    if (SUCCEEDED(result)) {
        result = renderer->d3d11Device->CreateShaderResourceView(atlas, nullptr, &atlasView);
    }
    if (FAILED(result)) {
        release(atlasView);
        release(atlas);
        throwJava(env, "DX12 terrain atlas texture upload failed.");
        return;
    }
    release(renderer->terrainAtlasView);
    release(renderer->terrainAtlas);
    renderer->terrainAtlas = atlas;
    renderer->terrainAtlasView = atlasView;
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_removeTerrainSection(JNIEnv*, jclass, jlong key) {
    if (g_interopRenderer == nullptr || g_interopRenderer->terrainSections == nullptr) {
        return;
    }
    auto existing = g_interopRenderer->terrainSections->find(key);
    if (existing == g_interopRenderer->terrainSections->end()) {
        return;
    }
    release(existing->second->vertexBuffer);
    release(existing->second->indexBuffer);
    delete existing->second;
    g_interopRenderer->terrainSections->erase(existing);
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_clearTerrain(JNIEnv*, jclass) {
    if (g_interopRenderer != nullptr) {
        releaseTerrainSections(g_interopRenderer);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_shutdownInterop(JNIEnv*, jclass) {
    destroyInteropRenderer(g_interopRenderer);
    g_interopRenderer = nullptr;
}
