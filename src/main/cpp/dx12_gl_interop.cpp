#include <jni.h>
#include <windows.h>
#include <d3d11.h>
#include <d3d11on12.h>
#include <d3d12.h>
#include <d3dcompiler.h>
#include <dxgi1_4.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <limits>
#include <map>
#include <iterator>
#include <mutex>
#include <string>
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
constexpr UINT kMaxExtensionPasses = 8;
constexpr UINT kPassUniformFloatCount = 60;
constexpr UINT kPassConstantCount = 64;
constexpr jsize kMaxAtlasLevels = 16;
constexpr float kSectionRadius = 13.8564f; // sqrt(3) * 8
constexpr float kClearColor[4] = {0.0f, 0.0f, 0.0f, 0.0f};
constexpr DXGI_FORMAT kColorFormat = DXGI_FORMAT_R8G8B8A8_UNORM;
constexpr DXGI_FORMAT kDepthFormat = DXGI_FORMAT_D32_FLOAT;
constexpr D3D12_RESOURCE_STATES kTerrainBufferState = D3D12_RESOURCE_STATE_VERTEX_AND_CONSTANT_BUFFER
        | D3D12_RESOURCE_STATE_INDEX_BUFFER;
// Cutoffs verificados nos shaders vanilla 1.21.1 rendertype_cutout*.fsh.
constexpr float kLayerAlphaCutoff[kLayerCount] = {-1.0f, 0.5f, 0.1f};

constexpr char kExtensionVertexShader[] = R"(
struct VertexOutput { float4 position : SV_Position; };
VertexOutput vsMain(uint vertexId : SV_VertexID) {
    float2 position = float2((vertexId << 1) & 2, vertexId & 2);
    VertexOutput output;
    output.position = float4(position * float2(2.0, -2.0) + float2(-1.0, 1.0), 0.0, 1.0);
    return output;
}
)";

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
    // OpenGL glGetTexImage returns atlas rows from bottom to top; D3D12 samples from the top row.
    output.uv = float2(input.uv.x, 1.0 - input.uv.y);
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

struct ArenaPage {
    ID3D12Resource* resource;
    UINT64 capacity;
    D3D12_RESOURCE_STATES state;
    bool uploadQueued;
    std::map<UINT64, UINT64> freeRanges;
};

struct TerrainSection {
    jlong key;
    int x;
    int y;
    int z;
    UINT layerIndexCounts[kLayerCount];
    UINT layerStart[kLayerCount];
    ArenaPage* page;
    UINT64 allocationOffset;
    UINT64 allocationSize;
    D3D12_VERTEX_BUFFER_VIEW vertexView;
    D3D12_INDEX_BUFFER_VIEW indexView;
};

struct VisibleSection {
    const TerrainSection* section;
    float offset[3];
};

struct TerrainIndirectCommand {
    D3D12_VERTEX_BUFFER_VIEW vertexView;
    D3D12_INDEX_BUFFER_VIEW indexView;
    UINT constants[kDrawConstantCount];
    D3D12_DRAW_INDEXED_ARGUMENTS draw;
};
static_assert(sizeof(TerrainIndirectCommand) == 72);

struct RetiredResource {
    UINT64 fenceValue;
    ID3D12Resource* resource;
};

struct RetiredAllocation {
    UINT64 fenceValue;
    ArenaPage* page;
    UINT64 offset;
    UINT64 bytes;
};

struct FrameSlot {
    ID3D12CommandAllocator* upload;
    ID3D12CommandAllocator* opaque;
    ID3D12CommandAllocator* cutout;
    ID3D12CommandAllocator* extension;
    ID3D12Resource* uploadBuffer;
    uint8_t* uploadMapped;
    UINT64 uploadCapacity;
    UINT64 uploadOffset;
    UINT64 opaqueIndirectOffset;
    UINT64 cutoutIndirectOffset;
    UINT opaqueIndirectCount;
    UINT cutoutIndirectCount;
    bool indirectReady;
    UINT64 fenceValue;
};

struct ExtensionPass {
    std::string source;
    ID3D12PipelineState* pipeline;
};

struct Plane {
    float a, b, c, d, length;
};

enum class PassRole { ClearOnly, Opaque, Cutout };

// Thread persistente que grava uma command list enquanto a thread de render grava outra.
struct RecordWorker {
    std::thread thread;
    std::mutex mutex;
    std::condition_variable condition;
    // mutex protects every cross-thread field below, including the published result.
    FrameSlot* slot;
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
    ID3D12Fence* cpuCopyFence;
    UINT64 cpuCopyFenceValue;
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
    std::string adapterName;
    UINT featureFlags;
    ID3D12Device1* device1;
    ID3D12PipelineLibrary* pipelineLibrary;
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
    ID3D12CommandSignature* terrainCommandSignature;
    ID3D12RootSignature* extensionRootSignature;
    ID3D12PipelineState* opaquePipeline;
    ID3D12PipelineState* cutoutPipeline;
    std::vector<ExtensionPass> extensionPasses;
    ID3D12DescriptorHeap* extensionRtvHeap;
    ID3D12DescriptorHeap* extensionSrvHeap;
    UINT extensionSrvIncrement;
    UINT extensionRtvIncrement;
    ID3D12Resource* extensionTargets[2];
    FrameSlot slots[kFramesInFlight];
    UINT slotIndex;
    ID3D12GraphicsCommandList* uploadList;
    ID3D12GraphicsCommandList* opaqueList;
    ID3D12GraphicsCommandList* cutoutList;
    ID3D12GraphicsCommandList* extensionList;
    bool uploadOpen;
    // Render/JNI-thread-owned upload bookkeeping. RecordWorker only records command lists
    // from the immutable visible-section snapshot and must never mutate these containers.
    std::vector<D3D12_RESOURCE_BARRIER> uploadBarriers;
    std::vector<ArenaPage*> uploadPages;
    std::vector<ID3D12Resource*> pendingRetire;
    std::vector<RetiredAllocation> pendingRetireAllocations;
    std::deque<RetiredResource> retired;
    std::deque<RetiredAllocation> retiredAllocations;
    std::vector<ArenaPage*> arenaPages;
    UINT64 terrainArenaBytes;
    UINT64 terrainArenaPageBytes;
    UINT64 maxTerrainArenaBytes;
    ID3D12Resource* atlas;
    UINT atlasIndex;
    UINT nextAtlasSlot;
    std::vector<VisibleSection> visible;
    float viewProjection[16];
    RecordWorker worker;
    bool workerRunning;
    std::unordered_map<jlong, TerrainSection*> sections;
    std::unordered_map<jlong, size_t> sectionIndices;
    std::vector<TerrainSection*> sectionList;
};

bool recordTerrainList(InteropRenderer* renderer, ID3D12CommandAllocator* allocator,
        ID3D12GraphicsCommandList* list, PassRole role);

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

void inspectAdapter(InteropRenderer* renderer) {
    renderer->adapterName = "unknown";
    renderer->featureFlags = renderer->pipelineLibrary != nullptr ? 8u : 0u;
    IDXGIFactory1* factory = nullptr;
    if (SUCCEEDED(CreateDXGIFactory1(IID_PPV_ARGS(&factory)))) {
        const LUID deviceLuid = renderer->device->GetAdapterLuid();
        for (UINT index = 0;; ++index) {
            IDXGIAdapter1* adapter = nullptr;
            if (factory->EnumAdapters1(index, &adapter) == DXGI_ERROR_NOT_FOUND) {
                break;
            }
            if (adapter == nullptr) {
                continue;
            }
            DXGI_ADAPTER_DESC1 description = {};
            if (SUCCEEDED(adapter->GetDesc1(&description))
                    && description.AdapterLuid.HighPart == deviceLuid.HighPart
                    && description.AdapterLuid.LowPart == deviceLuid.LowPart) {
                char name[256] = {};
                WideCharToMultiByte(CP_UTF8, 0, description.Description, -1, name,
                        static_cast<int>(sizeof(name)), nullptr, nullptr);
                renderer->adapterName = name;
                if ((description.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) == 0) {
                    renderer->featureFlags |= 1u;
                }
                adapter->Release();
                break;
            }
            adapter->Release();
        }
        factory->Release();
    }

    D3D12_FEATURE_DATA_D3D12_OPTIONS options = {};
    if (SUCCEEDED(renderer->device->CheckFeatureSupport(D3D12_FEATURE_D3D12_OPTIONS,
            &options, sizeof(options))) && options.ResourceBindingTier >= D3D12_RESOURCE_BINDING_TIER_2) {
        renderer->featureFlags |= 2u;
    }
    // Render passes are not used: opaque and cutout are recorded into separate command lists for
    // parallel recording, and a render pass may not span command lists.
}

// Creates a usable timeline fence. Exporting a Win32 handle is optional: if it fails,
// the local fence remains valid for CPU synchronization, but GPU semaphore interop is unavailable.
bool createFenceWithOptionalSharedHandle(ID3D12Device* device, ID3D12Fence** fence,
        HANDLE* sharedHandle, bool* sharedHandleAvailable) {
    *fence = nullptr;
    *sharedHandle = nullptr;
    *sharedHandleAvailable = false;

    HRESULT result = device->CreateFence(0, D3D12_FENCE_FLAG_SHARED, IID_PPV_ARGS(fence));
    if (FAILED(result)) {
        // Some drivers can create a local timeline fence even when a shareable fence is unsupported.
        result = device->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(fence));
        return SUCCEEDED(result);
    }

    result = device->CreateSharedHandle(*fence, nullptr, GENERIC_ALL, nullptr, sharedHandle);
    if (FAILED(result)) {
        *sharedHandle = nullptr;
        OutputDebugStringA("Direct: shared fence handle export failed; retaining the local fence for CPU-sync fallback.\n");
        return true;
    }

    *sharedHandleAvailable = true;
    return true;
}

// Espera com timeout; timeout e device removal são propagados ao Java para voltar ao vanilla.
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

// GL polls this fence before sampling the shared output; a timeout never fabricates completion.
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
            OutputDebugStringA("Direct: D3D12 interop fence missed its deadline; "
                    "the current frame will be discarded and vanilla rendering restored.\n");
        }
        if (renderer->watchedFenceValue == target) {
            renderer->watchedFenceValue = 0;
        }
    }
}

void recordWorkerLoop(InteropRenderer* renderer) {
    RecordWorker* worker = &renderer->worker;
    std::unique_lock<std::mutex> lock(worker->mutex);
    for (;;) {
        worker->condition.wait(lock, [worker] { return worker->stop || worker->pending; });
        if (worker->stop) {
            return;
        }
        FrameSlot* slot = worker->slot;
        lock.unlock();
        const bool result = recordTerrainList(renderer, slot->cutout, renderer->cutoutList,
                PassRole::Cutout);
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
UINT64 alignUp(UINT64 value, UINT64 alignment) {
    return (value + alignment - 1) & ~(alignment - 1);
}

bool tryAllocateArenaRange(ArenaPage* page, UINT64 bytes, UINT64* offset) {
    for (auto it = page->freeRanges.begin(); it != page->freeRanges.end(); ++it) {
        const UINT64 rangeStart = it->first;
        const UINT64 rangeBytes = it->second;
        const UINT64 alignedStart = alignUp(rangeStart, 256);
        const UINT64 prefixBytes = alignedStart - rangeStart;
        if (prefixBytes > rangeBytes || bytes > rangeBytes - prefixBytes) {
            continue;
        }
        const UINT64 suffixStart = alignedStart + bytes;
        const UINT64 suffixBytes = rangeBytes - prefixBytes - bytes;
        page->freeRanges.erase(it);
        if (prefixBytes != 0) {
            page->freeRanges.emplace(rangeStart, prefixBytes);
        }
        if (suffixBytes != 0) {
            page->freeRanges.emplace(suffixStart, suffixBytes);
        }
        *offset = alignedStart;
        return true;
    }
    return false;
}

bool allocateArenaRange(InteropRenderer* renderer, UINT64 requestedBytes, ArenaPage** pageOut,
        UINT64* offsetOut, UINT64* allocationBytesOut) {
    if (requestedBytes == 0 || requestedBytes > (std::numeric_limits<UINT64>::max() - 255)) {
        return false;
    }
    const UINT64 allocationBytes = alignUp(requestedBytes, 256);
    for (ArenaPage* page : renderer->arenaPages) {
        if (tryAllocateArenaRange(page, allocationBytes, offsetOut)) {
            *pageOut = page;
            *allocationBytesOut = allocationBytes;
            return true;
        }
    }

    const UINT64 pageBytes = (std::max)(renderer->terrainArenaPageBytes,
            alignUp(allocationBytes, 64 * 1024));
    if (pageBytes > renderer->maxTerrainArenaBytes
            || renderer->terrainArenaBytes > renderer->maxTerrainArenaBytes - pageBytes) {
        return false;
    }

    ArenaPage* page = new ArenaPage{};
    page->capacity = pageBytes;
    page->state = D3D12_RESOURCE_STATE_COMMON;
    if (!createBuffer(renderer, D3D12_HEAP_TYPE_DEFAULT, pageBytes, D3D12_RESOURCE_STATE_COMMON,
            &page->resource)) {
        delete page;
        return false;
    }
    page->freeRanges.emplace(0, pageBytes);
    renderer->arenaPages.push_back(page);
    renderer->terrainArenaBytes += pageBytes;
    if (!tryAllocateArenaRange(page, allocationBytes, offsetOut)) {
        return false;
    }
    *pageOut = page;
    *allocationBytesOut = allocationBytes;
    return true;
}

void freeArenaRange(ArenaPage* page, UINT64 offset, UINT64 bytes) {
    if (page == nullptr || bytes == 0) {
        return;
    }
    auto next = page->freeRanges.lower_bound(offset);
    if (next != page->freeRanges.begin()) {
        auto previous = std::prev(next);
        if (previous->first + previous->second == offset) {
            offset = previous->first;
            bytes += previous->second;
            page->freeRanges.erase(previous);
        }
    }
    next = page->freeRanges.lower_bound(offset);
    if (next != page->freeRanges.end() && offset + bytes == next->first) {
        bytes += next->second;
        page->freeRanges.erase(next);
    }
    page->freeRanges.emplace(offset, bytes);
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
    while (!renderer->retiredAllocations.empty() && completed != kFenceError
            && renderer->retiredAllocations.front().fenceValue <= completed) {
        const RetiredAllocation allocation = renderer->retiredAllocations.front();
        freeArenaRange(allocation.page, allocation.offset, allocation.bytes);
        renderer->retiredAllocations.pop_front();
    }
}

bool currentFrameSlotReady(InteropRenderer* renderer, JNIEnv* env, bool* ready) {
    if (renderer->uploadOpen) {
        *ready = true;
        return true;
    }
    const UINT64 completed = renderer->fence->GetCompletedValue();
    if (completed == kFenceError) {
        throwJava(env, "DX12 device was removed while checking the current frame slot.");
        return false;
    }
    *ready = renderer->slots[renderer->slotIndex].fenceValue <= completed;
    return true;
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
    renderer->retiredAllocations.clear();
    renderer->pendingRetireAllocations.clear();
}

// Abre (uma vez por frame) a lista de upload usando o alocador do slot do próximo frame.
bool ensureUploadOpen(InteropRenderer* renderer) {
    if (renderer->uploadOpen) {
        return true;
    }
    FrameSlot& slot = renderer->slots[renderer->slotIndex];
    // Java polls the frame slot before queuing uploads. Never block the render thread here:
    // if a caller misses that readiness check, defer the upload instead of waiting on an event.
    const UINT64 completed = renderer->fence->GetCompletedValue();
    if (completed == kFenceError) {
        renderer->gpuSyncFault.store(true);
        return false;
    }
    if (slot.fenceValue > completed
            || FAILED(slot.upload->Reset())
            || FAILED(renderer->uploadList->Reset(slot.upload, nullptr))) {
        return false;
    }
    slot.uploadOffset = 0;
    renderer->uploadOpen = true;
    return true;
}

bool reserveUploadRange(InteropRenderer* renderer, UINT64 bytes, UINT64 alignment, UINT64* offsetOut) {
    if (!ensureUploadOpen(renderer)) {
        return false;
    }
    FrameSlot& slot = renderer->slots[renderer->slotIndex];
    const UINT64 offset = alignUp(slot.uploadOffset, alignment);
    if (offset > slot.uploadCapacity || bytes > slot.uploadCapacity - offset) {
        return false;
    }
    slot.uploadOffset = offset + bytes;
    *offsetOut = offset;
    return true;
}

void deleteSection(TerrainSection* section) {
    delete section;
}

void retireSection(InteropRenderer* renderer, TerrainSection* section) {
    if (section->page != nullptr && section->allocationSize != 0) {
        // A fence value of 0 would be reclaimed by the next releaseCompleted() while the GPU may
        // still be reading this range. Tie the range to the last submitted frame instead.
        renderer->pendingRetireAllocations.push_back(
                {renderer->lastSignaledValue, section->page, section->allocationOffset, section->allocationSize});
    }
    section->page = nullptr;
    delete section;
}

void retireTerrainSections(InteropRenderer* renderer) {
    for (auto& entry : renderer->sections) {
        retireSection(renderer, entry.second);
    }
    renderer->sections.clear();
    renderer->sectionIndices.clear();
    renderer->sectionList.clear();
}

void releaseExtensionResources(InteropRenderer* renderer) {
    for (ID3D12Resource*& target : renderer->extensionTargets) {
        release(target);
    }
    release(renderer->extensionSrvHeap);
    release(renderer->extensionRtvHeap);
    renderer->extensionSrvIncrement = 0;
}

bool createExtensionResources(InteropRenderer* renderer, JNIEnv* env);

void releaseSizeResources(InteropRenderer* renderer) {
    release(renderer->wrappedColor);
    if (renderer->d3d11Context != nullptr) {
        renderer->d3d11Context->Flush();
    }
    releaseExtensionResources(renderer);
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
    // Create*View returns void; a failed descriptor creation surfaces as device removal.
    if (FAILED(renderer->device->GetDeviceRemovedReason())) {
        throwJava(env, "DX12 terrain color/depth descriptors could not be created.");
        return false;
    }

    D3D11_RESOURCE_FLAGS flags = {};
    flags.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    if (FAILED(renderer->d3d11On12->CreateWrappedResource(renderer->colorTarget, &flags,
            D3D12_RESOURCE_STATE_RENDER_TARGET, D3D12_RESOURCE_STATE_RENDER_TARGET,
            IID_PPV_ARGS(&renderer->wrappedColor)))) {
        throwJava(env, "DX12 terrain target could not be wrapped for the OpenGL bridge.");
        return false;
    }
    return createExtensionResources(renderer, env);
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
    release(renderer->extensionList);
    release(renderer->cutoutList);
    release(renderer->opaqueList);
    release(renderer->uploadList);
    for (FrameSlot& slot : renderer->slots) {
        if (slot.uploadBuffer != nullptr && slot.uploadMapped != nullptr) {
            const D3D12_RANGE writtenRange = {0, static_cast<SIZE_T>(slot.uploadCapacity)};
            slot.uploadBuffer->Unmap(0, &writtenRange);
            slot.uploadMapped = nullptr;
        }
        release(slot.uploadBuffer);
        release(slot.extension);
        release(slot.cutout);
        release(slot.opaque);
        release(slot.upload);
    }
    for (ArenaPage* page : renderer->arenaPages) {
        release(page->resource);
        delete page;
    }
    renderer->arenaPages.clear();
    renderer->terrainArenaBytes = 0;
    for (ExtensionPass& pass : renderer->extensionPasses) {
        release(pass.pipeline);
    }
    renderer->extensionPasses.clear();
    release(renderer->extensionRootSignature);
    release(renderer->terrainCommandSignature);
    release(renderer->cutoutPipeline);
    release(renderer->opaquePipeline);
    release(renderer->rootSignature);
    release(renderer->pipelineLibrary);
    release(renderer->device1);
    release(renderer->srvHeap);
    release(renderer->dsvHeap);
    release(renderer->rtvHeap);
    release(renderer->d3d11On12);
    release(renderer->d3d11Context);
    release(renderer->d3d11Device);
    closeHandle(renderer->sharedGlDoneFenceHandle);
    closeHandle(renderer->sharedFenceHandle);
    release(renderer->glDoneFence);
    release(renderer->cpuCopyFence);
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

HRESULT createCachedPipeline(InteropRenderer* renderer, LPCWSTR name,
        const D3D12_GRAPHICS_PIPELINE_STATE_DESC& description, ID3D12PipelineState** pipeline) {
    if (renderer->pipelineLibrary != nullptr
            && SUCCEEDED(renderer->pipelineLibrary->LoadGraphicsPipeline(name, &description,
                    IID_PPV_ARGS(pipeline)))) {
        return S_OK;
    }
    const HRESULT result = renderer->device->CreateGraphicsPipelineState(
            &description, IID_PPV_ARGS(pipeline));
    if (SUCCEEDED(result) && renderer->pipelineLibrary != nullptr) {
        renderer->pipelineLibrary->StorePipeline(name, *pipeline);
    }
    return result;
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
    // Preserve the original terrain winding convention used by the D3D12 pipeline.
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
    HRESULT result = createCachedPipeline(renderer, L"direct.terrain.opaque.v1", description,
            &renderer->opaquePipeline);
    if (SUCCEEDED(result)) {
        description.PS = {cutoutBytecode->GetBufferPointer(), cutoutBytecode->GetBufferSize()};
        result = createCachedPipeline(renderer, L"direct.terrain.cutout.v1", description,
                &renderer->cutoutPipeline);
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

bool createTerrainCommandSignature(InteropRenderer* renderer) {
    D3D12_INDIRECT_ARGUMENT_DESC arguments[4] = {};
    arguments[0].Type = D3D12_INDIRECT_ARGUMENT_TYPE_VERTEX_BUFFER_VIEW;
    arguments[0].VertexBuffer.Slot = 0;
    arguments[1].Type = D3D12_INDIRECT_ARGUMENT_TYPE_INDEX_BUFFER_VIEW;
    arguments[2].Type = D3D12_INDIRECT_ARGUMENT_TYPE_CONSTANT;
    arguments[2].Constant.RootParameterIndex = 1;
    arguments[2].Constant.DestOffsetIn32BitValues = 0;
    arguments[2].Constant.Num32BitValuesToSet = kDrawConstantCount;
    arguments[3].Type = D3D12_INDIRECT_ARGUMENT_TYPE_DRAW_INDEXED;

    D3D12_COMMAND_SIGNATURE_DESC description = {};
    description.ByteStride = sizeof(TerrainIndirectCommand);
    description.NumArgumentDescs = ARRAYSIZE(arguments);
    description.pArgumentDescs = arguments;
    const HRESULT result = renderer->device->CreateCommandSignature(&description, renderer->rootSignature,
            IID_PPV_ARGS(&renderer->terrainCommandSignature));
    if (FAILED(result)) {
        OutputDebugStringA("Direct: ExecuteIndirect command signature is unavailable; using direct draw recording.\n");
        release(renderer->terrainCommandSignature);
        return false;
    }
    return true;
}

bool createExtensionRootSignature(InteropRenderer* renderer) {
    D3D12_DESCRIPTOR_RANGE inputRange = {};
    inputRange.RangeType = D3D12_DESCRIPTOR_RANGE_TYPE_SRV;
    inputRange.NumDescriptors = 1;
    inputRange.BaseShaderRegister = 0;
    inputRange.OffsetInDescriptorsFromTableStart = 0;
    D3D12_ROOT_PARAMETER parameters[2] = {};
    parameters[0].ParameterType = D3D12_ROOT_PARAMETER_TYPE_32BIT_CONSTANTS;
    parameters[0].Constants.ShaderRegister = 0;
    parameters[0].Constants.Num32BitValues = kPassConstantCount;
    parameters[0].ShaderVisibility = D3D12_SHADER_VISIBILITY_PIXEL;
    parameters[1].ParameterType = D3D12_ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE;
    parameters[1].DescriptorTable.NumDescriptorRanges = 1;
    parameters[1].DescriptorTable.pDescriptorRanges = &inputRange;
    parameters[1].ShaderVisibility = D3D12_SHADER_VISIBILITY_PIXEL;
    D3D12_STATIC_SAMPLER_DESC sampler = {};
    sampler.Filter = D3D12_FILTER_MIN_MAG_MIP_LINEAR;
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
    ID3DBlob* serialized = nullptr;
    ID3DBlob* errors = nullptr;
    HRESULT result = D3D12SerializeRootSignature(&description, D3D_ROOT_SIGNATURE_VERSION_1,
            &serialized, &errors);
    if (SUCCEEDED(result)) {
        result = renderer->device->CreateRootSignature(0, serialized->GetBufferPointer(),
                serialized->GetBufferSize(), IID_PPV_ARGS(&renderer->extensionRootSignature));
    }
    if (errors != nullptr) {
        OutputDebugStringA(static_cast<const char*>(errors->GetBufferPointer()));
    }
    release(errors);
    release(serialized);
    return SUCCEEDED(result);
}

bool createExtensionResources(InteropRenderer* renderer, JNIEnv* env) {
    const UINT passCount = static_cast<UINT>(renderer->extensionPasses.size());
    if (passCount == 0) {
        return true;
    }

    // A chain of up to eight passes needs only two alternating intermediate color targets.
    constexpr UINT targetCount = 2;
    const D3D12_DESCRIPTOR_HEAP_DESC rtv = {
        D3D12_DESCRIPTOR_HEAP_TYPE_RTV, targetCount, D3D12_DESCRIPTOR_HEAP_FLAG_NONE, 0
    };
    // Slot 0 samples the terrain color; slots 1 and 2 sample the two ping-pong targets.
    const D3D12_DESCRIPTOR_HEAP_DESC srv = {
        D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV, targetCount + 1,
        D3D12_DESCRIPTOR_HEAP_FLAG_SHADER_VISIBLE, 0
    };
    if (FAILED(renderer->device->CreateDescriptorHeap(&rtv, IID_PPV_ARGS(&renderer->extensionRtvHeap)))
            || FAILED(renderer->device->CreateDescriptorHeap(&srv, IID_PPV_ARGS(&renderer->extensionSrvHeap)))) {
        throwJava(env, "Direct extension pass descriptor heaps could not be created.");
        return false;
    }
    renderer->extensionSrvIncrement = renderer->device->GetDescriptorHandleIncrementSize(
            D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV);
    renderer->extensionRtvIncrement = renderer->device->GetDescriptorHandleIncrementSize(
            D3D12_DESCRIPTOR_HEAP_TYPE_RTV);
    const D3D12_RESOURCE_DESC description = {
        D3D12_RESOURCE_DIMENSION_TEXTURE2D, 0, renderer->width, renderer->height, 1, 1,
        kColorFormat, {1, 0}, D3D12_TEXTURE_LAYOUT_UNKNOWN, D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET
    };
    D3D12_HEAP_PROPERTIES heap = {};
    heap.Type = D3D12_HEAP_TYPE_DEFAULT;
    D3D12_CLEAR_VALUE clear = {};
    clear.Format = kColorFormat;
    std::memcpy(clear.Color, kClearColor, sizeof(kClearColor));

    D3D12_CPU_DESCRIPTOR_HANDLE rtvHandle = renderer->extensionRtvHeap->GetCPUDescriptorHandleForHeapStart();
    D3D12_CPU_DESCRIPTOR_HANDLE srvHandle = renderer->extensionSrvHeap->GetCPUDescriptorHandleForHeapStart();
    D3D12_SHADER_RESOURCE_VIEW_DESC view = {};
    view.Format = kColorFormat;
    view.ViewDimension = D3D12_SRV_DIMENSION_TEXTURE2D;
    view.Shader4ComponentMapping = D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING;
    view.Texture2D.MipLevels = 1;

    renderer->device->CreateShaderResourceView(renderer->colorTarget, &view, srvHandle);
    srvHandle.ptr += renderer->extensionSrvIncrement;
    for (UINT index = 0; index < targetCount; ++index) {
        if (FAILED(renderer->device->CreateCommittedResource(&heap, D3D12_HEAP_FLAG_NONE, &description,
                D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE, &clear,
                IID_PPV_ARGS(&renderer->extensionTargets[index])))) {
            throwJava(env, "Direct extension ping-pong render target creation failed.");
            return false;
        }
        renderer->device->CreateRenderTargetView(renderer->extensionTargets[index], nullptr, rtvHandle);
        renderer->device->CreateShaderResourceView(renderer->extensionTargets[index], &view, srvHandle);
        rtvHandle.ptr += renderer->extensionRtvIncrement;
        srvHandle.ptr += renderer->extensionSrvIncrement;
    }
    // Create*View returns void; a failed descriptor creation surfaces as device removal.
    if (FAILED(renderer->device->GetDeviceRemovedReason())) {
        throwJava(env, "Direct extension pass descriptors could not be created.");
        return false;
    }
    return true;
}

bool configureExtensionPasses(InteropRenderer* renderer, const std::vector<std::string>& sources,
        JNIEnv* env) {
    if (sources.size() > kMaxExtensionPasses) {
        throwJava(env, "Direct supports at most eight extension render passes.");
        return false;
    }
    releaseExtensionResources(renderer);
    for (ExtensionPass& pass : renderer->extensionPasses) {
        release(pass.pipeline);
    }
    renderer->extensionPasses.clear();
    release(renderer->extensionRootSignature);
    if (sources.empty()) {
        return true;
    }
    if (!createExtensionRootSignature(renderer)) {
        throwJava(env, "Direct extension pass root signature creation failed.");
        return false;
    }
    ID3DBlob* vertex = nullptr;
    ID3DBlob* errors = nullptr;
    const HRESULT vertexResult = D3DCompile(kExtensionVertexShader, sizeof(kExtensionVertexShader) - 1,
            nullptr, nullptr, nullptr, "vsMain", "vs_5_1",
            D3DCOMPILE_ENABLE_STRICTNESS | D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, &vertex, &errors);
    if (errors != nullptr) {
        OutputDebugStringA(static_cast<const char*>(errors->GetBufferPointer()));
    }
    release(errors);
    if (FAILED(vertexResult)) {
        throwJava(env, "Direct extension pass vertex shader compilation failed.");
        return false;
    }

    D3D12_GRAPHICS_PIPELINE_STATE_DESC description = {};
    description.pRootSignature = renderer->extensionRootSignature;
    description.VS = {vertex->GetBufferPointer(), vertex->GetBufferSize()};
    description.BlendState.RenderTarget[0].RenderTargetWriteMask = D3D12_COLOR_WRITE_ENABLE_ALL;
    description.BlendState.RenderTarget[0].SrcBlend = D3D12_BLEND_ONE;
    description.BlendState.RenderTarget[0].DestBlend = D3D12_BLEND_ZERO;
    description.BlendState.RenderTarget[0].BlendOp = D3D12_BLEND_OP_ADD;
    description.BlendState.RenderTarget[0].SrcBlendAlpha = D3D12_BLEND_ONE;
    description.BlendState.RenderTarget[0].DestBlendAlpha = D3D12_BLEND_ZERO;
    description.BlendState.RenderTarget[0].BlendOpAlpha = D3D12_BLEND_OP_ADD;
    description.BlendState.RenderTarget[0].LogicOp = D3D12_LOGIC_OP_NOOP;
    description.SampleMask = UINT_MAX;
    description.RasterizerState.FillMode = D3D12_FILL_MODE_SOLID;
    description.RasterizerState.CullMode = D3D12_CULL_MODE_NONE;
    description.RasterizerState.DepthClipEnable = TRUE;
    description.DepthStencilState.DepthEnable = FALSE;
    description.DepthStencilState.DepthWriteMask = D3D12_DEPTH_WRITE_MASK_ZERO;
    description.DepthStencilState.DepthFunc = D3D12_COMPARISON_FUNC_ALWAYS;
    description.InputLayout = {nullptr, 0};
    description.PrimitiveTopologyType = D3D12_PRIMITIVE_TOPOLOGY_TYPE_TRIANGLE;
    description.NumRenderTargets = 1;
    description.RTVFormats[0] = kColorFormat;
    description.SampleDesc.Count = 1;

    for (const std::string& source : sources) {
        ID3DBlob* pixel = nullptr;
        errors = nullptr;
        const HRESULT compileResult = D3DCompile(source.data(), source.size(), nullptr, nullptr, nullptr,
                "main", "ps_5_1", D3DCOMPILE_ENABLE_STRICTNESS | D3DCOMPILE_OPTIMIZATION_LEVEL3,
                0, &pixel, &errors);
        if (errors != nullptr) {
            OutputDebugStringA(static_cast<const char*>(errors->GetBufferPointer()));
        }
        release(errors);
        if (FAILED(compileResult)) {
            release(vertex);
            throwJava(env, "Direct extension pass pixel shader compilation failed; inspect the native debug output.");
            return false;
        }
        description.PS = {pixel->GetBufferPointer(), pixel->GetBufferSize()};
        const std::wstring name = L"direct.extension."
                + std::to_wstring(std::hash<std::string>{}(source)) + L".v1";
        ID3D12PipelineState* pipeline = nullptr;
        const HRESULT pipelineResult = createCachedPipeline(renderer, name.c_str(), description, &pipeline);
        release(pixel);
        if (FAILED(pipelineResult)) {
            release(vertex);
            throwJava(env, "Direct extension pass pipeline state creation failed.");
            return false;
        }
        renderer->extensionPasses.push_back({source, pipeline});
    }
    release(vertex);
    return createExtensionResources(renderer, env);
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
                    IID_PPV_ARGS(&slot.cutout)))
                || FAILED(renderer->device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT,
                    IID_PPV_ARGS(&slot.extension)))) {
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
            || FAILED(renderer->device->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT, first.extension,
                nullptr, IID_PPV_ARGS(&renderer->extensionList)))
            || FAILED(renderer->uploadList->Close())
            || FAILED(renderer->opaqueList->Close())
            || FAILED(renderer->cutoutList->Close())
            || FAILED(renderer->extensionList->Close())) {
        return false;
    }
    return true;
}
bool createUploadRingBuffers(InteropRenderer* renderer, UINT64 capacity, JNIEnv* env) {
    const D3D12_RANGE noRead = {0, 0};
    for (FrameSlot& slot : renderer->slots) {
        if (!createBuffer(renderer, D3D12_HEAP_TYPE_UPLOAD, capacity,
                D3D12_RESOURCE_STATE_GENERIC_READ, &slot.uploadBuffer)) {
            throwJava(env, "Direct could not allocate a persistent D3D12 upload-ring buffer.");
            return false;
        }
        void* mapped = nullptr;
        if (FAILED(slot.uploadBuffer->Map(0, &noRead, &mapped))) {
            throwJava(env, "Direct could not map a persistent D3D12 upload-ring buffer.");
            return false;
        }
        slot.uploadMapped = static_cast<uint8_t*>(mapped);
        slot.uploadCapacity = capacity;
        slot.uploadOffset = 0;
    }
    return true;
}


void startRecordWorker(InteropRenderer* renderer) {
    try {
        renderer->worker.thread = std::thread(recordWorkerLoop, renderer);
        renderer->workerRunning = true;
    } catch (const std::system_error&) {
        renderer->workerRunning = false; // grava as duas listas na thread de render
    }
}

bool initializeInteropRenderer(InteropRenderer* renderer, jint width, jint height,
        bool parallelSectionRecording, jint uploadRingBufferMiB, jint terrainArenaPageMiB,
        jint maxTerrainArenaMiB, JNIEnv* env) {
    if (width <= 0 || height <= 0) {
        throwJava(env, "DX12/OpenGL interop requires a non-zero framebuffer size.");
        return false;
    }
    renderer->width = static_cast<UINT>(width);
    renderer->height = static_cast<UINT>(height);
    const UINT64 mebibyte = 1024ULL * 1024ULL;
    renderer->maxTerrainArenaBytes = static_cast<UINT64>((std::clamp)(maxTerrainArenaMiB, 64, 4096)) * mebibyte;
    renderer->terrainArenaPageBytes = (std::min)(
            static_cast<UINT64>((std::clamp)(terrainArenaPageMiB, 8, 256)) * mebibyte,
            renderer->maxTerrainArenaBytes);
    const UINT64 uploadRingBytes = static_cast<UINT64>((std::clamp)(uploadRingBufferMiB, 16, 512)) * mebibyte;
    renderer->uploadBarriers.reserve(64);
    renderer->uploadPages.reserve(16);
    renderer->pendingRetireAllocations.reserve(256);
    renderer->visible.reserve(256);
    renderer->sectionList.reserve(256);
    renderer->sectionIndices.reserve(256);
    if (FAILED(D3D12CreateDevice(nullptr, D3D_FEATURE_LEVEL_11_0, IID_PPV_ARGS(&renderer->device)))) {
        throwJava(env, "DX12/OpenGL interop could not create a Direct3D 12 device.");
        return false;
    }
    if (SUCCEEDED(renderer->device->QueryInterface(IID_PPV_ARGS(&renderer->device1)))) {
        if (SUCCEEDED(renderer->device1->CreatePipelineLibrary(nullptr, 0,
                IID_PPV_ARGS(&renderer->pipelineLibrary)))) {
            renderer->featureFlags |= 8u;
        }
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
    if (!createDescriptorHeaps(renderer) || !createCommandObjects(renderer)
            || !createUploadRingBuffers(renderer, uploadRingBytes, env)) {
        if (!env->ExceptionCheck()) {
            throwJava(env, "DX12 terrain descriptor heaps, command lists, or upload rings could not be created.");
        }
        return false;
    }
    inspectAdapter(renderer);
    if (!createTerrainPipelines(renderer, env)) {
        return false;
    }
    createTerrainCommandSignature(renderer); // optional optimization; direct draws remain the fallback
    if (!createSizeResources(renderer, env)) {
        return false;
    }
    bool sharedFenceAvailable = false;
    bool sharedGlDoneFenceAvailable = false;
    if (FAILED(renderer->device->CreateFence(0, D3D12_FENCE_FLAG_NONE,
                IID_PPV_ARGS(&renderer->cpuCopyFence)))
            || !createFenceWithOptionalSharedHandle(renderer->device, &renderer->fence,
                &renderer->sharedFenceHandle, &sharedFenceAvailable)
            || !createFenceWithOptionalSharedHandle(renderer->device, &renderer->glDoneFence,
                &renderer->sharedGlDoneFenceHandle, &sharedGlDoneFenceAvailable)) {
        throwJava(env, "DX12/OpenGL interop could not create its D3D12 timeline fences.");
        return false;
    }
    if (!sharedFenceAvailable) {
        OutputDebugStringA("Direct: optional output-fence handle was not exported; the output fence remains CPU-polled.\n");
    }
    if (!sharedGlDoneFenceAvailable) {
        OutputDebugStringA("Direct: OpenGL completion-fence handle was not exported; using polled GLsync CPU synchronization.\n");
    }
    renderer->fenceEvent = CreateEvent(nullptr, FALSE, FALSE, nullptr);
    renderer->watchdogEvent = CreateEvent(nullptr, FALSE, FALSE, nullptr);
    if (renderer->fenceEvent == nullptr || renderer->watchdogEvent == nullptr) {
        throwJava(env, "DX12/OpenGL interop could not create fence events.");
        return false;
    }
    try {
        renderer->watchdogThread = std::thread(fenceWatchdog, renderer);
    } catch (const std::system_error&) {
        OutputDebugStringA("Direct: fence watchdog thread could not start; native fence polling remains active.\n");
    }
    if (parallelSectionRecording) {
        startRecordWorker(renderer);
    }
    return true;
}

void extractFrustum(const float* m, Plane* planes) {
    auto at = [m](int row, int column) { return m[column * 4 + row]; };
    for (int index = 0; index < 6; ++index) {
        const int axis = index / 2;
        const float sign = index % 2 == 0 ? 1.0f : -1.0f;
        Plane plane = {};
        if (axis == 2 && sign > 0.0f) { // near: z >= 0 (clip D3D)
            plane = {at(2, 0), at(2, 1), at(2, 2), at(2, 3), 0.0f};
        } else {
            plane = {at(3, 0) + sign * at(axis, 0), at(3, 1) + sign * at(axis, 1),
                    at(3, 2) + sign * at(axis, 2), at(3, 3) + sign * at(axis, 3), 0.0f};
        }
        plane.length = std::sqrt(plane.a * plane.a + plane.b * plane.b + plane.c * plane.c);
        planes[index] = plane;
    }
}

bool sphereVisible(const Plane* planes, float x, float y, float z, float radius) {
    for (int index = 0; index < 6; ++index) {
        const Plane& p = planes[index];
        if (p.a * x + p.b * y + p.c * z + p.d < -radius * p.length) {
            return false;
        }
    }
    return true;
}

void collectVisibleSections(InteropRenderer* renderer, double cameraX, double cameraY, double cameraZ,
        float renderDistance) {
    renderer->visible.clear();
    renderer->visible.reserve(renderer->sectionList.size());
    Plane frustum[6];
    extractFrustum(renderer->viewProjection, frustum);
    const double maxDistanceSquared = static_cast<double>(renderDistance) * renderDistance;
    for (const TerrainSection* section : renderer->sectionList) {
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

// Opaco limpa o alvo; cutout preserva cor e depth.
// Um render pass não pode atravessar command lists (o runtime descarta a lista), e opaco/cutout
// são gravados em listas distintas para paralelismo. Por isso o alvo é ligado com OMSetRenderTargets.
void beginTerrainPass(InteropRenderer* renderer, ID3D12GraphicsCommandList* list, PassRole role) {
    const D3D12_CPU_DESCRIPTOR_HANDLE rtv = renderer->rtvHeap->GetCPUDescriptorHandleForHeapStart();
    const D3D12_CPU_DESCRIPTOR_HANDLE dsv = renderer->dsvHeap->GetCPUDescriptorHandleForHeapStart();
    const bool clear = role != PassRole::Cutout;
    list->OMSetRenderTargets(1, &rtv, FALSE, &dsv);
    if (clear) {
        list->ClearRenderTargetView(rtv, kClearColor, 0, nullptr);
        list->ClearDepthStencilView(dsv, D3D12_CLEAR_FLAG_DEPTH, 1.0f, 0, 0, nullptr);
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

bool prepareTerrainIndirectCommands(InteropRenderer* renderer, FrameSlot& slot, bool drawTerrain,
        bool useExecuteIndirect) {
    slot.indirectReady = false;
    slot.opaqueIndirectCount = 0;
    slot.cutoutIndirectCount = 0;
    slot.opaqueIndirectOffset = 0;
    slot.cutoutIndirectOffset = 0;
    if (!useExecuteIndirect || renderer->terrainCommandSignature == nullptr
            || slot.uploadBuffer == nullptr || slot.uploadMapped == nullptr) {
        return false;
    }

    if (!drawTerrain) {
        slot.indirectReady = true;
        return true;
    }

    UINT opaqueCount = 0;
    UINT cutoutCount = 0;
    for (const VisibleSection& entry : renderer->visible) {
        const TerrainSection* section = entry.section;
        opaqueCount += section->layerIndexCounts[0] != 0 ? 1u : 0u;
        cutoutCount += section->layerIndexCounts[1] != 0 ? 1u : 0u;
        cutoutCount += section->layerIndexCounts[2] != 0 ? 1u : 0u;
    }

    const UINT64 commandCount = static_cast<UINT64>(opaqueCount) + cutoutCount;
    if (commandCount == 0) {
        slot.indirectReady = true;
        return true;
    }
    if (commandCount > (std::numeric_limits<UINT64>::max() / sizeof(TerrainIndirectCommand))) {
        return false;
    }

    const UINT64 commandBytes = commandCount * sizeof(TerrainIndirectCommand);
    const UINT64 startOffset = alignUp(slot.uploadOffset, 256);
    if (startOffset > slot.uploadCapacity || commandBytes > slot.uploadCapacity - startOffset) {
        return false;
    }

    slot.opaqueIndirectOffset = startOffset;
    slot.cutoutIndirectOffset = startOffset + static_cast<UINT64>(opaqueCount) * sizeof(TerrainIndirectCommand);
    slot.opaqueIndirectCount = opaqueCount;
    slot.cutoutIndirectCount = cutoutCount;

    auto* commands = reinterpret_cast<TerrainIndirectCommand*>(slot.uploadMapped + startOffset);
    const UINT atlasIndex = renderer->atlasIndex;
    UINT opaqueIndex = 0;
    UINT cutoutIndex = opaqueCount;
    auto writeCommand = [&](TerrainIndirectCommand& command, const VisibleSection& entry,
            const TerrainSection* section, UINT layer) {
        command = {};
        command.vertexView = section->vertexView;
        command.indexView = section->indexView;
        std::memcpy(&command.constants[0], &entry.offset[0], sizeof(float));
        std::memcpy(&command.constants[1], &entry.offset[1], sizeof(float));
        std::memcpy(&command.constants[2], &entry.offset[2], sizeof(float));
        std::memcpy(&command.constants[3], &kLayerAlphaCutoff[layer], sizeof(float));
        command.constants[4] = atlasIndex;
        command.draw.IndexCountPerInstance = section->layerIndexCounts[layer];
        command.draw.InstanceCount = 1;
        command.draw.StartIndexLocation = section->layerStart[layer];
        command.draw.BaseVertexLocation = 0;
        command.draw.StartInstanceLocation = 0;
    };

    for (const VisibleSection& entry : renderer->visible) {
        const TerrainSection* section = entry.section;
        if (section->layerIndexCounts[0] != 0) {
            writeCommand(commands[opaqueIndex++], entry, section, 0);
        }
        for (UINT layer = 1; layer < kLayerCount; ++layer) {
            if (section->layerIndexCounts[layer] != 0) {
                writeCommand(commands[cutoutIndex++], entry, section, layer);
            }
        }
    }

    slot.uploadOffset = startOffset + commandBytes;
    slot.indirectReady = true;
    return true;
}

bool recordTerrainList(InteropRenderer* renderer, ID3D12CommandAllocator* allocator,
        ID3D12GraphicsCommandList* list, PassRole role) {
    if (FAILED(allocator->Reset()) || FAILED(list->Reset(allocator, nullptr))) {
        return false;
    }
    beginTerrainPass(renderer, list, role);
    const UINT firstLayer = role == PassRole::Opaque ? 0 : 1;
    const UINT lastLayer = role == PassRole::Opaque ? 1 : kLayerCount;
    if (role != PassRole::ClearOnly) {
        FrameSlot& slot = renderer->slots[renderer->slotIndex];
        const UINT indirectCount = role == PassRole::Opaque
                ? slot.opaqueIndirectCount : slot.cutoutIndirectCount;
        const UINT64 indirectOffset = role == PassRole::Opaque
                ? slot.opaqueIndirectOffset : slot.cutoutIndirectOffset;
        if (slot.indirectReady && renderer->terrainCommandSignature != nullptr) {
            if (indirectCount > 0) {
                list->ExecuteIndirect(renderer->terrainCommandSignature, indirectCount,
                        slot.uploadBuffer, indirectOffset, nullptr, 0);
            }
        } else {
            const UINT atlasIndex = renderer->atlasIndex;
            list->SetGraphicsRoot32BitConstants(1, 1, &atlasIndex, 4);
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
                        list->SetGraphicsRoot32BitConstants(1, 3, entry.offset, 0);
                        bound = true;
                    }
                    list->SetGraphicsRoot32BitConstants(1, 1, &kLayerAlphaCutoff[layer], 3);
                    list->DrawIndexedInstanced(section->layerIndexCounts[layer], 1, section->layerStart[layer], 0, 0);
                }
            }
        }
    }
    return SUCCEEDED(list->Close());
}

// Grava opaco na thread de render e cutout em paralelo na thread de gravação.
bool recordTerrain(InteropRenderer* renderer, FrameSlot& slot, bool drawTerrain) {
    if (!drawTerrain) {
        return recordTerrainList(renderer, slot.opaque, renderer->opaqueList, PassRole::ClearOnly);
    }
    if (!renderer->workerRunning) {
        return recordTerrainList(renderer, slot.opaque, renderer->opaqueList, PassRole::Opaque)
                && recordTerrainList(renderer, slot.cutout, renderer->cutoutList, PassRole::Cutout);
    }
    RecordWorker& worker = renderer->worker;
    {
        std::lock_guard<std::mutex> lock(worker.mutex);
        worker.slot = &slot;
        worker.pending = true;
    }
    worker.condition.notify_all();
    const bool opaque = recordTerrainList(renderer, slot.opaque, renderer->opaqueList, PassRole::Opaque);
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
    for (ArenaPage* page : renderer->uploadPages) {
        const D3D12_RESOURCE_BARRIER barrier = transition(page->resource,
                D3D12_RESOURCE_STATE_COPY_DEST, kTerrainBufferState);
        renderer->uploadList->ResourceBarrier(1, &barrier);
        page->state = kTerrainBufferState;
        page->uploadQueued = false;
    }
    renderer->uploadPages.clear();
    renderer->uploadOpen = false;
    if (FAILED(renderer->uploadList->Close())) {
        return false;
    }
    // Submissão separada: os buffers decaem para COMMON antes de serem lidos pelas listas de terreno.
    ID3D12CommandList* lists[] = {renderer->uploadList};
    renderer->queue->ExecuteCommandLists(1, lists);
    return true;
}

bool copyToInteropTexture(InteropRenderer* renderer) {
    renderer->d3d11On12->AcquireWrappedResources(&renderer->wrappedColor, 1);
    renderer->d3d11Context->CopyResource(renderer->interopTexture, renderer->wrappedColor);
    renderer->d3d11On12->ReleaseWrappedResources(&renderer->wrappedColor, 1);
    renderer->d3d11Context->Flush();
    return SUCCEEDED(renderer->d3d11Device->GetDeviceRemovedReason());
}

bool recordExtensionPasses(InteropRenderer* renderer, FrameSlot& slot, UINT64 frameIndex,
        float elapsedSeconds, const float* uniforms) {
    if (renderer->extensionPasses.empty()) {
        return true;
    }
    if (FAILED(slot.extension->Reset()) || FAILED(renderer->extensionList->Reset(slot.extension, nullptr))) {
        return false;
    }
    ID3D12GraphicsCommandList* list = renderer->extensionList;
    ID3D12DescriptorHeap* heaps[] = {renderer->extensionSrvHeap};
    list->SetDescriptorHeaps(1, heaps);
    list->SetGraphicsRootSignature(renderer->extensionRootSignature);
    const D3D12_VIEWPORT viewport = {0.0f, 0.0f, static_cast<float>(renderer->width),
            static_cast<float>(renderer->height), 0.0f, 1.0f};
    const D3D12_RECT scissor = {0, 0, static_cast<LONG>(renderer->width), static_cast<LONG>(renderer->height)};
    list->RSSetViewports(1, &viewport);
    list->RSSetScissorRects(1, &scissor);
    list->IASetPrimitiveTopology(D3D_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    const D3D12_RESOURCE_BARRIER colorForSampling = transition(renderer->colorTarget,
            D3D12_RESOURCE_STATE_RENDER_TARGET, D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE);
    list->ResourceBarrier(1, &colorForSampling);

    const D3D12_CPU_DESCRIPTOR_HANDLE rtvBase =
            renderer->extensionRtvHeap->GetCPUDescriptorHandleForHeapStart();
    const D3D12_GPU_DESCRIPTOR_HANDLE srvBase =
            renderer->extensionSrvHeap->GetGPUDescriptorHandleForHeapStart();
    for (UINT index = 0; index < renderer->extensionPasses.size(); ++index) {
        const UINT outputIndex = index & 1u;
        const UINT inputDescriptorIndex = index == 0 ? 0u : (((index - 1u) & 1u) + 1u);
        ID3D12Resource* output = renderer->extensionTargets[outputIndex];
        D3D12_CPU_DESCRIPTOR_HANDLE outputRtv = rtvBase;
        outputRtv.ptr += static_cast<SIZE_T>(outputIndex) * renderer->extensionRtvIncrement;
        D3D12_GPU_DESCRIPTOR_HANDLE inputSrv = srvBase;
        inputSrv.ptr += static_cast<UINT64>(inputDescriptorIndex) * renderer->extensionSrvIncrement;

        const D3D12_RESOURCE_BARRIER toRenderTarget = transition(output,
                D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE, D3D12_RESOURCE_STATE_RENDER_TARGET);
        list->ResourceBarrier(1, &toRenderTarget);
        list->OMSetRenderTargets(1, &outputRtv, FALSE, nullptr);
        list->SetPipelineState(renderer->extensionPasses[index].pipeline);
        list->SetGraphicsRootDescriptorTable(1, inputSrv);
        float constants[kPassConstantCount] = {};
        constants[0] = elapsedSeconds;
        constants[1] = static_cast<float>(renderer->width);
        constants[2] = static_cast<float>(renderer->height);
        constants[3] = static_cast<float>(frameIndex);
        std::memcpy(constants + 4, uniforms + index * kPassUniformFloatCount,
                kPassUniformFloatCount * sizeof(float));
        list->SetGraphicsRoot32BitConstants(0, kPassConstantCount, constants, 0);
        list->DrawInstanced(3, 1, 0, 0);
        const D3D12_RESOURCE_BARRIER toShaderResource = transition(output,
                D3D12_RESOURCE_STATE_RENDER_TARGET, D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE);
        list->ResourceBarrier(1, &toShaderResource);
    }

    ID3D12Resource* finalPass = renderer->extensionTargets[(renderer->extensionPasses.size() - 1u) & 1u];
    const D3D12_RESOURCE_BARRIER toCopy[] = {
        transition(renderer->colorTarget, D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE,
                D3D12_RESOURCE_STATE_COPY_DEST),
        transition(finalPass, D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE, D3D12_RESOURCE_STATE_COPY_SOURCE)
    };
    list->ResourceBarrier(ARRAYSIZE(toCopy), toCopy);
    list->CopyResource(renderer->colorTarget, finalPass);
    const D3D12_RESOURCE_BARRIER restore[] = {
        transition(renderer->colorTarget, D3D12_RESOURCE_STATE_COPY_DEST, D3D12_RESOURCE_STATE_RENDER_TARGET),
        transition(finalPass, D3D12_RESOURCE_STATE_COPY_SOURCE, D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE)
    };
    list->ResourceBarrier(ARRAYSIZE(restore), restore);
    return SUCCEEDED(list->Close());
}

bool renderInteropFrame(InteropRenderer* renderer, const float* viewProjection,
        double cameraX, double cameraY, double cameraZ, float renderDistance,
        UINT64 frameIndex, float elapsedSeconds, const float* passUniforms,
        bool useGpuSynchronization, bool useExecuteIndirect, JNIEnv* env,
        bool* gpuSynchronizationUsed, bool* frameSkipped) {
    if (frameIndex >= kFenceError - 1) {
        throwJava(env, "DX12/OpenGL interop fence timeline has exhausted its frame values.");
        return false;
    }
    const UINT64 glDoneValue = frameIndex;
    const UINT64 dxReadyValue = frameIndex + 1;
    FrameSlot& slot = renderer->slots[renderer->slotIndex];
    bool slotReady = false;
    if (!currentFrameSlotReady(renderer, env, &slotReady)) {
        return false;
    }
    if (!slotReady) {
        *frameSkipped = true;
        return true;
    }
    releaseCompleted(renderer);

    bool gpu = useGpuSynchronization && renderer->sharedGlDoneFenceHandle != nullptr
            && !renderer->gpuSyncFault.load();
    if (glDoneValue > 0) {
        if (FAILED(renderer->queue->Wait(renderer->glDoneFence, glDoneValue))) {
            renderer->gpuSyncFault.store(true);
            throwJava(env, "DX12 could not queue a wait for OpenGL's shared completion fence.");
            return false;
        }
        renderer->lastQueuedGlDoneValue = glDoneValue;
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
    prepareTerrainIndirectCommands(renderer, slot, drawTerrain, useExecuteIndirect);
    if (!recordTerrain(renderer, slot, drawTerrain)) {
        throwJava(env, "DX12 terrain command lists could not be recorded.");
        return false;
    }
    // Ambas as listas na mesma submissão e na mesma fila: o cutout preserva a cor/depth do opaco.
    ID3D12CommandList* lists[] = {renderer->opaqueList, renderer->cutoutList};
    renderer->queue->ExecuteCommandLists(drawTerrain ? 2 : 1, lists);
    if (!recordExtensionPasses(renderer, slot, frameIndex, elapsedSeconds, passUniforms)) {
        throwJava(env, "Direct extension pass command list could not be recorded.");
        return false;
    }
    if (!renderer->extensionPasses.empty()) {
        ID3D12CommandList* extensionLists[] = {renderer->extensionList};
        renderer->queue->ExecuteCommandLists(1, extensionLists);
    }
    if (!gpu) {
        if (renderer->cpuCopyFenceValue >= kFenceError - 1) {
            throwJava(env, "DX12 CPU-copy fence timeline has exhausted its values.");
            return false;
        }
        const UINT64 copyReadyValue = ++renderer->cpuCopyFenceValue;
        if (FAILED(renderer->queue->Signal(renderer->cpuCopyFence, copyReadyValue))
                || !waitFence(renderer, renderer->cpuCopyFence, copyReadyValue)) {
            throwJava(env, "DX12 CPU fallback could not wait for terrain rendering before the D3D11On12 copy.");
            return false;
        }
    }
    if (!copyToInteropTexture(renderer)) {
        throwJava(env, "D3D11On12 reported device removal while copying the completed terrain image.");
        return false;
    }

    HRESULT signalResult;
    {
        std::lock_guard<std::mutex> lock(renderer->watchdogMutex);
        signalResult = renderer->queue->Signal(renderer->fence, dxReadyValue);
        if (SUCCEEDED(signalResult)) {
            renderer->lastSignaledValue = dxReadyValue;
            renderer->watchedFenceValue = dxReadyValue;
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
    for (const RetiredAllocation& allocation : renderer->pendingRetireAllocations) {
        renderer->retiredAllocations.push_back(
                {dxReadyValue, allocation.page, allocation.offset, allocation.bytes});
    }
    renderer->pendingRetireAllocations.clear();
    renderer->watchdogCondition.notify_one();
    *gpuSynchronizationUsed = gpu;
    return true;
}

bool uploadAtlas(InteropRenderer* renderer, const void* const* levelData, jsize levelCount,
        UINT width, UINT height, bool compress, UINT bc7EncoderThreads, JNIEnv* env) {
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
                    encoded[level].data(), bc7EncoderThreads);
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
        // A mapped resource must be unmapped before Release.
        if (upload != nullptr && mapped != nullptr) {
            upload->Unmap(0, nullptr);
        }
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
Java_com_redondoguibi_direct_NativeDx12_initializeInterop(JNIEnv* env, jclass, jint width, jint height,
        jboolean parallelSectionRecording, jint uploadRingBufferMiB, jint terrainArenaPageMiB,
        jint maxTerrainArenaMiB) {
    destroyInteropRenderer(g_interopRenderer);
    g_interopRenderer = nullptr;
    InteropRenderer* renderer = new InteropRenderer{};
    if (!initializeInteropRenderer(renderer, width, height, parallelSectionRecording == JNI_TRUE,
            uploadRingBufferMiB, terrainArenaPageMiB, maxTerrainArenaMiB, env)) {
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

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_configureRenderPasses(JNIEnv* env, jclass, jobjectArray sources) {
    InteropRenderer* renderer = g_interopRenderer;
    const jsize count = sources != nullptr ? env->GetArrayLength(sources) : 0;
    if (renderer == nullptr || count > static_cast<jsize>(kMaxExtensionPasses)) {
        throwJava(env, "Direct extension pass configuration is invalid.");
        return;
    }
    if (!waitFence(renderer, renderer->fence, renderer->lastSignaledValue)) {
        throwJava(env, "DX12 timed out before reconfiguring extension render passes.");
        return;
    }
    std::vector<std::string> passSources;
    passSources.reserve(static_cast<size_t>(count));
    for (jsize index = 0; index < count; ++index) {
        auto source = static_cast<jstring>(env->GetObjectArrayElement(sources, index));
        if (source == nullptr) {
            throwJava(env, "Direct extension pass shader source must not be null.");
            return;
        }
        const char* text = env->GetStringUTFChars(source, nullptr);
        if (text == nullptr) {
            env->DeleteLocalRef(source);
            return;
        }
        passSources.emplace_back(text);
        env->ReleaseStringUTFChars(source, text);
        env->DeleteLocalRef(source);
        if (env->ExceptionCheck()) {
            return;
        }
    }
    configureExtensionPasses(renderer, passSources, env);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_redondoguibi_direct_NativeDx12_interopAdapterName(JNIEnv* env, jclass) {
    if (g_interopRenderer == nullptr) {
        return nullptr;
    }
    return env->NewStringUTF(g_interopRenderer->adapterName.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_redondoguibi_direct_NativeDx12_interopFeatureFlags(JNIEnv*, jclass) {
    return g_interopRenderer != nullptr ? static_cast<jint>(g_interopRenderer->featureFlags) : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_redondoguibi_direct_NativeDx12_isCurrentFrameSlotReady(JNIEnv* env, jclass) {
    if (g_interopRenderer == nullptr) {
        throwJava(env, "DX12/OpenGL interop is not initialized.");
        return JNI_FALSE;
    }
    bool ready = false;
    if (!currentFrameSlotReady(g_interopRenderer, env, &ready)) {
        return JNI_FALSE;
    }
    if (ready) {
        // Reclaim completed arena ranges before Java starts staging this frame's chunk uploads.
        releaseCompleted(g_interopRenderer);
        if (!g_interopRenderer->uploadOpen) {
            g_interopRenderer->slots[g_interopRenderer->slotIndex].uploadOffset = 0;
        }
    }
    return ready ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_redondoguibi_direct_NativeDx12_renderInterop(JNIEnv* env, jclass, jfloatArray matrix,
        jdouble cameraX, jdouble cameraY, jdouble cameraZ, jfloat renderDistance,
        jlong frameIndex, jfloat elapsedSeconds, jfloatArray passUniforms,
        jboolean useGpuSynchronization, jboolean useExecuteIndirect) {
    if (g_interopRenderer == nullptr) {
        throwJava(env, "DX12/OpenGL interop is not initialized.");
        return -1;
    }
    const jsize expectedUniforms = static_cast<jsize>(
            g_interopRenderer->extensionPasses.size() * kPassUniformFloatCount);
    if (matrix == nullptr || env->GetArrayLength(matrix) != 16 || frameIndex < 0
            || (expectedUniforms > 0 && (passUniforms == nullptr
                || env->GetArrayLength(passUniforms) != expectedUniforms))) {
        throwJava(env, "DX12 terrain rendering received an invalid matrix or frame index.");
        return -1;
    }
    jfloat values[16];
    env->GetFloatArrayRegion(matrix, 0, 16, values);
    if (env->ExceptionCheck()) {
        return -1;
    }
    float uniformValues[kMaxExtensionPasses * kPassUniformFloatCount];
    if (expectedUniforms > 0) {
        env->GetFloatArrayRegion(passUniforms, 0, expectedUniforms, uniformValues);
        if (env->ExceptionCheck()) {
            return -1;
        }
    }
    bool gpuUsed = false;
    bool frameSkipped = false;
    if (!renderInteropFrame(g_interopRenderer, values, cameraX, cameraY, cameraZ, renderDistance,
            static_cast<UINT64>(frameIndex), elapsedSeconds,
            expectedUniforms > 0 ? uniformValues : nullptr,
            useGpuSynchronization == JNI_TRUE, useExecuteIndirect == JNI_TRUE,
            env, &gpuUsed, &frameSkipped)) {
        return -1;
    }
    if (frameSkipped) {
        return 2;
    }
    return gpuUsed ? 1 : 0;
}

// 1 means complete, 0 means pending, and -1 means device/fence failure.
extern "C" JNIEXPORT jint JNICALL
Java_com_redondoguibi_direct_NativeDx12_pollInteropFence(JNIEnv*, jclass, jlong value) {
    InteropRenderer* renderer = g_interopRenderer;
    if (renderer == nullptr || value < 0 || renderer->gpuSyncFault.load()) {
        return -1;
    }
    const UINT64 completed = renderer->fence->GetCompletedValue();
    if (completed == kFenceError) {
        renderer->gpuSyncFault.store(true);
        return -1;
    }
    return completed >= static_cast<UINT64>(value) ? 1 : 0;
}

// CPU fallback for a frame that was skipped or whose GLsync has completed.
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

    // Suballocate section data from reusable GPU pages and stage it through the current frame's upload ring.
    const UINT64 totalBytes = static_cast<UINT64>(vertexBytes + indexBytes);
    ArenaPage* page = nullptr;
    UINT64 arenaOffset = 0;
    UINT64 arenaBytes = 0;
    UINT64 uploadOffset = 0;
    if (!allocateArenaRange(renderer, totalBytes, &page, &arenaOffset, &arenaBytes)) {
        throwJava(env, "Direct terrain GPU arena is full; increase maxTerrainArenaMiB.");
        return;
    }
    if (!ensureUploadOpen(renderer)) {
        freeArenaRange(page, arenaOffset, arenaBytes);
        throwJava(env, "Direct could not open the current frame's upload command list.");
        return;
    }
    const bool useUploadRing = reserveUploadRange(renderer, totalBytes, 256, &uploadOffset);

    if (!page->uploadQueued) {
        if (page->state == kTerrainBufferState) {
            const D3D12_RESOURCE_BARRIER toCopy = transition(page->resource,
                    kTerrainBufferState, D3D12_RESOURCE_STATE_COPY_DEST);
            renderer->uploadList->ResourceBarrier(1, &toCopy);
        } else if (page->state != D3D12_RESOURCE_STATE_COMMON) {
            freeArenaRange(page, arenaOffset, arenaBytes);
            throwJava(env, "Direct terrain arena page entered an unexpected resource state.");
            return;
        }
        page->state = D3D12_RESOURCE_STATE_COPY_DEST;
        page->uploadQueued = true;
        renderer->uploadPages.push_back(page);
    }

    if (useUploadRing) {
        FrameSlot& uploadSlot = renderer->slots[renderer->slotIndex];
        std::memcpy(uploadSlot.uploadMapped + uploadOffset, vertices, static_cast<size_t>(vertexBytes));
        std::memcpy(uploadSlot.uploadMapped + uploadOffset + vertexBytes, indices, static_cast<size_t>(indexBytes));
        renderer->uploadList->CopyBufferRegion(page->resource, arenaOffset,
                uploadSlot.uploadBuffer, uploadOffset, totalBytes);
    } else {
        // Oversized per-frame batches use a one-shot staging resource, retired behind this frame's fence.
        ID3D12Resource* upload = nullptr;
        void* mapped = nullptr;
        const D3D12_RANGE noRead = {0, 0};
        if (!createBuffer(renderer, D3D12_HEAP_TYPE_UPLOAD, totalBytes,
                    D3D12_RESOURCE_STATE_GENERIC_READ, &upload)
                || FAILED(upload->Map(0, &noRead, &mapped))) {
            release(upload);
            freeArenaRange(page, arenaOffset, arenaBytes);
            throwJava(env, "Direct could not allocate overflow staging memory for a terrain section.");
            return;
        }
        std::memcpy(mapped, vertices, static_cast<size_t>(vertexBytes));
        std::memcpy(static_cast<uint8_t*>(mapped) + vertexBytes, indices, static_cast<size_t>(indexBytes));
        const D3D12_RANGE writtenRange = {0, static_cast<SIZE_T>(totalBytes)};
        upload->Unmap(0, &writtenRange);
        renderer->uploadList->CopyBufferRegion(page->resource, arenaOffset, upload, 0, totalBytes);
        retire(renderer, upload);
    }

    TerrainSection* section = new TerrainSection{};
    section->key = key;
    section->x = sectionX;
    section->y = sectionY;
    section->z = sectionZ;
    section->page = page;
    section->allocationOffset = arenaOffset;
    section->allocationSize = arenaBytes;
    const D3D12_GPU_VIRTUAL_ADDRESS address = page->resource->GetGPUVirtualAddress() + arenaOffset;
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
        TerrainSection* previous = existing->second;
        const auto index = renderer->sectionIndices.find(key);
        if (index != renderer->sectionIndices.end() && index->second < renderer->sectionList.size()
                && renderer->sectionList[index->second] == previous) {
            renderer->sectionList[index->second] = section;
        } else {
            // Repair the auxiliary index only if its invariant was already broken.
            const auto position = std::find(renderer->sectionList.begin(), renderer->sectionList.end(), previous);
            if (position != renderer->sectionList.end()) {
                const size_t repairedIndex = static_cast<size_t>(position - renderer->sectionList.begin());
                renderer->sectionList[repairedIndex] = section;
                renderer->sectionIndices[key] = repairedIndex;
            } else {
                renderer->sectionIndices[key] = renderer->sectionList.size();
                renderer->sectionList.push_back(section);
            }
        }
        retireSection(renderer, previous);
        existing->second = section;
    } else {
        renderer->sections.emplace(key, section);
        renderer->sectionIndices.emplace(key, renderer->sectionList.size());
        renderer->sectionList.push_back(section);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_updateTerrainAtlas(JNIEnv* env, jclass, jobjectArray levels,
        jint width, jint height, jboolean compress, jint bc7EncoderThreads) {
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
    const UINT encoderThreads = static_cast<UINT>((std::clamp)(bc7EncoderThreads, 1, 16));
    uploadAtlas(renderer, data, levelCount, static_cast<UINT>(width), static_cast<UINT>(height),
            compress == JNI_TRUE, encoderThreads, env);
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_removeTerrainSection(JNIEnv*, jclass, jlong key) {
    if (g_interopRenderer == nullptr) {
        return;
    }
    auto existing = g_interopRenderer->sections.find(key);
    if (existing != g_interopRenderer->sections.end()) {
        TerrainSection* section = existing->second;
        auto& renderer = *g_interopRenderer;
        const auto index = renderer.sectionIndices.find(key);
        size_t removeIndex = renderer.sectionList.size();
        if (index != renderer.sectionIndices.end() && index->second < renderer.sectionList.size()
                && renderer.sectionList[index->second] == section) {
            removeIndex = index->second;
        } else {
            // Repair path is intentionally linear; normal section removal stays O(1).
            const auto position = std::find(renderer.sectionList.begin(), renderer.sectionList.end(), section);
            if (position != renderer.sectionList.end()) {
                removeIndex = static_cast<size_t>(position - renderer.sectionList.begin());
            }
        }
        renderer.sectionIndices.erase(key);
        if (removeIndex < renderer.sectionList.size()) {
            TerrainSection* moved = renderer.sectionList.back();
            renderer.sectionList[removeIndex] = moved;
            renderer.sectionList.pop_back();
            if (removeIndex < renderer.sectionList.size()) {
                renderer.sectionIndices[moved->key] = removeIndex;
            }
        }
        retireSection(g_interopRenderer, section);
        renderer.sections.erase(existing);
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
