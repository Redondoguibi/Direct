#include <jni.h>
#include <windows.h>
#include <d3d11.h>
#include <d3d11on12.h>
#include <d3d12.h>
#include <d3dcompiler.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <limits>
#include <mutex>
#include <system_error>
#include <thread>
#include <unordered_map>

namespace {

constexpr UINT64 kFenceError = std::numeric_limits<UINT64>::max();
constexpr DWORD kFenceWaitTimeoutMs = 5000;
constexpr UINT kVertexStride = 24;
constexpr UINT kLayerCount = 3;
constexpr jsize kMaxAtlasLevels = 16;
constexpr float kSectionRadius = 13.8564f; // sqrt(3) * 8
constexpr float kClearColor[4] = {0.0f, 0.0f, 0.0f, 0.0f};
// Ordem das camadas: solid, cutout_mipped, cutout (confirmar nos .fsh vanilla).
constexpr float kLayerAlphaCutoff[kLayerCount] = {-1.0f, 0.5f, 0.1f};

constexpr char kTerrainVertexShader[] = R"(
cbuffer SectionConstants : register(b0) {
    float4x4 viewProjection;
    float4 sectionOffset;
};
struct VertexInput { float3 position : POSITION; float4 color : COLOR; float2 uv : TEXCOORD0; };
struct VertexOutput { float4 position : SV_Position; float4 color : COLOR; float2 uv : TEXCOORD0; };
VertexOutput main(VertexInput input) {
    VertexOutput output;
    output.position = mul(viewProjection, float4(input.position + sectionOffset.xyz, 1.0));
    output.color = input.color;
    output.uv = input.uv;
    return output;
}
)";

constexpr char kTerrainPixelShader[] = R"(
cbuffer LayerConstants : register(b1) { float4 layer; };
Texture2D terrainAtlas : register(t0);
SamplerState terrainAtlasSampler : register(s0);
float4 main(float4 position : SV_Position, float4 color : COLOR, float2 uv : TEXCOORD0) : SV_Target {
    float4 result = terrainAtlas.Sample(terrainAtlasSampler, uv) * color;
    clip(result.a - layer.x);
    return float4(result.rgb, 1.0);
}
)";

struct TerrainSection {
    int x;
    int y;
    int z;
    UINT layerIndexCounts[kLayerCount];
    ID3D11Buffer* vertexBuffer;
    ID3D11Buffer* indexBuffer;
};

struct SectionConstants {
    float viewProjection[16];
    float sectionOffset[4];
};

struct Plane {
    float a, b, c, d;
};

struct InteropRenderer {
    UINT width;
    UINT height;
    UINT64 lastSignaledValue;
    UINT64 lastQueuedGlDoneValue;
    ID3D12Device* device;
    ID3D12CommandQueue* queue;
    ID3D12Fence* fence;
    ID3D12Fence* glDoneFence;
    HANDLE fenceEvent;
    HANDLE watchdogEvent;
    HANDLE sharedFenceHandle;
    HANDLE sharedGlDoneFenceHandle;
    std::mutex watchdogMutex;
    std::condition_variable watchdogCondition;
    std::thread watchdogThread;
    UINT64 watchedFenceValue;
    bool stopWatchdog;
    std::atomic<bool> gpuSyncFault;
    ID3D11Device* d3d11Device;
    ID3D11DeviceContext* d3d11Context;
    ID3D11Texture2D* interopTexture;
    ID3D11RenderTargetView* renderTarget;
    ID3D11Texture2D* depthTexture;
    ID3D11DepthStencilView* depthView;
    ID3D11RasterizerState* rasterizerState;
    ID3D11DepthStencilState* depthState;
    ID3D11VertexShader* vertexShader;
    ID3D11PixelShader* pixelShader;
    ID3D11InputLayout* inputLayout;
    ID3D11Buffer* sectionConstants;
    ID3D11Buffer* layerConstants[kLayerCount];
    ID3D11Texture2D* atlas;
    ID3D11ShaderResourceView* atlasView;
    ID3D11SamplerState* atlasSampler;
    std::unordered_map<jlong, TerrainSection*> sections;
};

InteropRenderer* g_interopRenderer;

void throwJava(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) {
        return;
    }
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

void closeHandle(HANDLE& handle) {
    if (handle != nullptr) {
        CloseHandle(handle);
        handle = nullptr;
    }
}

bool createSharedFence(ID3D12Device* device, ID3D12Fence** fence, HANDLE* sharedHandle) {
    *sharedHandle = nullptr;
    if (SUCCEEDED(device->CreateFence(0, D3D12_FENCE_FLAG_SHARED, IID_PPV_ARGS(fence)))) {
        if (FAILED(device->CreateSharedHandle(*fence, nullptr, GENERIC_ALL, nullptr, sharedHandle))) {
            *sharedHandle = nullptr;
        }
        return true;
    }
    return SUCCEEDED(device->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(fence)));
}

// Espera com timeout. NUNCA sinaliza pela CPU: um timeout é tratado como falha.
bool waitFence(InteropRenderer* renderer, ID3D12Fence* fence, UINT64 value) {
    UINT64 completed = fence->GetCompletedValue();
    if (completed == kFenceError) {
        return false;
    }
    if (value == 0 || completed >= value) {
        return true;
    }
    if (FAILED(fence->SetEventOnCompletion(value, renderer->fenceEvent))) {
        return false;
    }
    if (WaitForSingleObject(renderer->fenceEvent, kFenceWaitTimeoutMs) != WAIT_OBJECT_0) {
        renderer->gpuSyncFault.store(true);
        return false;
    }
    completed = fence->GetCompletedValue();
    return completed != kFenceError && completed >= value;
}

// Só detecta problema. Prazo por valor alvo; sem Signal() pela CPU.
void fenceWatchdog(InteropRenderer* renderer) {
    std::unique_lock<std::mutex> lock(renderer->watchdogMutex);
    for (;;) {
        renderer->watchdogCondition.wait(lock, [renderer] {
            return renderer->stopWatchdog || renderer->watchedFenceValue != 0;
        });
        if (renderer->stopWatchdog) {
            return;
        }
        const UINT64 target = renderer->watchedFenceValue;
        const auto deadline = std::chrono::steady_clock::now()
                + std::chrono::milliseconds(kFenceWaitTimeoutMs);
        lock.unlock();

        bool completed = false;
        bool failed = FAILED(renderer->fence->SetEventOnCompletion(target, renderer->watchdogEvent));
        while (!failed) {
            const UINT64 value = renderer->fence->GetCompletedValue();
            if (value == kFenceError) {
                failed = true;
                break;
            }
            if (value >= target) {
                completed = true;
                break;
            }
            const auto now = std::chrono::steady_clock::now();
            if (now >= deadline) {
                break;
            }
            const auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - now);
            if (WaitForSingleObject(renderer->watchdogEvent,
                    static_cast<DWORD>(remaining.count()) + 1) == WAIT_FAILED) {
                failed = true;
                break;
            }
            std::lock_guard<std::mutex> stopCheck(renderer->watchdogMutex);
            if (renderer->stopWatchdog) {
                return;
            }
        }

        lock.lock();
        if (renderer->stopWatchdog) {
            return;
        }
        if (!completed) {
            renderer->gpuSyncFault.store(true);
        }
        if (renderer->watchedFenceValue == target) {
            renderer->watchedFenceValue = 0;
        }
    }
}

void deleteSection(TerrainSection* section) {
    release(section->vertexBuffer);
    release(section->indexBuffer);
    delete section;
}

void releaseTerrainSections(InteropRenderer* renderer) {
    for (auto& entry : renderer->sections) {
        deleteSection(entry.second);
    }
    renderer->sections.clear();
}

void releaseSizeResources(InteropRenderer* renderer) {
    if (renderer->d3d11Context != nullptr) {
        renderer->d3d11Context->OMSetRenderTargets(0, nullptr, nullptr);
    }
    release(renderer->depthView);
    release(renderer->depthTexture);
    release(renderer->renderTarget);
    release(renderer->interopTexture);
}

bool createSizeResources(InteropRenderer* renderer, JNIEnv* env) {
    D3D11_TEXTURE2D_DESC color = {};
    color.Width = renderer->width;
    color.Height = renderer->height;
    color.MipLevels = 1;
    color.ArraySize = 1;
    color.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    color.SampleDesc.Count = 1;
    color.Usage = D3D11_USAGE_DEFAULT;
    color.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    if (FAILED(renderer->d3d11Device->CreateTexture2D(&color, nullptr, &renderer->interopTexture))
            || FAILED(renderer->d3d11Device->CreateRenderTargetView(renderer->interopTexture, nullptr,
                    &renderer->renderTarget))) {
        throwJava(env, "DX12/OpenGL interop could not create the shared color target.");
        return false;
    }
    D3D11_TEXTURE2D_DESC depth = color;
    depth.Format = DXGI_FORMAT_D24_UNORM_S8_UINT;
    depth.BindFlags = D3D11_BIND_DEPTH_STENCIL;
    if (FAILED(renderer->d3d11Device->CreateTexture2D(&depth, nullptr, &renderer->depthTexture))
            || FAILED(renderer->d3d11Device->CreateDepthStencilView(renderer->depthTexture, nullptr,
                    &renderer->depthView))) {
        throwJava(env, "DX12 terrain depth buffer creation failed.");
        return false;
    }
    return true;
}

void destroyInteropRenderer(InteropRenderer* renderer) {
    if (renderer == nullptr) {
        return;
    }
    // Apenas no shutdown: o Java já chamou glFinish, então o GL nunca mais sinalizará.
    // Libera uma eventual Wait() pendente na fila para não travar o teardown.
    if (renderer->glDoneFence != nullptr && renderer->lastQueuedGlDoneValue > 0) {
        const UINT64 completed = renderer->glDoneFence->GetCompletedValue();
        if (completed != kFenceError && completed < renderer->lastQueuedGlDoneValue) {
            renderer->glDoneFence->Signal(renderer->lastQueuedGlDoneValue);
        }
    }
    {
        std::lock_guard<std::mutex> lock(renderer->watchdogMutex);
        renderer->stopWatchdog = true;
    }
    renderer->watchdogCondition.notify_all();
    if (renderer->watchdogEvent != nullptr) {
        SetEvent(renderer->watchdogEvent);
    }
    if (renderer->watchdogThread.joinable()) {
        renderer->watchdogThread.join();
    }
    if (renderer->d3d11Context != nullptr) {
        renderer->d3d11Context->ClearState();
        renderer->d3d11Context->Flush();
    }
    if (renderer->queue != nullptr && renderer->fence != nullptr && renderer->fenceEvent != nullptr) {
        const UINT64 completed = renderer->fence->GetCompletedValue();
        if (completed != kFenceError) {
            const UINT64 value = (completed > renderer->lastSignaledValue
                    ? completed : renderer->lastSignaledValue) + 1;
            if (SUCCEEDED(renderer->queue->Signal(renderer->fence, value))
                    && SUCCEEDED(renderer->fence->SetEventOnCompletion(value, renderer->fenceEvent))) {
                WaitForSingleObject(renderer->fenceEvent, kFenceWaitTimeoutMs);
            }
        }
    }
    releaseTerrainSections(renderer);
    releaseSizeResources(renderer);
    for (UINT layer = 0; layer < kLayerCount; ++layer) {
        release(renderer->layerConstants[layer]);
    }
    release(renderer->sectionConstants);
    release(renderer->inputLayout);
    release(renderer->vertexShader);
    release(renderer->pixelShader);
    release(renderer->rasterizerState);
    release(renderer->depthState);
    release(renderer->atlasSampler);
    release(renderer->atlasView);
    release(renderer->atlas);
    release(renderer->d3d11Context);
    release(renderer->d3d11Device);
    closeHandle(renderer->sharedGlDoneFenceHandle);
    closeHandle(renderer->sharedFenceHandle);
    release(renderer->glDoneFence);
    release(renderer->fence);
    release(renderer->queue);
    release(renderer->device);
    closeHandle(renderer->watchdogEvent);
    closeHandle(renderer->fenceEvent);
    delete renderer;
}

bool compileShader(const char* source, SIZE_T length, const char* target, ID3DBlob** bytecode) {
    ID3DBlob* errors = nullptr;
    const HRESULT result = D3DCompile(source, length, nullptr, nullptr, nullptr, "main", target,
            D3DCOMPILE_ENABLE_STRICTNESS, 0, bytecode, &errors);
    if (errors != nullptr) {
        OutputDebugStringA(static_cast<const char*>(errors->GetBufferPointer()));
    }
    release(errors);
    return SUCCEEDED(result);
}

bool createTerrainPipeline(InteropRenderer* renderer, JNIEnv* env) {
    ID3DBlob* vertexBytecode = nullptr;
    if (!compileShader(kTerrainVertexShader, sizeof(kTerrainVertexShader) - 1, "vs_4_0", &vertexBytecode)) {
        throwJava(env, "DX12 terrain vertex shader compilation failed.");
        return false;
    }
    const D3D11_INPUT_ELEMENT_DESC elements[] = {
        {"POSITION", 0, DXGI_FORMAT_R32G32B32_FLOAT, 0, 0, D3D11_INPUT_PER_VERTEX_DATA, 0},
        {"COLOR", 0, DXGI_FORMAT_R8G8B8A8_UNORM, 0, 12, D3D11_INPUT_PER_VERTEX_DATA, 0},
        {"TEXCOORD", 0, DXGI_FORMAT_R32G32_FLOAT, 0, 16, D3D11_INPUT_PER_VERTEX_DATA, 0}
    };
    HRESULT result = renderer->d3d11Device->CreateVertexShader(vertexBytecode->GetBufferPointer(),
            vertexBytecode->GetBufferSize(), nullptr, &renderer->vertexShader);
    if (SUCCEEDED(result)) {
        result = renderer->d3d11Device->CreateInputLayout(elements, ARRAYSIZE(elements),
                vertexBytecode->GetBufferPointer(), vertexBytecode->GetBufferSize(), &renderer->inputLayout);
    }
    release(vertexBytecode);
    if (FAILED(result)) {
        throwJava(env, "DX12 terrain vertex shader or input layout creation failed.");
        return false;
    }

    ID3DBlob* pixelBytecode = nullptr;
    if (!compileShader(kTerrainPixelShader, sizeof(kTerrainPixelShader) - 1, "ps_4_0", &pixelBytecode)) {
        throwJava(env, "DX12 terrain pixel shader compilation failed.");
        return false;
    }
    result = renderer->d3d11Device->CreatePixelShader(pixelBytecode->GetBufferPointer(),
            pixelBytecode->GetBufferSize(), nullptr, &renderer->pixelShader);
    release(pixelBytecode);
    if (FAILED(result)) {
        throwJava(env, "DX12 terrain pixel shader creation failed.");
        return false;
    }

    D3D11_BUFFER_DESC constants = {};
    constants.ByteWidth = sizeof(SectionConstants);
    constants.Usage = D3D11_USAGE_DYNAMIC;
    constants.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
    constants.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
    if (FAILED(renderer->d3d11Device->CreateBuffer(&constants, nullptr, &renderer->sectionConstants))) {
        throwJava(env, "DX12 terrain section constant buffer creation failed.");
        return false;
    }
    D3D11_BUFFER_DESC layerDescription = {};
    layerDescription.ByteWidth = 16;
    layerDescription.Usage = D3D11_USAGE_IMMUTABLE;
    layerDescription.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
    for (UINT layer = 0; layer < kLayerCount; ++layer) {
        const float values[4] = {kLayerAlphaCutoff[layer], 0.0f, 0.0f, 0.0f};
        D3D11_SUBRESOURCE_DATA data = {values, 0, 0};
        if (FAILED(renderer->d3d11Device->CreateBuffer(&layerDescription, &data,
                &renderer->layerConstants[layer]))) {
            throwJava(env, "DX12 terrain layer constant buffer creation failed.");
            return false;
        }
    }

    // GL usa CCW como frente; o flip em Y da matriz de correção transforma isso em CW no D3D.
    D3D11_RASTERIZER_DESC rasterizer = {};
    rasterizer.FillMode = D3D11_FILL_SOLID;
    rasterizer.CullMode = D3D11_CULL_BACK;
    rasterizer.FrontCounterClockwise = FALSE;
    rasterizer.DepthClipEnable = TRUE;
    D3D11_DEPTH_STENCIL_DESC depth = {};
    depth.DepthEnable = TRUE;
    depth.DepthWriteMask = D3D11_DEPTH_WRITE_MASK_ALL;
    depth.DepthFunc = D3D11_COMPARISON_LESS_EQUAL;
    D3D11_SAMPLER_DESC sampler = {};
    sampler.Filter = D3D11_FILTER_MIN_MAG_POINT_MIP_LINEAR;
    sampler.AddressU = D3D11_TEXTURE_ADDRESS_CLAMP;
    sampler.AddressV = D3D11_TEXTURE_ADDRESS_CLAMP;
    sampler.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
    sampler.ComparisonFunc = D3D11_COMPARISON_NEVER;
    sampler.MaxLOD = D3D11_FLOAT32_MAX;
    if (FAILED(renderer->d3d11Device->CreateRasterizerState(&rasterizer, &renderer->rasterizerState))
            || FAILED(renderer->d3d11Device->CreateDepthStencilState(&depth, &renderer->depthState))
            || FAILED(renderer->d3d11Device->CreateSamplerState(&sampler, &renderer->atlasSampler))) {
        throwJava(env, "DX12 terrain fixed-function state creation failed.");
        return false;
    }
    return true;
}

bool initializeInteropRenderer(InteropRenderer* renderer, jint width, jint height, JNIEnv* env) {
    if (width <= 0 || height <= 0) {
        throwJava(env, "DX12/OpenGL interop requires a non-zero framebuffer size.");
        return false;
    }
    renderer->width = static_cast<UINT>(width);
    renderer->height = static_cast<UINT>(height);
    if (FAILED(D3D12CreateDevice(nullptr, D3D_FEATURE_LEVEL_11_0, IID_PPV_ARGS(&renderer->device)))) {
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
    if (FAILED(D3D11On12CreateDevice(renderer->device, D3D11_CREATE_DEVICE_BGRA_SUPPORT,
            featureLevels, ARRAYSIZE(featureLevels), queues, ARRAYSIZE(queues), 0,
            &renderer->d3d11Device, &renderer->d3d11Context, nullptr))) {
        throwJava(env, "DX12/OpenGL interop could not create a D3D11On12 device.");
        return false;
    }
    if (!createSizeResources(renderer, env) || !createTerrainPipeline(renderer, env)) {
        return false;
    }
    if (!createSharedFence(renderer->device, &renderer->fence, &renderer->sharedFenceHandle)
            || !createSharedFence(renderer->device, &renderer->glDoneFence, &renderer->sharedGlDoneFenceHandle)) {
        throwJava(env, "DX12/OpenGL interop could not create a Direct3D 12 fence.");
        return false;
    }
    renderer->fenceEvent = CreateEvent(nullptr, FALSE, FALSE, nullptr);
    renderer->watchdogEvent = CreateEvent(nullptr, FALSE, FALSE, nullptr);
    if (renderer->fenceEvent == nullptr || renderer->watchdogEvent == nullptr) {
        throwJava(env, "DX12/OpenGL interop could not create fence events.");
        return false;
    }
    if (renderer->sharedFenceHandle != nullptr && renderer->sharedGlDoneFenceHandle != nullptr) {
        try {
            renderer->watchdogThread = std::thread(fenceWatchdog, renderer);
        } catch (const std::system_error&) {
            closeHandle(renderer->sharedFenceHandle);
            closeHandle(renderer->sharedGlDoneFenceHandle);
        }
    }
    return true;
}

void extractFrustum(const float* m, Plane* planes) {
    auto at = [m](int row, int column) { return m[column * 4 + row]; };
    for (int index = 0; index < 6; ++index) {
        const int axis = index / 2;
        const float sign = index % 2 == 0 ? 1.0f : -1.0f;
        Plane plane;
        if (axis == 2 && sign > 0.0f) { // near: z >= 0 (clip D3D)
            plane = {at(2, 0), at(2, 1), at(2, 2), at(2, 3)};
        } else {
            plane = {at(3, 0) + sign * at(axis, 0), at(3, 1) + sign * at(axis, 1),
                    at(3, 2) + sign * at(axis, 2), at(3, 3) + sign * at(axis, 3)};
        }
        const float length = std::sqrt(plane.a * plane.a + plane.b * plane.b + plane.c * plane.c);
        if (length > 0.0f) {
            plane = {plane.a / length, plane.b / length, plane.c / length, plane.d / length};
        }
        planes[index] = plane;
    }
}

bool sphereVisible(const Plane* planes, float x, float y, float z, float radius) {
    for (int index = 0; index < 6; ++index) {
        const Plane& p = planes[index];
        if (p.a * x + p.b * y + p.c * z + p.d < -radius) {
            return false;
        }
    }
    return true;
}

bool renderInteropFrame(InteropRenderer* renderer, const float* viewProjection,
        double cameraX, double cameraY, double cameraZ, float renderDistance,
        UINT64 frameIndex, bool useGpuSynchronization, JNIEnv* env, bool* gpuSynchronizationUsed) {
    if (frameIndex >= kFenceError - 1) {
        throwJava(env, "DX12/OpenGL interop fence timeline has exhausted its frame values.");
        return false;
    }
    const UINT64 glDoneValue = frameIndex;
    const UINT64 dxReadyValue = frameIndex + 1;
    bool gpu = useGpuSynchronization && renderer->sharedFenceHandle != nullptr
            && renderer->sharedGlDoneFenceHandle != nullptr && !renderer->gpuSyncFault.load();
    if (gpu && glDoneValue > 0) {
        if (FAILED(renderer->queue->Wait(renderer->glDoneFence, glDoneValue))) {
            if (!waitFence(renderer, renderer->glDoneFence, glDoneValue)) {
                throwJava(env, "DX12/OpenGL interop timed out waiting for OpenGL to release the frame.");
                return false;
            }
            gpu = false;
        } else {
            renderer->lastQueuedGlDoneValue = glDoneValue;
        }
    }

    ID3D11DeviceContext* context = renderer->d3d11Context;
    context->ClearRenderTargetView(renderer->renderTarget, kClearColor);
    context->ClearDepthStencilView(renderer->depthView, D3D11_CLEAR_DEPTH | D3D11_CLEAR_STENCIL, 1.0f, 0);

    if (renderer->atlasView != nullptr && !renderer->sections.empty()) {
        const float blendFactor[4] = {0.0f, 0.0f, 0.0f, 0.0f};
        context->OMSetRenderTargets(1, &renderer->renderTarget, renderer->depthView);
        context->OMSetBlendState(nullptr, blendFactor, 0xffffffff);
        context->OMSetDepthStencilState(renderer->depthState, 0);
        context->RSSetState(renderer->rasterizerState);
        const D3D11_VIEWPORT viewport = {0.0f, 0.0f, static_cast<float>(renderer->width),
                static_cast<float>(renderer->height), 0.0f, 1.0f};
        context->RSSetViewports(1, &viewport);
        context->IASetInputLayout(renderer->inputLayout);
        context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
        context->VSSetShader(renderer->vertexShader, nullptr, 0);
        context->PSSetShader(renderer->pixelShader, nullptr, 0);
        context->VSSetConstantBuffers(0, 1, &renderer->sectionConstants);
        context->PSSetShaderResources(0, 1, &renderer->atlasView);
        context->PSSetSamplers(0, 1, &renderer->atlasSampler);

        Plane frustum[6];
        extractFrustum(viewProjection, frustum);
        const double maxDistanceSquared = static_cast<double>(renderDistance) * renderDistance;
        SectionConstants constants;
        std::memcpy(constants.viewProjection, viewProjection, sizeof(constants.viewProjection));
        constants.sectionOffset[3] = 0.0f;

        for (const auto& entry : renderer->sections) {
            const TerrainSection* section = entry.second;
            const double ox = section->x - cameraX;
            const double oy = section->y - cameraY;
            const double oz = section->z - cameraZ;
            const double cx = ox + 8.0;
            const double cy = oy + 8.0;
            const double cz = oz + 8.0;
            if (cx * cx + cy * cy + cz * cz > maxDistanceSquared
                    || !sphereVisible(frustum, static_cast<float>(cx), static_cast<float>(cy),
                            static_cast<float>(cz), kSectionRadius)) {
                continue;
            }
            constants.sectionOffset[0] = static_cast<float>(ox);
            constants.sectionOffset[1] = static_cast<float>(oy);
            constants.sectionOffset[2] = static_cast<float>(oz);
            D3D11_MAPPED_SUBRESOURCE mapped = {};
            if (FAILED(context->Map(renderer->sectionConstants, 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped))) {
                throwJava(env, "DX12 terrain section constants could not be mapped.");
                return false;
            }
            std::memcpy(mapped.pData, &constants, sizeof(constants));
            context->Unmap(renderer->sectionConstants, 0);

            const UINT stride = kVertexStride;
            const UINT offset = 0;
            context->IASetVertexBuffers(0, 1, &section->vertexBuffer, &stride, &offset);
            context->IASetIndexBuffer(section->indexBuffer, DXGI_FORMAT_R32_UINT, 0);
            UINT startIndex = 0;
            for (UINT layer = 0; layer < kLayerCount; ++layer) {
                const UINT count = section->layerIndexCounts[layer];
                if (count == 0) {
                    continue;
                }
                context->PSSetConstantBuffers(1, 1, &renderer->layerConstants[layer]);
                context->DrawIndexed(count, startIndex, 0);
                startIndex += count;
            }
        }
        context->OMSetRenderTargets(0, nullptr, nullptr);
    }
    context->Flush();

    HRESULT signalResult;
    {
        std::lock_guard<std::mutex> lock(renderer->watchdogMutex);
        signalResult = renderer->queue->Signal(renderer->fence, dxReadyValue);
        if (SUCCEEDED(signalResult)) {
            renderer->lastSignaledValue = dxReadyValue;
            if (gpu) {
                renderer->watchedFenceValue = dxReadyValue;
            }
        }
    }
    if (FAILED(signalResult)) {
        throwJava(env, "DX12/OpenGL interop failed to signal its frame fence.");
        return false;
    }
    if (gpu) {
        renderer->watchdogCondition.notify_one();
    } else if (!waitFence(renderer, renderer->fence, dxReadyValue)) {
        throwJava(env, "DX12/OpenGL interop timed out waiting for its frame fence.");
        return false;
    }
    *gpuSynchronizationUsed = gpu;
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
    const jlong handles[] = {
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

// Retorna o novo ponteiro da textura D3D11. O Java deve desregistrar o objeto WGL antes.
extern "C" JNIEXPORT jlong JNICALL
Java_com_redondoguibi_direct_NativeDx12_resizeInterop(JNIEnv* env, jclass, jint width, jint height) {
    InteropRenderer* renderer = g_interopRenderer;
    if (renderer == nullptr || width <= 0 || height <= 0) {
        throwJava(env, "DX12/OpenGL interop resize received an invalid state or size.");
        return 0;
    }
    renderer->d3d11Context->Flush();
    if (!waitFence(renderer, renderer->fence, renderer->lastSignaledValue)) {
        throwJava(env, "DX12/OpenGL interop timed out waiting for the GPU before resizing.");
        return 0;
    }
    releaseSizeResources(renderer);
    renderer->width = static_cast<UINT>(width);
    renderer->height = static_cast<UINT>(height);
    if (!createSizeResources(renderer, env)) {
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(renderer->interopTexture));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_redondoguibi_direct_NativeDx12_renderInterop(JNIEnv* env, jclass, jfloatArray matrix,
        jdouble cameraX, jdouble cameraY, jdouble cameraZ, jfloat renderDistance,
        jlong frameIndex, jboolean useGpuSynchronization) {
    if (g_interopRenderer == nullptr) {
        throwJava(env, "DX12/OpenGL interop is not initialized.");
        return JNI_FALSE;
    }
    if (matrix == nullptr || env->GetArrayLength(matrix) != 16 || frameIndex < 0) {
        throwJava(env, "DX12 terrain rendering received an invalid matrix or frame index.");
        return JNI_FALSE;
    }
    jfloat values[16];
    env->GetFloatArrayRegion(matrix, 0, 16, values);
    if (env->ExceptionCheck()) {
        return JNI_FALSE;
    }
    bool gpuUsed = false;
    if (!renderInteropFrame(g_interopRenderer, values, cameraX, cameraY, cameraZ, renderDistance,
            static_cast<UINT64>(frameIndex), useGpuSynchronization == JNI_TRUE, env, &gpuUsed)) {
        return JNI_FALSE;
    }
    return gpuUsed ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_waitInteropFence(JNIEnv* env, jclass, jlong value) {
    if (g_interopRenderer == nullptr || value < 0
            || !waitFence(g_interopRenderer, g_interopRenderer->fence, static_cast<UINT64>(value))) {
        throwJava(env, "DX12/OpenGL interop could not wait for the requested fence value.");
    }
}

// Caminho de fallback pela CPU: só é chamado depois de glFinish(), então o GL realmente terminou.
extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_signalInteropFence(JNIEnv* env, jclass, jlong value) {
    if (g_interopRenderer == nullptr || value < 0
            || FAILED(g_interopRenderer->glDoneFence->Signal(static_cast<UINT64>(value)))) {
        throwJava(env, "DX12/OpenGL interop could not CPU-signal the OpenGL completion fence.");
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_redondoguibi_direct_NativeDx12_hasInteropGpuSyncFault(JNIEnv*, jclass) {
    return g_interopRenderer != nullptr && g_interopRenderer->gpuSyncFault.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_updateTerrainSection(JNIEnv* env, jclass, jlong key,
        jint sectionX, jint sectionY, jint sectionZ, jobject vertexData, jint vertexCount,
        jobject indexData, jintArray layerIndexCounts) {
    InteropRenderer* renderer = g_interopRenderer;
    if (renderer == nullptr) {
        return;
    }
    if (vertexData == nullptr || indexData == nullptr || vertexCount <= 0 || layerIndexCounts == nullptr
            || env->GetArrayLength(layerIndexCounts) != static_cast<jsize>(kLayerCount)) {
        throwJava(env, "DX12 terrain section data is incomplete.");
        return;
    }
    jint counts[kLayerCount];
    env->GetIntArrayRegion(layerIndexCounts, 0, kLayerCount, counts);
    if (env->ExceptionCheck()) {
        return;
    }
    jlong indexCount = 0;
    for (UINT layer = 0; layer < kLayerCount; ++layer) {
        if (counts[layer] < 0) {
            throwJava(env, "DX12 terrain layer index count is negative.");
            return;
        }
        indexCount += counts[layer];
    }
    const jlong vertexBytes = static_cast<jlong>(vertexCount) * kVertexStride;
    const jlong indexBytes = indexCount * static_cast<jlong>(sizeof(UINT32));
    const void* vertices = env->GetDirectBufferAddress(vertexData);
    const void* indices = env->GetDirectBufferAddress(indexData);
    if (indexCount == 0 || vertices == nullptr || indices == nullptr
            || env->GetDirectBufferCapacity(vertexData) < vertexBytes
            || env->GetDirectBufferCapacity(indexData) < indexBytes
            || vertexBytes > std::numeric_limits<UINT>::max()
            || indexBytes > std::numeric_limits<UINT>::max()) {
        throwJava(env, "DX12 terrain section buffers have invalid direct-buffer sizes.");
        return;
    }

    TerrainSection* section = new TerrainSection{};
    D3D11_BUFFER_DESC description = {};
    description.Usage = D3D11_USAGE_IMMUTABLE;
    description.ByteWidth = static_cast<UINT>(vertexBytes);
    description.BindFlags = D3D11_BIND_VERTEX_BUFFER;
    D3D11_SUBRESOURCE_DATA vertexInitial = {vertices, 0, 0};
    HRESULT result = renderer->d3d11Device->CreateBuffer(&description, &vertexInitial, &section->vertexBuffer);
    if (SUCCEEDED(result)) {
        description.ByteWidth = static_cast<UINT>(indexBytes);
        description.BindFlags = D3D11_BIND_INDEX_BUFFER;
        D3D11_SUBRESOURCE_DATA indexInitial = {indices, 0, 0};
        result = renderer->d3d11Device->CreateBuffer(&description, &indexInitial, &section->indexBuffer);
    }
    if (FAILED(result)) {
        deleteSection(section);
        throwJava(env, "DX12 terrain section buffer creation failed.");
        return;
    }
    section->x = sectionX;
    section->y = sectionY;
    section->z = sectionZ;
    for (UINT layer = 0; layer < kLayerCount; ++layer) {
        section->layerIndexCounts[layer] = static_cast<UINT>(counts[layer]);
    }
    auto existing = renderer->sections.find(key);
    if (existing != renderer->sections.end()) {
        deleteSection(existing->second);
        existing->second = section;
    } else {
        renderer->sections.emplace(key, section);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_updateTerrainAtlas(JNIEnv* env, jclass, jobjectArray levels,
        jint width, jint height) {
    InteropRenderer* renderer = g_interopRenderer;
    if (renderer == nullptr) {
        throwJava(env, "DX12 terrain atlas upload requires an initialized renderer.");
        return;
    }
    const jsize levelCount = levels != nullptr ? env->GetArrayLength(levels) : 0;
    if (levelCount < 1 || levelCount > kMaxAtlasLevels || width <= 0 || height <= 0) {
        throwJava(env, "DX12 terrain atlas dimensions or mip chain are invalid.");
        return;
    }
    D3D11_SUBRESOURCE_DATA data[kMaxAtlasLevels] = {};
    for (jsize level = 0; level < levelCount; ++level) {
        jobject buffer = env->GetObjectArrayElement(levels, level);
        const uint64_t levelWidth = (std::max)(1u, static_cast<UINT>(width) >> level);
        const uint64_t levelHeight = (std::max)(1u, static_cast<UINT>(height) >> level);
        const uint64_t bytes = levelWidth * levelHeight * 4;
        const void* pixels = buffer != nullptr ? env->GetDirectBufferAddress(buffer) : nullptr;
        const jlong capacity = buffer != nullptr ? env->GetDirectBufferCapacity(buffer) : -1;
        if (buffer != nullptr) {
            env->DeleteLocalRef(buffer); // o array Java mantém o buffer vivo
        }
        if (pixels == nullptr || capacity < 0 || bytes > static_cast<uint64_t>(capacity)) {
            throwJava(env, "DX12 terrain atlas mip level buffer is invalid.");
            return;
        }
        data[level].pSysMem = pixels;
        data[level].SysMemPitch = static_cast<UINT>(levelWidth * 4);
    }
    D3D11_TEXTURE2D_DESC description = {};
    description.Width = static_cast<UINT>(width);
    description.Height = static_cast<UINT>(height);
    description.MipLevels = static_cast<UINT>(levelCount);
    description.ArraySize = 1;
    description.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    description.SampleDesc.Count = 1;
    description.Usage = D3D11_USAGE_IMMUTABLE;
    description.BindFlags = D3D11_BIND_SHADER_RESOURCE;
    ID3D11Texture2D* atlas = nullptr;
    ID3D11ShaderResourceView* atlasView = nullptr;
    HRESULT result = renderer->d3d11Device->CreateTexture2D(&description, data, &atlas);
    if (SUCCEEDED(result)) {
        result = renderer->d3d11Device->CreateShaderResourceView(atlas, nullptr, &atlasView);
    }
    if (FAILED(result)) {
        release(atlasView);
        release(atlas);
        throwJava(env, "DX12 terrain atlas texture upload failed.");
        return;
    }
    release(renderer->atlasView);
    release(renderer->atlas);
    renderer->atlas = atlas;
    renderer->atlasView = atlasView;
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_removeTerrainSection(JNIEnv*, jclass, jlong key) {
    if (g_interopRenderer == nullptr) {
        return;
    }
    auto existing = g_interopRenderer->sections.find(key);
    if (existing != g_interopRenderer->sections.end()) {
        deleteSection(existing->second);
        g_interopRenderer->sections.erase(existing);
    }
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