#include <jni.h>
#include <windows.h>
#include <d3d11.h>
#include <d3d11on12.h>
#include <d3d12.h>
#include <d3dcompiler.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <functional>
#include <limits>
#include <mutex>
#include <system_error>
#include <thread>
#include <unordered_map>
#include <vector>

#include "bc7_encoder.h"

namespace {

constexpr UINT64 kFenceError = std::numeric_limits<UINT64>::max();
constexpr DWORD kFenceWaitTimeoutMs = 5000;
constexpr UINT kVertexStride = 16;
constexpr UINT kLayerCount = 3;
constexpr UINT kFramesInFlight = 3;
constexpr UINT kAtlasSlots = 64;
constexpr UINT kDrawConstantCount = 5;
constexpr jsize kMaxAtlasLevels = 16;
constexpr float kSectionRadius = 13.8564f; // sqrt(3) * 8
constexpr float kClearColor[4] = {0.0f, 0.0f, 0.0f, 0.0f};
constexpr DXGI_FORMAT kColorFormat = DXGI_FORMAT_R8G8B8A8_UNORM;
constexpr DXGI_FORMAT kDepthFormat = DXGI_FORMAT_D32_FLOAT;
// Ordem das camadas: solid, cutout_mipped, cutout (confirmar nos .fsh vanilla).
constexpr float kLayerAlphaCutoff[kLayerCount] = {-1.0f, 0.5f, 0.1f};

// Vértice de 16 bytes: posição uint16 (1/1024 de bloco, origem -16), cor RGBA8, UV unorm16.
constexpr char kTerrainShader[] = R"(
cbuffer FrameConstants : register(b0) { float4x4 viewProjection; };
cbuffer DrawConstants : register(b1) { float3 sectionOffset; float alphaCutoff; uint atlasIndex; };
Texture2D atlasTextures[ATLAS_SLOTS] : register(t0);
SamplerState atlasSampler : register(s0);
struct VertexInput { uint4 position : POSITION; float4 color : COLOR; float2 uv : TEXCOORD0; };
struct VertexOutput { float4 position : SV_Position; float4 color : COLOR; float2 uv : TEXCOORD0; };
VertexOutput vsMain(VertexInput input) {
    VertexOutput output;
    float3 local = float3(input.position.xyz) * (1.0 / 1024.0) - 16.0;
    output.position = mul(viewProjection, float4(local + sectionOffset, 1.0));
    output.color = input.color;
    output.uv = input.uv;
    return output;
}
float4 psMain(VertexOutput input) : SV_Target {
    float4 result = atlasTextures[atlasIndex].Sample(atlasSampler, input.uv) * input.color;
#ifdef DIRECT_CUTOUT
    clip(result.a - alphaCutoff);
#endif
    return float4(result.rgb, 1.0);
}
)";

struct TerrainSection {
    int x;
    int y;
    int z;
    UINT layerIndexCounts[kLayerCount];
    UINT layerStart[kLayerCount];
    ID3D12Resource* buffer;
    D3D12_VERTEX_BUFFER_VIEW vertexView;
    D3D12_INDEX_BUFFER_VIEW indexView;
};

struct VisibleSection {
    const TerrainSection* section;
    float offset[3];
};

struct RetiredResource {
    UINT64 fenceValue;
    ID3D12Resource* resource;
};

struct FrameSlot {
    ID3D12CommandAllocator* upload;
    ID3D12CommandAllocator* opaque;
    ID3D12CommandAllocator* cutout;
    UINT64 fenceValue;
};

struct Plane {
    float a, b, c, d;
};

// Thread persistente que grava uma command list enquanto a thread de render grava outra.
struct RecordWorker {
    std::thread thread;
    std::mutex mutex;
    std::condition_variable condition;
    std::function<bool()> job;
    bool pending;
    bool stop;
    bool result;
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
    // D3D11On12 só faz a ponte para o WGL: uma cópia por frame para a textura importada.
    ID3D11Device* d3d11Device;
    ID3D11DeviceContext* d3d11Context;
    ID3D11On12Device* d3d11On12;
    ID3D11Texture2D* interopTexture;
    ID3D11Resource* wrappedColor;
    // Pipeline de terreno D3D12 nativo.
    ID3D12Resource* colorTarget;
    ID3D12Resource* depthTarget;
    ID3D12DescriptorHeap* rtvHeap;
    ID3D12DescriptorHeap* dsvHeap;
    ID3D12DescriptorHeap* srvHeap;
    UINT srvIncrement;
    ID3D12RootSignature* rootSignature;
    ID3D12PipelineState* opaquePipeline;
    ID3D12PipelineState* cutoutPipeline;
    FrameSlot slots[kFramesInFlight];
    UINT slotIndex;
    ID3D12GraphicsCommandList* uploadList;
    ID3D12GraphicsCommandList* opaqueList;
    ID3D12GraphicsCommandList* cutoutList;
    ID3D12GraphicsCommandList4* opaquePassList;
    ID3D12GraphicsCommandList4* cutoutPassList;
    bool uploadOpen;
    std::vector<D3D12_RESOURCE_BARRIER> uploadBarriers;
    std::vector<ID3D12Resource*> pendingRetire;
    std::deque<RetiredResource> retired;
    ID3D12Resource* atlas;
    UINT atlasIndex;
    UINT nextAtlasSlot;
    std::vector<VisibleSection> visible;
    float viewProjection[16];
    RecordWorker worker;
    bool workerRunning;
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

void recordWorkerLoop(RecordWorker* worker) {
    std::unique_lock<std::mutex> lock(worker->mutex);
    for (;;) {
        worker->condition.wait(lock, [worker] { return worker->stop || worker->pending; });
        if (worker->stop) {
            return;
        }
        std::function<bool()> job = std::move(worker->job);
        lock.unlock();
        const bool result = job();
        lock.lock();
        worker->result = result;
        worker->pending = false;
        worker->condition.notify_all();
    }
}

void stopRecordWorker(InteropRenderer* renderer) {
    if (!renderer->workerRunning) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(renderer->worker.mutex);
        renderer->worker.stop = true;
    }
    renderer->worker.condition.notify_all();
    renderer->worker.thread.join();
    renderer->workerRunning = false;
}

D3D12_RESOURCE_BARRIER transition(ID3D12Resource* resource, D3D12_RESOURCE_STATES before,
        D3D12_RESOURCE_STATES after) {
    D3D12_RESOURCE_BARRIER barrier = {};
    barrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
    barrier.Transition.pResource = resource;
    barrier.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;
    barrier.Transition.StateBefore = before;
    barrier.Transition.StateAfter = after;
    return barrier;
}

D3D12_RESOURCE_DESC bufferDescription(UINT64 bytes) {
    D3D12_RESOURCE_DESC description = {};
    description.Dimension = D3D12_RESOURCE_DIMENSION_BUFFER;
    description.Width = bytes;
    description.Height = 1;
    description.DepthOrArraySize = 1;
    description.MipLevels = 1;
    description.SampleDesc.Count = 1;
    description.Layout = D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
    return description;
}

bool createBuffer(InteropRenderer* renderer, D3D12_HEAP_TYPE heapType, UINT64 bytes,
        D3D12_RESOURCE_STATES state, ID3D12Resource** buffer) {
    D3D12_HEAP_PROPERTIES heap = {};
    heap.Type = heapType;
    const D3D12_RESOURCE_DESC description = bufferDescription(bytes);
    return SUCCEEDED(renderer->device->CreateCommittedResource(&heap, D3D12_HEAP_FLAG_NONE, &description,
            state, nullptr, IID_PPV_ARGS(buffer)));
}

// Recursos só são liberados depois que o frame que os submete por último termina na GPU.
void retire(InteropRenderer* renderer, ID3D12Resource* resource) {
    if (resource != nullptr) {
        renderer->pendingRetire.push_back(resource);
    }
}

void releaseCompleted(InteropRenderer* renderer) {
    const UINT64 completed = renderer->fence->GetCompletedValue();
    while (!renderer->retired.empty() && completed != kFenceError
            && renderer->retired.front().fenceValue <= completed) {
        renderer->retired.front().resource->Release();
        renderer->retired.pop_front();
    }
}

void releaseAllRetired(InteropRenderer* renderer) {
    for (RetiredResource& entry : renderer->retired) {
        entry.resource->Release();
    }
    renderer->retired.clear();
    for (ID3D12Resource* resource : renderer->pendingRetire) {
        resource->Release();
    }
    renderer->pendingRetire.clear();
}

// Abre (uma vez por frame) a lista de upload usando o alocador do slot do próximo frame.
bool ensureUploadOpen(InteropRenderer* renderer) {
    if (renderer->uploadOpen) {
        return true;
    }
    FrameSlot& slot = renderer->slots[renderer->slotIndex];
    if (!waitFence(renderer, renderer->fence, slot.fenceValue)
            || FAILED(slot.upload->Reset())
            || FAILED(renderer->uploadList->Reset(slot.upload, nullptr))) {
        return false;
    }
    renderer->uploadOpen = true;
    return true;
}

void deleteSection(TerrainSection* section) {
    release(section->buffer);
    delete section;
}

void retireSection(InteropRenderer* renderer, TerrainSection* section) {
    retire(renderer, section->buffer);
    section->buffer = nullptr;
    delete section;
}

void retireTerrainSections(InteropRenderer* renderer) {
    for (auto& entry : renderer->sections) {
        retireSection(renderer, entry.second);
    }
    renderer->sections.clear();
}

void releaseSizeResources(InteropRenderer* renderer) {
    release(renderer->wrappedColor);
    if (renderer->d3d11Context != nullptr) {
        renderer->d3d11Context->Flush();
    }
    release(renderer->depthTarget);
    release(renderer->colorTarget);
    release(renderer->interopTexture);
}

bool createSizeResources(InteropRenderer* renderer, JNIEnv* env) {
    D3D11_TEXTURE2D_DESC color = {};
    color.Width = renderer->width;
    color.Height = renderer->height;
    color.MipLevels = 1;
    color.ArraySize = 1;
    color.Format = kColorFormat;
    color.SampleDesc.Count = 1;
    color.Usage = D3D11_USAGE_DEFAULT;
    color.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    if (FAILED(renderer->d3d11Device->CreateTexture2D(&color, nullptr, &renderer->interopTexture))) {
        throwJava(env, "DX12/OpenGL interop could not create the shared color target.");
        return false;
    }

    D3D12_HEAP_PROPERTIES heap = {};
    heap.Type = D3D12_HEAP_TYPE_DEFAULT;
    D3D12_RESOURCE_DESC texture = {};
    texture.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
    texture.Width = renderer->width;
    texture.Height = renderer->height;
    texture.DepthOrArraySize = 1;
    texture.MipLevels = 1;
    texture.Format = kColorFormat;
    texture.SampleDesc.Count = 1;
    texture.Flags = D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET;
    D3D12_CLEAR_VALUE colorClear = {};
    colorClear.Format = kColorFormat;
    std::memcpy(colorClear.Color, kClearColor, sizeof(kClearColor));
    D3D12_RESOURCE_DESC depth = texture;
    depth.Format = kDepthFormat;
    depth.Flags = D3D12_RESOURCE_FLAG_ALLOW_DEPTH_STENCIL | D3D12_RESOURCE_FLAG_DENY_SHADER_RESOURCE;
    D3D12_CLEAR_VALUE depthClear = {};
    depthClear.Format = kDepthFormat;
    depthClear.DepthStencil.Depth = 1.0f;
    if (FAILED(renderer->device->CreateCommittedResource(&heap, D3D12_HEAP_FLAG_NONE, &texture,
                D3D12_RESOURCE_STATE_RENDER_TARGET, &colorClear, IID_PPV_ARGS(&renderer->colorTarget)))
            || FAILED(renderer->device->CreateCommittedResource(&heap, D3D12_HEAP_FLAG_NONE, &depth,
                D3D12_RESOURCE_STATE_DEPTH_WRITE, &depthClear, IID_PPV_ARGS(&renderer->depthTarget)))) {
        throwJava(env, "DX12 terrain color/depth target creation failed.");
        return false;
    }
    renderer->device->CreateRenderTargetView(renderer->colorTarget, nullptr,
            renderer->rtvHeap->GetCPUDescriptorHandleForHeapStart());
    renderer->device->CreateDepthStencilView(renderer->depthTarget, nullptr,
            renderer->dsvHeap->GetCPUDescriptorHandleForHeapStart());

    D3D11_RESOURCE_FLAGS flags = {};
    flags.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    if (FAILED(renderer->d3d11On12->CreateWrappedResource(renderer->colorTarget, &flags,
            D3D12_RESOURCE_STATE_RENDER_TARGET, D3D12_RESOURCE_STATE_RENDER_TARGET,
            IID_PPV_ARGS(&renderer->wrappedColor)))) {
        throwJava(env, "DX12 terrain target could not be wrapped for the OpenGL bridge.");
        return false;
    }
    return true;
}

void destroyInteropRenderer(InteropRenderer* renderer) {
    if (renderer == nullptr) {
        return;
    }
    stopRecordWorker(renderer);
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
    for (auto& entry : renderer->sections) {
        deleteSection(entry.second);
    }
    renderer->sections.clear();
    releaseAllRetired(renderer);
    releaseSizeResources(renderer);
    release(renderer->atlas);
    release(renderer->cutoutPassList);
    release(renderer->opaquePassList);
    release(renderer->cutoutList);
    release(renderer->opaqueList);
    release(renderer->uploadList);
    for (FrameSlot& slot : renderer->slots) {
        release(slot.cutout);
        release(slot.opaque);
        release(slot.upload);
    }
    release(renderer->cutoutPipeline);
    release(renderer->opaquePipeline);
    release(renderer->rootSignature);
    release(renderer->srvHeap);
    release(renderer->dsvHeap);
    release(renderer->rtvHeap);
    release(renderer->d3d11On12);
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

bool compileShader(const char* entryPoint, const char* target, bool cutout, ID3DBlob** bytecode) {
    const D3D_SHADER_MACRO defines[] = {
        {"ATLAS_SLOTS", "64"},
        {cutout ? "DIRECT_CUTOUT" : nullptr, "1"},
        {nullptr, nullptr}
    };
    static_assert(kAtlasSlots == 64, "ATLAS_SLOTS must match kAtlasSlots");
    ID3DBlob* errors = nullptr;
    const HRESULT result = D3DCompile(kTerrainShader, sizeof(kTerrainShader) - 1, nullptr, defines, nullptr,
            entryPoint, target, D3DCOMPILE_ENABLE_STRICTNESS | D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, bytecode, &errors);
    if (errors != nullptr) {
        OutputDebugStringA(static_cast<const char*>(errors->GetBufferPointer()));
    }
    release(errors);
    return SUCCEEDED(result);
}

bool createRootSignature(InteropRenderer* renderer) {
    D3D12_DESCRIPTOR_RANGE atlasRange = {};
    atlasRange.RangeType = D3D12_DESCRIPTOR_RANGE_TYPE_SRV;
    atlasRange.NumDescriptors = kAtlasSlots;
    atlasRange.OffsetInDescriptorsFromTableStart = 0;
    D3D12_ROOT_PARAMETER parameters[3] = {};
    parameters[0].ParameterType = D3D12_ROOT_PARAMETER_TYPE_32BIT_CONSTANTS;
    parameters[0].Constants.ShaderRegister = 0;
    parameters[0].Constants.Num32BitValues = 16;
    parameters[0].ShaderVisibility = D3D12_SHADER_VISIBILITY_VERTEX;
    parameters[1].ParameterType = D3D12_ROOT_PARAMETER_TYPE_32BIT_CONSTANTS;
    parameters[1].Constants.ShaderRegister = 1;
    parameters[1].Constants.Num32BitValues = kDrawConstantCount;
    parameters[1].ShaderVisibility = D3D12_SHADER_VISIBILITY_ALL;
    parameters[2].ParameterType = D3D12_ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE;
    parameters[2].DescriptorTable.NumDescriptorRanges = 1;
    parameters[2].DescriptorTable.pDescriptorRanges = &atlasRange;
    parameters[2].ShaderVisibility = D3D12_SHADER_VISIBILITY_PIXEL;
    D3D12_STATIC_SAMPLER_DESC sampler = {};
    sampler.Filter = D3D12_FILTER_MIN_MAG_POINT_MIP_LINEAR;
    sampler.AddressU = D3D12_TEXTURE_ADDRESS_MODE_CLAMP;
    sampler.AddressV = D3D12_TEXTURE_ADDRESS_MODE_CLAMP;
    sampler.AddressW = D3D12_TEXTURE_ADDRESS_MODE_CLAMP;
    sampler.ComparisonFunc = D3D12_COMPARISON_FUNC_NEVER;
    sampler.MaxLOD = D3D12_FLOAT32_MAX;
    sampler.ShaderVisibility = D3D12_SHADER_VISIBILITY_PIXEL;
    D3D12_ROOT_SIGNATURE_DESC description = {};
    description.NumParameters = ARRAYSIZE(parameters);
    description.pParameters = parameters;
    description.NumStaticSamplers = 1;
    description.pStaticSamplers = &sampler;
    description.Flags = D3D12_ROOT_SIGNATURE_FLAG_ALLOW_INPUT_ASSEMBLER_INPUT_LAYOUT
            | D3D12_ROOT_SIGNATURE_FLAG_DENY_HULL_SHADER_ROOT_ACCESS
            | D3D12_ROOT_SIGNATURE_FLAG_DENY_DOMAIN_SHADER_ROOT_ACCESS
            | D3D12_ROOT_SIGNATURE_FLAG_DENY_GEOMETRY_SHADER_ROOT_ACCESS;
    ID3DBlob* serialized = nullptr;
    ID3DBlob* errors = nullptr;
    HRESULT result = D3D12SerializeRootSignature(&description, D3D_ROOT_SIGNATURE_VERSION_1,
            &serialized, &errors);
    if (SUCCEEDED(result)) {
        result = renderer->device->CreateRootSignature(0, serialized->GetBufferPointer(),
                serialized->GetBufferSize(), IID_PPV_ARGS(&renderer->rootSignature));
    }
    release(errors);
    release(serialized);
    return SUCCEEDED(result);
}

// PSOs criados uma única vez na inicialização: opaco (sem clip) e cutout (clip por root constant).
bool createTerrainPipelines(InteropRenderer* renderer, JNIEnv* env) {
    if (!createRootSignature(renderer)) {
        throwJava(env, "DX12 terrain root signature creation failed.");
        return false;
    }
    ID3DBlob* vertexBytecode = nullptr;
    ID3DBlob* opaqueBytecode = nullptr;
    ID3DBlob* cutoutBytecode = nullptr;
    if (!compileShader("vsMain", "vs_5_1", false, &vertexBytecode)
            || !compileShader("psMain", "ps_5_1", false, &opaqueBytecode)
            || !compileShader("psMain", "ps_5_1", true, &cutoutBytecode)) {
        release(vertexBytecode);
        release(opaqueBytecode);
        release(cutoutBytecode);
        throwJava(env, "DX12 terrain shader compilation failed.");
        return false;
    }
    const D3D12_INPUT_ELEMENT_DESC elements[] = {
        {"POSITION", 0, DXGI_FORMAT_R16G16B16A16_UINT, 0, 0, D3D12_INPUT_CLASSIFICATION_PER_VERTEX_DATA, 0},
        {"COLOR", 0, DXGI_FORMAT_R8G8B8A8_UNORM, 0, 8, D3D12_INPUT_CLASSIFICATION_PER_VERTEX_DATA, 0},
        {"TEXCOORD", 0, DXGI_FORMAT_R16G16_UNORM, 0, 12, D3D12_INPUT_CLASSIFICATION_PER_VERTEX_DATA, 0}
    };
    D3D12_GRAPHICS_PIPELINE_STATE_DESC description = {};
    description.pRootSignature = renderer->rootSignature;
    description.VS = {vertexBytecode->GetBufferPointer(), vertexBytecode->GetBufferSize()};
    description.BlendState.RenderTarget[0].RenderTargetWriteMask = D3D12_COLOR_WRITE_ENABLE_ALL;
    description.BlendState.RenderTarget[0].SrcBlend = D3D12_BLEND_ONE;
    description.BlendState.RenderTarget[0].DestBlend = D3D12_BLEND_ZERO;
    description.BlendState.RenderTarget[0].BlendOp = D3D12_BLEND_OP_ADD;
    description.BlendState.RenderTarget[0].SrcBlendAlpha = D3D12_BLEND_ONE;
    description.BlendState.RenderTarget[0].DestBlendAlpha = D3D12_BLEND_ZERO;
    description.BlendState.RenderTarget[0].BlendOpAlpha = D3D12_BLEND_OP_ADD;
    description.BlendState.RenderTarget[0].LogicOp = D3D12_LOGIC_OP_NOOP;
    description.SampleMask = UINT_MAX;
    // GL usa CCW como frente; o flip em Y da matriz de correção transforma isso em CW no D3D.
    description.RasterizerState.FillMode = D3D12_FILL_MODE_SOLID;
    description.RasterizerState.CullMode = D3D12_CULL_MODE_BACK;
    description.RasterizerState.FrontCounterClockwise = FALSE;
    description.RasterizerState.DepthClipEnable = TRUE;
    description.DepthStencilState.DepthEnable = TRUE;
    description.DepthStencilState.DepthWriteMask = D3D12_DEPTH_WRITE_MASK_ALL;
    description.DepthStencilState.DepthFunc = D3D12_COMPARISON_FUNC_LESS_EQUAL;
    description.InputLayout = {elements, ARRAYSIZE(elements)};
    description.PrimitiveTopologyType = D3D12_PRIMITIVE_TOPOLOGY_TYPE_TRIANGLE;
    description.NumRenderTargets = 1;
    description.RTVFormats[0] = kColorFormat;
    description.DSVFormat = kDepthFormat;
    description.SampleDesc.Count = 1;
    description.PS = {opaqueBytecode->GetBufferPointer(), opaqueBytecode->GetBufferSize()};
    HRESULT result = renderer->device->CreateGraphicsPipelineState(&description,
            IID_PPV_ARGS(&renderer->opaquePipeline));
    if (SUCCEEDED(result)) {
        description.PS = {cutoutBytecode->GetBufferPointer(), cutoutBytecode->GetBufferSize()};
        result = renderer->device->CreateGraphicsPipelineState(&description,
                IID_PPV_ARGS(&renderer->cutoutPipeline));
    }
    release(vertexBytecode);
    release(opaqueBytecode);
    release(cutoutBytecode);
    if (FAILED(result)) {
        throwJava(env, "DX12 terrain pipeline state creation failed.");
        return false;
    }
    return true;
}

bool createDescriptorHeaps(InteropRenderer* renderer) {
    D3D12_DESCRIPTOR_HEAP_DESC rtv = {D3D12_DESCRIPTOR_HEAP_TYPE_RTV, 1, D3D12_DESCRIPTOR_HEAP_FLAG_NONE, 0};
    D3D12_DESCRIPTOR_HEAP_DESC dsv = {D3D12_DESCRIPTOR_HEAP_TYPE_DSV, 1, D3D12_DESCRIPTOR_HEAP_FLAG_NONE, 0};
    D3D12_DESCRIPTOR_HEAP_DESC srv = {D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV, kAtlasSlots,
            D3D12_DESCRIPTOR_HEAP_FLAG_SHADER_VISIBLE, 0};
    if (FAILED(renderer->device->CreateDescriptorHeap(&rtv, IID_PPV_ARGS(&renderer->rtvHeap)))
            || FAILED(renderer->device->CreateDescriptorHeap(&dsv, IID_PPV_ARGS(&renderer->dsvHeap)))
            || FAILED(renderer->device->CreateDescriptorHeap(&srv, IID_PPV_ARGS(&renderer->srvHeap)))) {
        return false;
    }
    renderer->srvIncrement = renderer->device->GetDescriptorHandleIncrementSize(
            D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV);
    // Todos os slots começam com um SRV nulo válido (exigido em hardware de binding tier 1).
    D3D12_SHADER_RESOURCE_VIEW_DESC nullView = {};
    nullView.Format = kColorFormat;
    nullView.ViewDimension = D3D12_SRV_DIMENSION_TEXTURE2D;
    nullView.Shader4ComponentMapping = D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING;
    nullView.Texture2D.MipLevels = 1;
    D3D12_CPU_DESCRIPTOR_HANDLE handle = renderer->srvHeap->GetCPUDescriptorHandleForHeapStart();
    for (UINT slot = 0; slot < kAtlasSlots; ++slot) {
        renderer->device->CreateShaderResourceView(nullptr, &nullView, handle);
        handle.ptr += renderer->srvIncrement;
    }
    return true;
}

bool createCommandObjects(InteropRenderer* renderer) {
    for (FrameSlot& slot : renderer->slots) {
        if (FAILED(renderer->device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT,
                    IID_PPV_ARGS(&slot.upload)))
                || FAILED(renderer->device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT,
                    IID_PPV_ARGS(&slot.opaque)))
                || FAILED(renderer->device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT,
                    IID_PPV_ARGS(&slot.cutout)))) {
            return false;
        }
    }
    FrameSlot& first = renderer->slots[0];
    if (FAILED(renderer->device->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT, first.upload, nullptr,
                IID_PPV_ARGS(&renderer->uploadList)))
            || FAILED(renderer->device->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT, first.opaque,
                nullptr, IID_PPV_ARGS(&renderer->opaqueList)))
            || FAILED(renderer->device->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT, first.cutout,
                nullptr, IID_PPV_ARGS(&renderer->cutoutList)))
            || FAILED(renderer->uploadList->Close())
            || FAILED(renderer->opaqueList->Close())
            || FAILED(renderer->cutoutList->Close())) {
        return false;
    }
    // Render passes exigem ID3D12GraphicsCommandList4 (Windows 10 1809+); sem ela usa OMSetRenderTargets.
    if (FAILED(renderer->opaqueList->QueryInterface(IID_PPV_ARGS(&renderer->opaquePassList)))
            || FAILED(renderer->cutoutList->QueryInterface(IID_PPV_ARGS(&renderer->cutoutPassList)))) {
        release(renderer->opaquePassList);
        release(renderer->cutoutPassList);
    }
    return true;
}

void startRecordWorker(InteropRenderer* renderer) {
    try {
        renderer->worker.thread = std::thread(recordWorkerLoop, &renderer->worker);
        renderer->workerRunning = true;
    } catch (const std::system_error&) {
        renderer->workerRunning = false; // grava as duas listas na thread de render
    }
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
            &renderer->d3d11Device, &renderer->d3d11Context, nullptr))
            || FAILED(renderer->d3d11Device->QueryInterface(IID_PPV_ARGS(&renderer->d3d11On12)))) {
        throwJava(env, "DX12/OpenGL interop could not create a D3D11On12 device.");
        return false;
    }
    if (!createDescriptorHeaps(renderer) || !createCommandObjects(renderer)) {
        throwJava(env, "DX12 terrain descriptor heaps or command lists could not be created.");
        return false;
    }
    if (!createTerrainPipelines(renderer, env) || !createSizeResources(renderer, env)) {
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
    startRecordWorker(renderer);
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

void collectVisibleSections(InteropRenderer* renderer, double cameraX, double cameraY, double cameraZ,
        float renderDistance) {
    renderer->visible.clear();
    Plane frustum[6];
    extractFrustum(renderer->viewProjection, frustum);
    const double maxDistanceSquared = static_cast<double>(renderDistance) * renderDistance;
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
        renderer->visible.push_back({section, {static_cast<float>(ox), static_cast<float>(oy),
                static_cast<float>(oz)}});
    }
}

enum class PassRole { ClearOnly, Opaque, Cutout };

// Opaco abre o render pass (clear) e o suspende; cutout o retoma e descarta o depth no fim.
void beginTerrainPass(InteropRenderer* renderer, ID3D12GraphicsCommandList* list,
        ID3D12GraphicsCommandList4* passList, PassRole role) {
    const D3D12_CPU_DESCRIPTOR_HANDLE rtv = renderer->rtvHeap->GetCPUDescriptorHandleForHeapStart();
    const D3D12_CPU_DESCRIPTOR_HANDLE dsv = renderer->dsvHeap->GetCPUDescriptorHandleForHeapStart();
    const bool clear = role != PassRole::Cutout;
    if (passList != nullptr) {
        D3D12_RENDER_PASS_RENDER_TARGET_DESC color = {};
        color.cpuDescriptor = rtv;
        color.BeginningAccess.Type = clear ? D3D12_RENDER_PASS_BEGINNING_ACCESS_TYPE_CLEAR
                : D3D12_RENDER_PASS_BEGINNING_ACCESS_TYPE_PRESERVE;
        color.BeginningAccess.Clear.ClearValue.Format = kColorFormat;
        std::memcpy(color.BeginningAccess.Clear.ClearValue.Color, kClearColor, sizeof(kClearColor));
        color.EndingAccess.Type = D3D12_RENDER_PASS_ENDING_ACCESS_TYPE_PRESERVE;
        D3D12_RENDER_PASS_DEPTH_STENCIL_DESC depth = {};
        depth.cpuDescriptor = dsv;
        depth.DepthBeginningAccess.Type = clear ? D3D12_RENDER_PASS_BEGINNING_ACCESS_TYPE_CLEAR
                : D3D12_RENDER_PASS_BEGINNING_ACCESS_TYPE_PRESERVE;
        depth.DepthBeginningAccess.Clear.ClearValue.Format = kDepthFormat;
        depth.DepthBeginningAccess.Clear.ClearValue.DepthStencil.Depth = 1.0f;
        depth.DepthEndingAccess.Type = role == PassRole::Opaque ? D3D12_RENDER_PASS_ENDING_ACCESS_TYPE_PRESERVE
                : D3D12_RENDER_PASS_ENDING_ACCESS_TYPE_DISCARD;
        depth.StencilBeginningAccess.Type = D3D12_RENDER_PASS_BEGINNING_ACCESS_TYPE_NO_ACCESS;
        depth.StencilEndingAccess.Type = D3D12_RENDER_PASS_ENDING_ACCESS_TYPE_NO_ACCESS;
        const D3D12_RENDER_PASS_FLAGS flags = role == PassRole::Opaque ? D3D12_RENDER_PASS_FLAG_SUSPENDING_PASS
                : role == PassRole::Cutout ? D3D12_RENDER_PASS_FLAG_RESUMING_PASS : D3D12_RENDER_PASS_FLAG_NONE;
        passList->BeginRenderPass(1, &color, &depth, flags);
    } else {
        list->OMSetRenderTargets(1, &rtv, FALSE, &dsv);
        if (clear) {
            list->ClearRenderTargetView(rtv, kClearColor, 0, nullptr);
            list->ClearDepthStencilView(dsv, D3D12_CLEAR_FLAG_DEPTH, 1.0f, 0, 0, nullptr);
        }
    }
    if (role == PassRole::ClearOnly) {
        return;
    }
    ID3D12DescriptorHeap* heaps[] = {renderer->srvHeap};
    list->SetDescriptorHeaps(1, heaps);
    list->SetGraphicsRootSignature(renderer->rootSignature);
    list->SetPipelineState(role == PassRole::Opaque ? renderer->opaquePipeline : renderer->cutoutPipeline);
    list->IASetPrimitiveTopology(D3D_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    const D3D12_VIEWPORT viewport = {0.0f, 0.0f, static_cast<float>(renderer->width),
            static_cast<float>(renderer->height), 0.0f, 1.0f};
    const D3D12_RECT scissor = {0, 0, static_cast<LONG>(renderer->width), static_cast<LONG>(renderer->height)};
    list->RSSetViewports(1, &viewport);
    list->RSSetScissorRects(1, &scissor);
    list->SetGraphicsRoot32BitConstants(0, 16, renderer->viewProjection, 0);
    list->SetGraphicsRootDescriptorTable(2, renderer->srvHeap->GetGPUDescriptorHandleForHeapStart());
}

void drawLayer(InteropRenderer* renderer, ID3D12GraphicsCommandList* list, const VisibleSection& entry,
        UINT layer) {
    UINT32 constants[kDrawConstantCount];
    std::memcpy(constants, entry.offset, sizeof(entry.offset));
    std::memcpy(&constants[3], &kLayerAlphaCutoff[layer], sizeof(float));
    constants[4] = renderer->atlasIndex;
    list->SetGraphicsRoot32BitConstants(1, kDrawConstantCount, constants, 0);
    list->DrawIndexedInstanced(entry.section->layerIndexCounts[layer], 1, entry.section->layerStart[layer], 0, 0);
}

bool recordTerrainList(InteropRenderer* renderer, ID3D12CommandAllocator* allocator,
        ID3D12GraphicsCommandList* list, ID3D12GraphicsCommandList4* passList, PassRole role) {
    if (FAILED(allocator->Reset()) || FAILED(list->Reset(allocator, nullptr))) {
        return false;
    }
    beginTerrainPass(renderer, list, passList, role);
    const UINT firstLayer = role == PassRole::Opaque ? 0 : 1;
    const UINT lastLayer = role == PassRole::Opaque ? 1 : kLayerCount;
    if (role != PassRole::ClearOnly) {
        for (const VisibleSection& entry : renderer->visible) {
            const TerrainSection* section = entry.section;
            bool bound = false;
            for (UINT layer = firstLayer; layer < lastLayer; ++layer) {
                if (section->layerIndexCounts[layer] == 0) {
                    continue;
                }
                if (!bound) {
                    list->IASetVertexBuffers(0, 1, &section->vertexView);
                    list->IASetIndexBuffer(&section->indexView);
                    bound = true;
                }
                drawLayer(renderer, list, entry, layer);
            }
        }
    }
    if (passList != nullptr) {
        passList->EndRenderPass();
    }
    return SUCCEEDED(list->Close());
}

// Grava opaco na thread de render e cutout em paralelo na thread de gravação.
bool recordTerrain(InteropRenderer* renderer, FrameSlot& slot, bool drawTerrain) {
    if (!drawTerrain) {
        return recordTerrainList(renderer, slot.opaque, renderer->opaqueList, renderer->opaquePassList,
                PassRole::ClearOnly);
    }
    auto cutoutJob = [renderer, &slot] {
        return recordTerrainList(renderer, slot.cutout, renderer->cutoutList, renderer->cutoutPassList,
                PassRole::Cutout);
    };
    if (!renderer->workerRunning) {
        return recordTerrainList(renderer, slot.opaque, renderer->opaqueList, renderer->opaquePassList,
                PassRole::Opaque) && cutoutJob();
    }
    RecordWorker& worker = renderer->worker;
    {
        std::lock_guard<std::mutex> lock(worker.mutex);
        worker.job = cutoutJob;
        worker.pending = true;
    }
    worker.condition.notify_all();
    const bool opaque = recordTerrainList(renderer, slot.opaque, renderer->opaqueList, renderer->opaquePassList,
            PassRole::Opaque);
    std::unique_lock<std::mutex> lock(worker.mutex);
    worker.condition.wait(lock, [&worker] { return !worker.pending; });
    return opaque && worker.result;
}

bool submitUploads(InteropRenderer* renderer) {
    if (!renderer->uploadOpen) {
        return true;
    }
    if (!renderer->uploadBarriers.empty()) {
        renderer->uploadList->ResourceBarrier(static_cast<UINT>(renderer->uploadBarriers.size()),
                renderer->uploadBarriers.data());
        renderer->uploadBarriers.clear();
    }
    renderer->uploadOpen = false;
    if (FAILED(renderer->uploadList->Close())) {
        return false;
    }
    // Submissão separada: os buffers decaem para COMMON antes de serem lidos pelas listas de terreno.
    ID3D12CommandList* lists[] = {renderer->uploadList};
    renderer->queue->ExecuteCommandLists(1, lists);
    return true;
}

void copyToInteropTexture(InteropRenderer* renderer) {
    renderer->d3d11On12->AcquireWrappedResources(&renderer->wrappedColor, 1);
    renderer->d3d11Context->CopyResource(renderer->interopTexture, renderer->wrappedColor);
    renderer->d3d11On12->ReleaseWrappedResources(&renderer->wrappedColor, 1);
    renderer->d3d11Context->Flush();
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
    FrameSlot& slot = renderer->slots[renderer->slotIndex];
    if (!waitFence(renderer, renderer->fence, slot.fenceValue)) {
        throwJava(env, "DX12 terrain timed out waiting for a free frame slot.");
        return false;
    }
    releaseCompleted(renderer);

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

    std::memcpy(renderer->viewProjection, viewProjection, sizeof(renderer->viewProjection));
    const bool drawTerrain = renderer->atlas != nullptr && !renderer->sections.empty();
    if (drawTerrain) {
        collectVisibleSections(renderer, cameraX, cameraY, cameraZ, renderDistance);
    }
    if (!submitUploads(renderer)) {
        throwJava(env, "DX12 terrain upload command list could not be closed.");
        return false;
    }
    if (!recordTerrain(renderer, slot, drawTerrain)) {
        throwJava(env, "DX12 terrain command lists could not be recorded.");
        return false;
    }
    // Ambas as listas na mesma submissão: o render pass suspenso no opaco é retomado pelo cutout.
    ID3D12CommandList* lists[] = {renderer->opaqueList, renderer->cutoutList};
    renderer->queue->ExecuteCommandLists(drawTerrain ? 2 : 1, lists);
    copyToInteropTexture(renderer);

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
    slot.fenceValue = dxReadyValue;
    renderer->slotIndex = (renderer->slotIndex + 1) % kFramesInFlight;
    for (ID3D12Resource* resource : renderer->pendingRetire) {
        renderer->retired.push_back({dxReadyValue, resource});
    }
    renderer->pendingRetire.clear();
    if (gpu) {
        renderer->watchdogCondition.notify_one();
    } else if (!waitFence(renderer, renderer->fence, dxReadyValue)) {
        throwJava(env, "DX12/OpenGL interop timed out waiting for its frame fence.");
        return false;
    }
    *gpuSynchronizationUsed = gpu;
    return true;
}

bool uploadAtlas(InteropRenderer* renderer, const void* const* levelData, jsize levelCount,
        UINT width, UINT height, bool compress, JNIEnv* env) {
    // BC7 exige que o nível 0 seja múltiplo de 4; níveis menores são preenchidos por bloco.
    const bool bc7 = compress && width % 4 == 0 && height % 4 == 0;
    std::vector<std::vector<uint8_t>> encoded;
    if (bc7) {
        encoded.resize(static_cast<size_t>(levelCount));
        for (jsize level = 0; level < levelCount; ++level) {
            const UINT levelWidth = (std::max)(1u, width >> level);
            const UINT levelHeight = (std::max)(1u, height >> level);
            encoded[level].resize(static_cast<size_t>(direct::bc7LevelSize(levelWidth, levelHeight)));
            direct::encodeBc7Level(static_cast<const uint8_t*>(levelData[level]), levelWidth, levelHeight,
                    encoded[level].data());
        }
    }
    const DXGI_FORMAT format = bc7 ? DXGI_FORMAT_BC7_UNORM : kColorFormat;
    D3D12_RESOURCE_DESC description = {};
    description.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
    description.Width = width;
    description.Height = height;
    description.DepthOrArraySize = 1;
    description.MipLevels = static_cast<UINT16>(levelCount);
    description.Format = format;
    description.SampleDesc.Count = 1;
    D3D12_PLACED_SUBRESOURCE_FOOTPRINT footprints[kMaxAtlasLevels];
    UINT rowCounts[kMaxAtlasLevels];
    UINT64 rowSizes[kMaxAtlasLevels];
    UINT64 uploadBytes = 0;
    renderer->device->GetCopyableFootprints(&description, 0, static_cast<UINT>(levelCount), 0, footprints,
            rowCounts, rowSizes, &uploadBytes);

    D3D12_HEAP_PROPERTIES heap = {};
    heap.Type = D3D12_HEAP_TYPE_DEFAULT;
    ID3D12Resource* atlas = nullptr;
    ID3D12Resource* upload = nullptr;
    void* mapped = nullptr;
    const D3D12_RANGE noRead = {0, 0};
    if (FAILED(renderer->device->CreateCommittedResource(&heap, D3D12_HEAP_FLAG_NONE, &description,
                D3D12_RESOURCE_STATE_COPY_DEST, nullptr, IID_PPV_ARGS(&atlas)))
            || !createBuffer(renderer, D3D12_HEAP_TYPE_UPLOAD, uploadBytes, D3D12_RESOURCE_STATE_GENERIC_READ,
                &upload)
            || FAILED(upload->Map(0, &noRead, &mapped))
            || !ensureUploadOpen(renderer)) {
        release(upload);
        release(atlas);
        throwJava(env, "DX12 terrain atlas texture upload failed.");
        return false;
    }
    for (jsize level = 0; level < levelCount; ++level) {
        const uint8_t* source = bc7 ? encoded[level].data() : static_cast<const uint8_t*>(levelData[level]);
        uint8_t* destination = static_cast<uint8_t*>(mapped) + footprints[level].Offset;
        for (UINT row = 0; row < rowCounts[level]; ++row) {
            std::memcpy(destination + static_cast<UINT64>(row) * footprints[level].Footprint.RowPitch,
                    source + row * rowSizes[level], static_cast<size_t>(rowSizes[level]));
        }
    }
    upload->Unmap(0, nullptr);
    for (jsize level = 0; level < levelCount; ++level) {
        D3D12_TEXTURE_COPY_LOCATION target = {};
        target.pResource = atlas;
        target.Type = D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX;
        target.SubresourceIndex = static_cast<UINT>(level);
        D3D12_TEXTURE_COPY_LOCATION source = {};
        source.pResource = upload;
        source.Type = D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;
        source.PlacedFootprint = footprints[level];
        renderer->uploadList->CopyTextureRegion(&target, 0, 0, 0, &source, nullptr);
    }
    renderer->uploadBarriers.push_back(transition(atlas, D3D12_RESOURCE_STATE_COPY_DEST,
            D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE));

    // Bindless: o novo atlas ganha um slot próprio no heap e o shader o indexa por root constant.
    const UINT slot = renderer->nextAtlasSlot;
    renderer->nextAtlasSlot = (slot + 1) % kAtlasSlots;
    D3D12_SHADER_RESOURCE_VIEW_DESC view = {};
    view.Format = format;
    view.ViewDimension = D3D12_SRV_DIMENSION_TEXTURE2D;
    view.Shader4ComponentMapping = D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING;
    view.Texture2D.MipLevels = static_cast<UINT>(levelCount);
    D3D12_CPU_DESCRIPTOR_HANDLE handle = renderer->srvHeap->GetCPUDescriptorHandleForHeapStart();
    handle.ptr += static_cast<SIZE_T>(slot) * renderer->srvIncrement;
    renderer->device->CreateShaderResourceView(atlas, &view, handle);
    retire(renderer, upload);
    retire(renderer, renderer->atlas);
    renderer->atlas = atlas;
    renderer->atlasIndex = slot;
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
            || vertexBytes + indexBytes > std::numeric_limits<UINT>::max()) {
        throwJava(env, "DX12 terrain section buffers have invalid direct-buffer sizes.");
        return;
    }

    // Um único buffer por seção (vértices seguidos de índices) em memória de vídeo, via staging.
    const UINT64 totalBytes = static_cast<UINT64>(vertexBytes + indexBytes);
    ID3D12Resource* buffer = nullptr;
    ID3D12Resource* upload = nullptr;
    void* mapped = nullptr;
    const D3D12_RANGE noRead = {0, 0};
    if (!createBuffer(renderer, D3D12_HEAP_TYPE_DEFAULT, totalBytes, D3D12_RESOURCE_STATE_COMMON, &buffer)
            || !createBuffer(renderer, D3D12_HEAP_TYPE_UPLOAD, totalBytes, D3D12_RESOURCE_STATE_GENERIC_READ,
                &upload)
            || FAILED(upload->Map(0, &noRead, &mapped))
            || !ensureUploadOpen(renderer)) {
        release(upload);
        release(buffer);
        throwJava(env, "DX12 terrain section buffer creation failed.");
        return;
    }
    std::memcpy(mapped, vertices, static_cast<size_t>(vertexBytes));
    std::memcpy(static_cast<uint8_t*>(mapped) + vertexBytes, indices, static_cast<size_t>(indexBytes));
    upload->Unmap(0, nullptr);
    renderer->uploadList->CopyBufferRegion(buffer, 0, upload, 0, totalBytes);
    renderer->uploadBarriers.push_back(transition(buffer, D3D12_RESOURCE_STATE_COPY_DEST,
            D3D12_RESOURCE_STATE_VERTEX_AND_CONSTANT_BUFFER | D3D12_RESOURCE_STATE_INDEX_BUFFER));
    retire(renderer, upload);

    TerrainSection* section = new TerrainSection{};
    section->x = sectionX;
    section->y = sectionY;
    section->z = sectionZ;
    section->buffer = buffer;
    const D3D12_GPU_VIRTUAL_ADDRESS address = buffer->GetGPUVirtualAddress();
    section->vertexView = {address, static_cast<UINT>(vertexBytes), kVertexStride};
    section->indexView = {address + vertexBytes, static_cast<UINT>(indexBytes), DXGI_FORMAT_R32_UINT};
    UINT start = 0;
    for (UINT layer = 0; layer < kLayerCount; ++layer) {
        section->layerIndexCounts[layer] = static_cast<UINT>(counts[layer]);
        section->layerStart[layer] = start;
        start += section->layerIndexCounts[layer];
    }
    auto existing = renderer->sections.find(key);
    if (existing != renderer->sections.end()) {
        retireSection(renderer, existing->second);
        existing->second = section;
    } else {
        renderer->sections.emplace(key, section);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_updateTerrainAtlas(JNIEnv* env, jclass, jobjectArray levels,
        jint width, jint height, jboolean compress) {
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
    const void* data[kMaxAtlasLevels] = {};
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
        data[level] = pixels;
    }
    uploadAtlas(renderer, data, levelCount, static_cast<UINT>(width), static_cast<UINT>(height),
            compress == JNI_TRUE, env);
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_removeTerrainSection(JNIEnv*, jclass, jlong key) {
    if (g_interopRenderer == nullptr) {
        return;
    }
    auto existing = g_interopRenderer->sections.find(key);
    if (existing != g_interopRenderer->sections.end()) {
        retireSection(g_interopRenderer, existing->second);
        g_interopRenderer->sections.erase(existing);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_clearTerrain(JNIEnv*, jclass) {
    if (g_interopRenderer != nullptr) {
        retireTerrainSections(g_interopRenderer);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_shutdownInterop(JNIEnv*, jclass) {
    destroyInteropRenderer(g_interopRenderer);
    g_interopRenderer = nullptr;
}
