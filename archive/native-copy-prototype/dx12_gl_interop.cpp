#include <jni.h>
#include <windows.h>
#include <d3d11.h>
#include <d3d11on12.h>
#include <d3d12.h>
#include <d3dcompiler.h>
#include <dxgi1_4.h>
#include <DirectXMath.h>
#include <array>
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
#include <unordered_set>
#include <vector>

#include "bc7_encoder.h"

namespace {

constexpr UINT64 kFenceError = std::numeric_limits<UINT64>::max();
constexpr DWORD kFenceWaitTimeoutMs = 5000;
constexpr UINT kVertexStride = 20;
constexpr UINT kLayerCount = 4;
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
constexpr float kLayerAlphaCutoff[kLayerCount] = {-1.0f, 0.5f, 0.1f, -1.0f};

constexpr char kExtensionVertexShader[] = R"(
struct VertexOutput { float4 position : SV_Position; };
VertexOutput vsMain(uint vertexId : SV_VertexID) {
    float2 position = float2((vertexId << 1) & 2, vertexId & 2);
    VertexOutput output;
    output.position = float4(position * float2(2.0, -2.0) + float2(-1.0, 1.0), 0.0, 1.0);
    return output;
}
)";

// V2, 20 bytes: posição/light/cor/UV (16), normal SNORM8 xyz e material/flag (4).
constexpr char kTerrainShader[] = R"(
cbuffer FrameConstants : register(b0) { float4x4 viewProjection; float4 fogColor; float4 fogParams; float4 colorModulator; };
cbuffer DrawConstants : register(b1) { float3 sectionOffset; float alphaCutoff; uint atlasIndex; };
Texture2D atlasTextures[ATLAS_SLOTS] : register(t0);
SamplerState atlasSampler : register(s0);
SamplerState lightSampler : register(s1);
cbuffer GeometryConstants : register(b2) { float4x4 shadowVP; float4 strengths; float4 sunTime; float4 geometryParams; float4x4 inverseVP; float4x4 worldVP; float4 cameraPhase; };
Texture2D SceneColor : register(t64);
Texture2D<float> SceneDepth : register(t65);
Texture2D<float> ShadowMap : register(t66);
SamplerComparisonState shadowSampler : register(s2);
// Integer spatial harmonics keep the camera's 4096-block phase wrap continuous.
static const float phaseUnit=6.28318530718/4096.0;
struct VertexInput { uint4 position : POSITION; float4 color : COLOR; float2 uv : TEXCOORD0;
#if DIRECT_GEOMETRY
    float4 normal:NORMAL; uint material:MATERIAL;
#endif
};
struct VertexOutput { float4 position : SV_Position; float4 color : COLOR; float2 uv : TEXCOORD0; float distance : TEXCOORD1;
#if DIRECT_GEOMETRY
    float3 world:TEXCOORD2; float3 normal:TEXCOORD3; nointerpolation uint material:TEXCOORD4;
#endif
};
VertexOutput vsMain(VertexInput input) {
    VertexOutput output;
    float3 local = float3(input.position.xyz) * (1.0 / 1024.0) - 16.0;
    float3 world=local+sectionOffset;
#if DIRECT_GEOMETRY
    uint material=input.material&127;
    if(material==5 && strengths.y>0 && (input.material&128)!=0)
        world.y+=sin(((world.x+cameraPhase.x)*847+(world.z+cameraPhase.z)*587)*phaseUnit+sunTime.w*1.4)*strengths.y*.015;
    if(strengths.w>0 && (material==3 || material==8 || material==9)){
        float weight=material==8?((input.material&128)!=0?1.0:0.0):1.0;
        float wave=sin(((world.x+cameraPhase.x)*359+(world.z+cameraPhase.z)*254)*phaseUnit+sunTime.w*1.8);
        world.xz+=float2(wave,sin(wave+sunTime.w))*strengths.w*.06*weight;
    }
    output.world=world;output.normal=input.normal.xyz;output.material=material;
#endif
    output.position = mul(viewProjection, float4(world, 1.0));
    float2 lightUv = float2(input.position.w & 255, input.position.w >> 8) / 256.0;
    output.color = input.color * atlasTextures[63].SampleLevel(lightSampler, clamp(lightUv, 0.5 / 16.0, 15.5 / 16.0), 0);
    float3 pos = local + sectionOffset;
    output.distance = fogParams.z == 0 ? length(pos) : max(length(pos.xz), abs(pos.y));
    // glGetTexImage's first (bottom) row is uploaded as D3D12 row 0, so vanilla atlas UVs
    // address the same texels without a vertical flip.
    output.uv = input.uv;
    return output;
}
struct PixelOutput { float4 color : SV_Target0; };
PixelOutput psMain(VertexOutput input) {
    float4 texel;
    // The vanilla cutout layer (0.1 alpha cutoff) does not use mipmaps.
    if (alphaCutoff > 0.05 && alphaCutoff < 0.2) {
        texel = atlasTextures[atlasIndex].SampleLevel(atlasSampler, input.uv, 0);
    } else {
        texel = atlasTextures[atlasIndex].Sample(atlasSampler, input.uv);
    }
    float4 result = texel * input.color * colorModulator;
#ifdef DIRECT_CUTOUT
    clip(result.a - alphaCutoff);
#endif
    if (input.distance > fogParams.x) {
        float fog = input.distance < fogParams.y ? smoothstep(fogParams.x, fogParams.y, input.distance) : 1.0;
        result.rgb = lerp(result.rgb, fogColor.rgb, fog * fogColor.a);
    }
#if DIRECT_GEOMETRY
    if(strengths.x>0 && geometryParams.x>.5 && geometryParams.y>.02){
        float4 lightClip=mul(shadowVP,float4(input.world,1));float3 q=lightClip.xyz/lightClip.w;
        float2 uv=q.xy*float2(.5,-.5)+.5;
        if(all(uv>0)&&all(uv<1)&&q.z>0&&q.z<1){
            float visibility=ShadowMap.SampleCmpLevelZero(shadowSampler,uv,q.z-.0012);
            float edge=saturate(min(min(uv.x,uv.y),min(1-uv.x,1-uv.y))*12);
            result.rgb*=1-(1-visibility)*strengths.x*.65*geometryParams.y*(1-geometryParams.z*.7)*edge;
        }
    }
#endif
    PixelOutput output;
    output.color = float4(result.rgb, 1.0);
    return output;
}
void shadowMain(VertexOutput input) {
    if(alphaCutoff>0)clip(atlasTextures[atlasIndex].Sample(atlasSampler,input.uv).a-alphaCutoff);
}
#if DIRECT_GEOMETRY
float3 worldFromDepth(float2 uv,float d){float4 p=mul(inverseVP,float4(uv*float2(2,-2)+float2(-1,1),d,1));return p.xyz/max(abs(p.w),.00001)*sign(p.w);}
float4 psTranslucent(VertexOutput input):SV_Target0 {
    float4 base=atlasTextures[atlasIndex].Sample(atlasSampler,input.uv)*input.color*colorModulator;
    clip(base.a-.001);
    if(input.material==5 && (strengths.y>0 || strengths.z>0)){
        uint width,height;SceneColor.GetDimensions(width,height);float2 uv=input.position.xy/float2(width,height);
        float3 normal=normalize(cross(ddx(input.world),ddy(input.world)));
        if(dot(normal,-input.world)<0)normal=-normal;
        float2 wave=float2(sin((input.world.x+cameraPhase.x)*1304*phaseUnit+sunTime.w),cos((input.world.z+cameraPhase.z)*1499*phaseUnit+sunTime.w*1.2));
        normal=normalize(normal+float3(wave.x,0,wave.y)*strengths.y*.12);
        float3 view=normalize(input.world);float fresnel=.02+.98*pow(1-saturate(dot(-view,normal)),5);
        if(strengths.y>0){
            float2 refracted=saturate(uv+wave*strengths.y*.004);
            float sceneD=SceneDepth.Load(int3(clamp(int2(refracted*float2(width,height)),int2(0,0),int2(width-1,height-1)),0));
            if(sceneD<input.position.z)refracted=uv;
            base.rgb=lerp(base.rgb,SceneColor.SampleLevel(atlasSampler,refracted,0).rgb,strengths.y*.4);
        }
        if(strengths.z>0){
            float3 reflected=fogColor.rgb;float3 direction=reflect(view,normal);bool hit=false;
            int steps=geometryParams.w<.5?6:geometryParams.w<1.5?10:16;
            for(int i=1;i<=16;i++){
                if(i>steps)break;float distance=.2+float(i)*float(i)*.08;
                float3 ray=input.world+normal*.04+direction*distance;float4 clipP=mul(worldVP,float4(ray,1));if(clipP.w<=0)break;
                float3 projected=clipP.xyz/clipP.w;float2 hitUV=projected.xy*float2(.5,-.5)+.5;
                if(any(hitUV<0)||any(hitUV>1))break;
                int2 pixel=clamp(int2(hitUV*float2(width,height)),int2(0,0),int2(width-1,height-1));float d=SceneDepth.Load(int3(pixel,0));
                if(d<1 && projected.z>d && length(worldFromDepth(hitUV,d)-ray)<.6){reflected=SceneColor.SampleLevel(atlasSampler,hitUV,0).rgb;hit=true;break;}
            }
            base.rgb=lerp(base.rgb,reflected,saturate(fresnel*strengths.z*(hit?1:.5)));
        }
    }
    if(input.distance>fogParams.x){float fog=input.distance<fogParams.y?smoothstep(fogParams.x,fogParams.y,input.distance):1;base.rgb=lerp(base.rgb,fogColor.rgb,fog*fogColor.a);}
    return base;
}
#endif

)";

struct ArenaPage {
    ID3D12Resource* resource;
    UINT64 capacity;
    D3D12_RESOURCE_STATES state;
    bool uploadQueued;
    std::map<UINT64, UINT64> freeRanges;
};

struct TransparentQuad { float center[3]; std::array<UINT,6> indices; };
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
    std::vector<TransparentQuad> transparent;
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
    ID3D12CommandAllocator* finish;
    ID3D12CommandAllocator* shadow;
    ID3D12CommandAllocator* translucent;
    ID3D12CommandAllocator* interopCopy;
    ID3D12Resource* uploadBuffer;
    uint8_t* uploadMapped;
    ID3D12Resource* passConstants;
    uint8_t* passConstantsMapped;
    UINT64 uploadCapacity;
    UINT64 uploadOffset;
    UINT64 opaqueIndirectOffset;
    UINT64 cutoutIndirectOffset;
    UINT opaqueIndirectCount;
    UINT cutoutIndirectCount;
    bool indirectReady;
    bool timestampValid;
    UINT drawCount;
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
    ID3D12Fence* glDoneFence;
    HANDLE fenceEvent;
    HANDLE watchdogEvent;
    HANDLE sharedFenceHandle;
    HANDLE sharedGlDoneFenceHandle;
    ID3D12Resource* directMemoryProbe;
    HANDLE directMemoryProbeHandle;
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
    ID3D11On12Device2* d3d11On12v2;
    ID3D12Fence* exportFence;
    UINT64 exportValue;
    bool nativeCopy;
    bool nativeCopyRejected;
    ID3D12GraphicsCommandList* interopCopyList;
    ID3D11Texture2D* interopTexture;
    ID3D11Resource* wrappedColor;
    ID3D11Texture2D* interopDepth;
    ID3D11Resource* wrappedDepth;
    ID3D12Resource* lightmap;
    std::vector<jlong> visibleKeys;
    std::unordered_set<jlong> shadowKeys;
    bool useShadowKeys;
    bool useVisibleKeys;
    bool exactVisibleCoverage;
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
    ID3D12PipelineState* fastOpaquePipeline;
    ID3D12PipelineState* fastCutoutPipeline;
    bool terrainEffectsActive;
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
    ID3D12GraphicsCommandList* finishList;
    ID3D12QueryHeap* timestampHeap;
    ID3D12Resource* timestampReadback;
    UINT64 timestampFrequency;
    bool profiling;
    float geometrySettings[10];
    float geometryConstants[64];
    float cameraOffset[3];
    ID3D12Resource* shadowMap;
    ID3D12DescriptorHeap* shadowDsv;
    UINT shadowSize;
    ID3D12PipelineState* shadowPipeline;
    ID3D12PipelineState* translucentPipeline;
    ID3D12GraphicsCommandList* shadowList;
    ID3D12GraphicsCommandList* translucentList;
    ID3D12Resource* sceneColor;
    ID3D12Resource* sceneDepth;
    bool shadowRecorded;
    UINT shadowDrawCount;

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
    UINT64 translucentIndexBytes;
    float viewProjection[28];
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
    renderer->visible.clear();renderer->translucentIndexBytes=0;
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
    release(renderer->wrappedDepth);
    if (renderer->d3d11Context != nullptr) {
        renderer->d3d11Context->Flush();
    }
    releaseExtensionResources(renderer);
    release(renderer->interopDepth);
    release(renderer->depthTarget);
    release(renderer->shadowMap);release(renderer->shadowDsv);release(renderer->sceneColor);release(renderer->sceneDepth);renderer->shadowSize=0;
    if(renderer->srvHeap){
        D3D12_SHADER_RESOURCE_VIEW_DESC view={};view.ViewDimension=D3D12_SRV_DIMENSION_TEXTURE2D;view.Shader4ComponentMapping=D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING;view.Texture2D.MipLevels=1;
        auto handle=renderer->srvHeap->GetCPUDescriptorHandleForHeapStart();handle.ptr+=64*renderer->srvIncrement;
        for(UINT slot=64;slot<=66;slot++){view.Format=slot==64?kColorFormat:DXGI_FORMAT_R32_FLOAT;renderer->device->CreateShaderResourceView(nullptr,&view,handle);handle.ptr+=renderer->srvIncrement;}
    }
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

    color.Format = DXGI_FORMAT_R32_FLOAT;
    if (FAILED(renderer->d3d11Device->CreateTexture2D(&color, nullptr, &renderer->interopDepth))) {
        throwJava(env, "Could not create the shared terrain depth image.");
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
    depth.Format = DXGI_FORMAT_R32_TYPELESS;
    depth.Flags = D3D12_RESOURCE_FLAG_ALLOW_DEPTH_STENCIL;
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
    D3D12_DEPTH_STENCIL_VIEW_DESC depthView = {};
    depthView.Format = kDepthFormat;
    depthView.ViewDimension = D3D12_DSV_DIMENSION_TEXTURE2D;
    renderer->device->CreateDepthStencilView(renderer->depthTarget, &depthView,
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
    flags.BindFlags = D3D11_BIND_DEPTH_STENCIL;
    if (FAILED(renderer->d3d11On12->CreateWrappedResource(renderer->depthTarget, &flags,
            D3D12_RESOURCE_STATE_DEPTH_WRITE, D3D12_RESOURCE_STATE_DEPTH_WRITE,
            IID_PPV_ARGS(&renderer->wrappedDepth)))) {
        throwJava(env, "Could not wrap the terrain depth image.");
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
    release(renderer->lightmap);
    release(renderer->atlas);
    release(renderer->extensionList);
    release(renderer->finishList);
    release(renderer->timestampHeap);
    release(renderer->timestampReadback);
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
        if (slot.passConstants != nullptr && slot.passConstantsMapped != nullptr) {
            slot.passConstants->Unmap(0, nullptr);
            slot.passConstantsMapped = nullptr;
        }
        release(slot.passConstants);
        release(slot.extension);
        release(slot.finish);
        release(slot.cutout);
        release(slot.opaque);
        release(slot.upload);release(slot.shadow);release(slot.translucent);release(slot.interopCopy);
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
    release(renderer->fastOpaquePipeline);release(renderer->fastCutoutPipeline);
    release(renderer->shadowPipeline);release(renderer->translucentPipeline);release(renderer->shadowList);release(renderer->translucentList);
    release(renderer->rootSignature);
    release(renderer->pipelineLibrary);
    release(renderer->device1);
    release(renderer->srvHeap);
    release(renderer->dsvHeap);
    release(renderer->rtvHeap);
    release(renderer->d3d11On12);
    release(renderer->d3d11On12v2);release(renderer->exportFence);release(renderer->interopCopyList);
    release(renderer->d3d11Context);
    release(renderer->d3d11Device);
    closeHandle(renderer->sharedGlDoneFenceHandle);
    closeHandle(renderer->sharedFenceHandle);
    closeHandle(renderer->directMemoryProbeHandle);
    release(renderer->directMemoryProbe);
    release(renderer->glDoneFence);
    release(renderer->fence);
    release(renderer->queue);
    release(renderer->device);
    closeHandle(renderer->watchdogEvent);
    closeHandle(renderer->fenceEvent);
    delete renderer;
}

bool compileShader(const char* entryPoint, const char* target, bool cutout, ID3DBlob** bytecode, bool geometry=true) {
    const D3D_SHADER_MACRO defines[] = {
        {"ATLAS_SLOTS", "64"},
        {"DIRECT_GEOMETRY",geometry?"1":"0"},
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
    atlasRange.NumDescriptors = kAtlasSlots + 3;
    atlasRange.OffsetInDescriptorsFromTableStart = 0;
    D3D12_ROOT_PARAMETER parameters[4] = {};
    parameters[0].ParameterType = D3D12_ROOT_PARAMETER_TYPE_32BIT_CONSTANTS;
    parameters[0].Constants.ShaderRegister = 0;
    parameters[0].Constants.Num32BitValues = 28;
    parameters[0].ShaderVisibility = D3D12_SHADER_VISIBILITY_ALL;
    parameters[1].ParameterType = D3D12_ROOT_PARAMETER_TYPE_32BIT_CONSTANTS;
    parameters[1].Constants.ShaderRegister = 1;
    parameters[1].Constants.Num32BitValues = kDrawConstantCount;
    parameters[1].ShaderVisibility = D3D12_SHADER_VISIBILITY_ALL;
    parameters[2].ParameterType = D3D12_ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE;
    parameters[2].DescriptorTable.NumDescriptorRanges = 1;
    parameters[2].DescriptorTable.pDescriptorRanges = &atlasRange;
    parameters[2].ShaderVisibility = D3D12_SHADER_VISIBILITY_ALL;
    parameters[3].ParameterType = D3D12_ROOT_PARAMETER_TYPE_CBV;
    parameters[3].Descriptor.ShaderRegister = 2;
    parameters[3].ShaderVisibility = D3D12_SHADER_VISIBILITY_ALL;
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
    D3D12_STATIC_SAMPLER_DESC samplers[] = {sampler, sampler, sampler};
    samplers[2].ShaderRegister=2;samplers[2].Filter=D3D12_FILTER_COMPARISON_MIN_MAG_LINEAR_MIP_POINT;samplers[2].ComparisonFunc=D3D12_COMPARISON_FUNC_LESS_EQUAL;
    samplers[1].ShaderRegister = 1;
    samplers[1].Filter = D3D12_FILTER_MIN_MAG_MIP_LINEAR;
    samplers[1].ShaderVisibility = D3D12_SHADER_VISIBILITY_VERTEX;
    description.NumStaticSamplers = 3;
    description.pStaticSamplers = samplers;
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
        {"TEXCOORD", 0, DXGI_FORMAT_R16G16_UNORM, 0, 12, D3D12_INPUT_CLASSIFICATION_PER_VERTEX_DATA, 0},
        {"NORMAL",0,DXGI_FORMAT_R8G8B8A8_SNORM,0,16,D3D12_INPUT_CLASSIFICATION_PER_VERTEX_DATA,0},
        {"MATERIAL",0,DXGI_FORMAT_R8_UINT,0,19,D3D12_INPUT_CLASSIFICATION_PER_VERTEX_DATA,0}
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
    // Match vanilla GL counter-clockwise block meshes; verified by the WGL regression.
    description.RasterizerState.FillMode = D3D12_FILL_MODE_SOLID;
    description.RasterizerState.CullMode = D3D12_CULL_MODE_BACK;
    description.RasterizerState.FrontCounterClockwise = TRUE;
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
    HRESULT result = createCachedPipeline(renderer, L"direct.terrain.opaque.v6", description,
            &renderer->opaquePipeline);
    if (SUCCEEDED(result)) {
        description.PS = {cutoutBytecode->GetBufferPointer(), cutoutBytecode->GetBufferSize()};
        result = createCachedPipeline(renderer, L"direct.terrain.cutout.v6", description,
                &renderer->cutoutPipeline);
    }
    if(SUCCEEDED(result)){
        ID3DBlob* pixel=nullptr;
        if(!compileShader("shadowMain","ps_5_1",false,&pixel))result=E_FAIL;
        else{
            auto shadow=description;shadow.NumRenderTargets=0;shadow.RTVFormats[0]=DXGI_FORMAT_UNKNOWN;shadow.PS={pixel->GetBufferPointer(),pixel->GetBufferSize()};
            shadow.RasterizerState.CullMode=D3D12_CULL_MODE_NONE;
            result=createCachedPipeline(renderer,L"direct.shadow.v1",shadow,&renderer->shadowPipeline);release(pixel);
        }
        if(SUCCEEDED(result)){
            if(!compileShader("psTranslucent","ps_5_1",false,&pixel))result=E_FAIL;
            else{
                auto transparent=description;transparent.PS={pixel->GetBufferPointer(),pixel->GetBufferSize()};transparent.DepthStencilState.DepthWriteMask=D3D12_DEPTH_WRITE_MASK_ALL;
                transparent.RasterizerState.CullMode=D3D12_CULL_MODE_NONE;
                auto& blend=transparent.BlendState.RenderTarget[0];blend.BlendEnable=TRUE;blend.SrcBlend=D3D12_BLEND_SRC_ALPHA;blend.DestBlend=D3D12_BLEND_INV_SRC_ALPHA;blend.SrcBlendAlpha=D3D12_BLEND_ONE;blend.DestBlendAlpha=D3D12_BLEND_INV_SRC_ALPHA;
                result=createCachedPipeline(renderer,L"direct.translucent.v1",transparent,&renderer->translucentPipeline);release(pixel);
            }
        }
    }
    if(SUCCEEDED(result)){
        ID3DBlob* fastVertex=nullptr;ID3DBlob* fastOpaque=nullptr;ID3DBlob* fastCutout=nullptr;
        if(!compileShader("vsMain","vs_5_1",false,&fastVertex,false)
                ||!compileShader("psMain","ps_5_1",false,&fastOpaque,false)
                ||!compileShader("psMain","ps_5_1",true,&fastCutout,false))result=E_FAIL;
        else{
            auto fast=description;fast.InputLayout={elements,3};fast.VS={fastVertex->GetBufferPointer(),fastVertex->GetBufferSize()};
            fast.PS={fastOpaque->GetBufferPointer(),fastOpaque->GetBufferSize()};
            result=createCachedPipeline(renderer,L"direct.terrain.opaque.fast.v1",fast,&renderer->fastOpaquePipeline);
            if(SUCCEEDED(result)){fast.PS={fastCutout->GetBufferPointer(),fastCutout->GetBufferSize()};result=createCachedPipeline(renderer,L"direct.terrain.cutout.fast.v1",fast,&renderer->fastCutoutPipeline);}
        }
        release(fastVertex);release(fastOpaque);release(fastCutout);
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
    inputRange.NumDescriptors = 2;
    inputRange.BaseShaderRegister = 0;
    inputRange.OffsetInDescriptorsFromTableStart = 0;
    D3D12_ROOT_PARAMETER parameters[2] = {};
    // A 64-float root constant block plus the SRV table exceeds the 64-DWORD root limit.
    parameters[0].ParameterType = D3D12_ROOT_PARAMETER_TYPE_CBV;
    parameters[0].Descriptor.ShaderRegister = 0;
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
        D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV, (targetCount + 1) * 2,
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
    auto addDepthView = [&]() {
        srvHandle.ptr += renderer->extensionSrvIncrement;
        auto depthView = view;
        depthView.Format = DXGI_FORMAT_R32_FLOAT;
        renderer->device->CreateShaderResourceView(renderer->depthTarget, &depthView, srvHandle);
        srvHandle.ptr += renderer->extensionSrvIncrement;
    };
    addDepthView();
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
        addDepthView();
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
    if (sources.empty()) {
        releaseExtensionResources(renderer);
        for (ExtensionPass& pass : renderer->extensionPasses) release(pass.pipeline);
        renderer->extensionPasses.clear();
        return true;
    }
    if (renderer->extensionRootSignature == nullptr && !createExtensionRootSignature(renderer)) {
        throwJava(env, "Direct extension pass root signature creation failed.");
        return false;
    }
    // Keep the working chain alive until every replacement pipeline has compiled.
    std::vector<ExtensionPass> candidates;
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
            release(pixel);
            for (ExtensionPass& pass : candidates) release(pass.pipeline);
            jclass errorType = env->FindClass("java/lang/IllegalArgumentException");
            if (errorType != nullptr) {
                const std::string message = "Direct extension pass " + std::to_string(candidates.size())
                        + " pixel shader compilation failed; inspect the native debug output.";
                env->ThrowNew(errorType, message.c_str());
                env->DeleteLocalRef(errorType);
            }
            return false;
        }
        description.PS = {pixel->GetBufferPointer(), pixel->GetBufferSize()};
        const std::wstring name = L"direct.extension."
                + std::to_wstring(std::hash<std::string>{}(source)) + L".v2";
        ID3D12PipelineState* pipeline = nullptr;
        const HRESULT pipelineResult = createCachedPipeline(renderer, name.c_str(), description, &pipeline);
        release(pixel);
        if (FAILED(pipelineResult)) {
            release(vertex);
            for (ExtensionPass& pass : candidates) release(pass.pipeline);
            throwJava(env, "Direct extension pass pipeline state creation failed.");
            return false;
        }
        candidates.push_back({source, pipeline});
    }
    release(vertex);
    releaseExtensionResources(renderer);
    for (ExtensionPass& pass : renderer->extensionPasses) release(pass.pipeline);
    renderer->extensionPasses = std::move(candidates);
    return createExtensionResources(renderer, env);
}

bool createDescriptorHeaps(InteropRenderer* renderer) {
    D3D12_DESCRIPTOR_HEAP_DESC rtv = {D3D12_DESCRIPTOR_HEAP_TYPE_RTV, 2, D3D12_DESCRIPTOR_HEAP_FLAG_NONE, 0};
    D3D12_DESCRIPTOR_HEAP_DESC dsv = {D3D12_DESCRIPTOR_HEAP_TYPE_DSV, 1, D3D12_DESCRIPTOR_HEAP_FLAG_NONE, 0};
    D3D12_DESCRIPTOR_HEAP_DESC srv = {D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV, kAtlasSlots + 3,
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
    for (UINT slot = 0; slot < kAtlasSlots + 3; ++slot) {
        nullView.Format=slot>=65?DXGI_FORMAT_R32_FLOAT:kColorFormat;
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
                    IID_PPV_ARGS(&slot.extension)))
                || FAILED(renderer->device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT,
                    IID_PPV_ARGS(&slot.finish)))
                || FAILED(renderer->device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT,IID_PPV_ARGS(&slot.shadow)))
                || FAILED(renderer->device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT,IID_PPV_ARGS(&slot.translucent)))
                || FAILED(renderer->device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT,IID_PPV_ARGS(&slot.interopCopy)))) {
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
            || FAILED(renderer->device->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT, first.finish,
                nullptr, IID_PPV_ARGS(&renderer->finishList)))
            || FAILED(renderer->device->CreateCommandList(0,D3D12_COMMAND_LIST_TYPE_DIRECT,first.shadow,nullptr,IID_PPV_ARGS(&renderer->shadowList)))
            || FAILED(renderer->device->CreateCommandList(0,D3D12_COMMAND_LIST_TYPE_DIRECT,first.translucent,nullptr,IID_PPV_ARGS(&renderer->translucentList)))
            || FAILED(renderer->device->CreateCommandList(0,D3D12_COMMAND_LIST_TYPE_DIRECT,first.interopCopy,nullptr,IID_PPV_ARGS(&renderer->interopCopyList)))
            || FAILED(renderer->interopCopyList->Close())
            || FAILED(renderer->shadowList->Close()) || FAILED(renderer->translucentList->Close())
            || FAILED(renderer->finishList->Close())
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
        if (!createBuffer(renderer, D3D12_HEAP_TYPE_UPLOAD, (kMaxExtensionPasses + 1) * 256,
                D3D12_RESOURCE_STATE_GENERIC_READ, &slot.passConstants)) {
            throwJava(env, "Direct could not allocate extension constant buffers.");
            return false;
        }
        mapped = nullptr;
        if (FAILED(slot.passConstants->Map(0, &noRead, &mapped))) {
            throwJava(env, "Direct could not map extension constant buffers.");
            return false;
        }
        slot.passConstantsMapped = static_cast<uint8_t*>(mapped);
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
    renderer->d3d11On12->QueryInterface(IID_PPV_ARGS(&renderer->d3d11On12v2));
    if(renderer->d3d11On12v2&&FAILED(renderer->device->CreateFence(0,D3D12_FENCE_FLAG_NONE,IID_PPV_ARGS(&renderer->exportFence))))release(renderer->d3d11On12v2);
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
    if (!createFenceWithOptionalSharedHandle(renderer->device, &renderer->fence,
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
    renderer->translucentIndexBytes=0;
    renderer->visible.reserve(renderer->sectionList.size());
    Plane frustum[6];
    extractFrustum(renderer->viewProjection, frustum);
    const double maxDistanceSquared = static_cast<double>(renderDistance) * renderDistance;
    std::vector<const TerrainSection*> selected;
    if (renderer->useVisibleKeys) {
        selected.reserve(renderer->visibleKeys.size());
        for (jlong key : renderer->visibleKeys) {
            auto it = renderer->sections.find(key);
            if (it != renderer->sections.end()) selected.push_back(it->second);
        }
    } else selected.assign(renderer->sectionList.begin(), renderer->sectionList.end());
    for (const TerrainSection* section : selected) {
        const double ox = section->x - cameraX;
        const double oy = section->y - cameraY;
        const double oz = section->z - cameraZ;
        const double cx = ox + 8.0;
        const double cy = oy + 8.0;
        const double cz = oz + 8.0;
        if (!renderer->exactVisibleCoverage && (cx * cx + cy * cy + cz * cz > maxDistanceSquared
                || !sphereVisible(frustum, static_cast<float>(cx), static_cast<float>(cy),
                        static_cast<float>(cz), kSectionRadius))) {
            continue;
        }
        renderer->visible.push_back({section, {static_cast<float>(ox), static_cast<float>(oy),
                static_cast<float>(oz)}});
        if(!section->transparent.empty())renderer->translucentIndexBytes=((renderer->translucentIndexBytes+255)&~UINT64(255))+section->transparent.size()*24;
    }
}

// Opaco limpa o alvo; cutout preserva cor e depth.
// Um render pass não pode atravessar command lists (o runtime descarta a lista), e opaco/cutout
// são gravados em listas distintas para paralelismo. Por isso o alvo é ligado com OMSetRenderTargets.
void beginTerrainPass(InteropRenderer* renderer, ID3D12GraphicsCommandList* list, PassRole role) {
    const D3D12_CPU_DESCRIPTOR_HANDLE rtv = renderer->rtvHeap->GetCPUDescriptorHandleForHeapStart();
    const D3D12_CPU_DESCRIPTOR_HANDLE dsv = renderer->dsvHeap->GetCPUDescriptorHandleForHeapStart();
    const bool clear = role != PassRole::Cutout;
    list->OMSetRenderTargets(1, &rtv, TRUE, &dsv);
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
    list->SetPipelineState(renderer->terrainEffectsActive
            ?(role==PassRole::Opaque?renderer->opaquePipeline:renderer->cutoutPipeline)
            :(role==PassRole::Opaque?renderer->fastOpaquePipeline:renderer->fastCutoutPipeline));
    list->IASetPrimitiveTopology(D3D_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    const D3D12_VIEWPORT viewport = {0.0f, 0.0f, static_cast<float>(renderer->width),
            static_cast<float>(renderer->height), 0.0f, 1.0f};
    const D3D12_RECT scissor = {0, 0, static_cast<LONG>(renderer->width), static_cast<LONG>(renderer->height)};
    list->RSSetViewports(1, &viewport);
    list->RSSetScissorRects(1, &scissor);
    list->SetGraphicsRoot32BitConstants(0, 28, renderer->viewProjection, 0);
    list->SetGraphicsRootConstantBufferView(3,renderer->slots[renderer->slotIndex].passConstants->GetGPUVirtualAddress()+kMaxExtensionPasses*256);
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
        for (UINT layer = 1; layer < 3; ++layer) {
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
    if (renderer->profiling && role != PassRole::Cutout && !renderer->shadowRecorded)
        list->EndQuery(renderer->timestampHeap, D3D12_QUERY_TYPE_TIMESTAMP, renderer->slotIndex * 2);
    beginTerrainPass(renderer, list, role);
    const UINT firstLayer = role == PassRole::Opaque ? 0 : 1;
    const UINT lastLayer = role == PassRole::Opaque ? 1 : 3;
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

// Return 0 only for a safely recovered unsupported unwrap, so the legacy exporter can run.
int copyToInteropNative(InteropRenderer* r){
    auto& slot=r->slots[r->slotIndex];auto* list=r->interopCopyList;
    if(FAILED(slot.interopCopy->Reset())||FAILED(list->Reset(slot.interopCopy,nullptr)))return -1;
    ID3D12Resource* color=nullptr;ID3D12Resource* depth=nullptr;
    HRESULT first=r->d3d11On12v2->UnwrapUnderlyingResource(r->interopTexture,r->queue,IID_PPV_ARGS(&color));
    HRESULT second=SUCCEEDED(first)?r->d3d11On12v2->UnwrapUnderlyingResource(r->interopDepth,r->queue,IID_PPV_ARGS(&depth)):first;
    // Unwrap can enqueue COMMON transitions in the translation context. Publish them
    // before any external wait; all native work below remains on this same queue.
    r->d3d11Context->Flush();
    if(FAILED(first)||FAILED(second)){
        list->Close();UINT64 value=++r->exportValue;bool returned=SUCCEEDED(r->queue->Signal(r->exportFence,value));
        if(color)returned=SUCCEEDED(r->d3d11On12v2->ReturnUnderlyingResource(r->interopTexture,1,&value,&r->exportFence))&&returned;
        if(depth)returned=SUCCEEDED(r->d3d11On12v2->ReturnUnderlyingResource(r->interopDepth,1,&value,&r->exportFence))&&returned;
        release(color);release(depth);r->nativeCopy=false;r->nativeCopyRejected=true;
        OutputDebugStringA("Direct: native interop unwrap rejected; retaining the D3D11On12 copy exporter.\n");
        return returned?0:-1;
    }
    D3D12_RESOURCE_BARRIER before[]={
        transition(r->colorTarget,D3D12_RESOURCE_STATE_RENDER_TARGET,D3D12_RESOURCE_STATE_COPY_SOURCE),
        transition(r->depthTarget,D3D12_RESOURCE_STATE_DEPTH_WRITE,D3D12_RESOURCE_STATE_COPY_SOURCE),
        transition(color,D3D12_RESOURCE_STATE_COMMON,D3D12_RESOURCE_STATE_COPY_DEST),
        transition(depth,D3D12_RESOURCE_STATE_COMMON,D3D12_RESOURCE_STATE_COPY_DEST)};
    list->ResourceBarrier(4,before);list->CopyResource(color,r->colorTarget);list->CopyResource(depth,r->depthTarget);
    D3D12_RESOURCE_BARRIER after[]={
        transition(r->colorTarget,D3D12_RESOURCE_STATE_COPY_SOURCE,D3D12_RESOURCE_STATE_RENDER_TARGET),
        transition(r->depthTarget,D3D12_RESOURCE_STATE_COPY_SOURCE,D3D12_RESOURCE_STATE_DEPTH_WRITE),
        transition(color,D3D12_RESOURCE_STATE_COPY_DEST,D3D12_RESOURCE_STATE_COMMON),
        transition(depth,D3D12_RESOURCE_STATE_COPY_DEST,D3D12_RESOURCE_STATE_COMMON)};
    list->ResourceBarrier(4,after);
    bool submitted=SUCCEEDED(list->Close());
    if(submitted){ID3D12CommandList* lists[]={list};r->queue->ExecuteCommandLists(1,lists);}
    UINT64 value=++r->exportValue;
    bool signaled=SUCCEEDED(r->queue->Signal(r->exportFence,value));
    // Check resources back in with their real completion fence. No CPU wait here.
    bool returnedColor=SUCCEEDED(r->d3d11On12v2->ReturnUnderlyingResource(r->interopTexture,1,&value,&r->exportFence));
    bool returnedDepth=SUCCEEDED(r->d3d11On12v2->ReturnUnderlyingResource(r->interopDepth,1,&value,&r->exportFence));
    release(color);release(depth);
    return submitted&&signaled&&returnedColor&&returnedDepth&&SUCCEEDED(r->device->GetDeviceRemovedReason())?1:-1;
}

bool copyToInteropTexture(InteropRenderer* renderer) {
    if(renderer->nativeCopy){int result=copyToInteropNative(renderer);if(result!=0)return result>0;}
    // Both APIs submit to the same queue. Acquire/release plus Flush preserve GPU order;
    // a CPU wait before this copy serializes the renderer unnecessarily.
    ID3D11Resource* wrapped[] = {renderer->wrappedColor, renderer->wrappedDepth};
    renderer->d3d11On12->AcquireWrappedResources(wrapped, 2);
    renderer->d3d11Context->CopyResource(renderer->interopTexture, renderer->wrappedColor);
    renderer->d3d11Context->CopyResource(renderer->interopDepth, renderer->wrappedDepth);
    renderer->d3d11On12->ReleaseWrappedResources(wrapped, 2);
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
    const D3D12_RESOURCE_BARRIER forSampling[] = {
        transition(renderer->colorTarget,D3D12_RESOURCE_STATE_RENDER_TARGET,D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE),
        transition(renderer->depthTarget,D3D12_RESOURCE_STATE_DEPTH_WRITE,D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE)
    };
    list->ResourceBarrier(ARRAYSIZE(forSampling),forSampling);

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
        inputSrv.ptr += static_cast<UINT64>(inputDescriptorIndex) * 2 * renderer->extensionSrvIncrement;

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
        const UINT64 constantOffset = static_cast<UINT64>(index) * 256;
        std::memcpy(slot.passConstantsMapped + constantOffset, constants, sizeof(constants));
        list->SetGraphicsRootConstantBufferView(0, slot.passConstants->GetGPUVirtualAddress() + constantOffset);
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
        transition(finalPass, D3D12_RESOURCE_STATE_COPY_SOURCE, D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE),
        transition(renderer->depthTarget,D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE,D3D12_RESOURCE_STATE_DEPTH_WRITE)
    };
    list->ResourceBarrier(ARRAYSIZE(restore), restore);
    return SUCCEEDED(list->Close());
}


D3D12_CPU_DESCRIPTOR_HANDLE materialSrv(InteropRenderer* r,UINT slot){auto handle=r->srvHeap->GetCPUDescriptorHandleForHeapStart();handle.ptr+=static_cast<SIZE_T>(slot)*r->srvIncrement;return handle;}
void setDepthSrv(InteropRenderer* r,ID3D12Resource* resource,UINT slot){D3D12_SHADER_RESOURCE_VIEW_DESC view={};view.Format=DXGI_FORMAT_R32_FLOAT;view.ViewDimension=D3D12_SRV_DIMENSION_TEXTURE2D;view.Shader4ComponentMapping=D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING;view.Texture2D.MipLevels=1;r->device->CreateShaderResourceView(resource,&view,materialSrv(r,slot));}
void releaseSceneSnapshots(InteropRenderer* r){
    release(r->sceneColor);release(r->sceneDepth);setDepthSrv(r,nullptr,65);
    D3D12_SHADER_RESOURCE_VIEW_DESC srv={};srv.Format=kColorFormat;srv.ViewDimension=D3D12_SRV_DIMENSION_TEXTURE2D;srv.Shader4ComponentMapping=D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING;srv.Texture2D.MipLevels=1;
    r->device->CreateShaderResourceView(nullptr,&srv,materialSrv(r,64));
}
bool prepareGeometry(InteropRenderer* r,FrameSlot& slot,double x,double y,double z,JNIEnv* env){
    using namespace DirectX;
    auto* c=r->geometryConstants;std::fill(c,c+64,0.0f);
    for(UINT i=0;i<4;i++)c[16+i]=r->geometrySettings[i];
    if(c[17]==0&&c[18]==0&&(r->sceneColor||r->sceneDepth))releaseSceneSnapshots(r);
    for(UINT i=0;i<4;i++)c[20+i]=r->geometrySettings[5+i];
    c[25]=(std::max)(r->geometrySettings[6],0.0f);c[26]=r->geometrySettings[9];c[27]=r->geometrySettings[4];
    r->terrainEffectsActive=c[19]>0||(c[16]>0&&c[25]>.02f);
    r->cameraOffset[0]=static_cast<float>(std::fmod(x,4096));r->cameraOffset[1]=static_cast<float>(std::fmod(y,4096));r->cameraOffset[2]=static_cast<float>(std::fmod(z,4096));
    std::memcpy(c+60,r->cameraOffset,12);
    if(c[18]>0){XMFLOAT4X4 world;std::memcpy(&world,r->viewProjection,64);auto matrix=XMLoadFloat4x4(&world);XMFLOAT4X4 inverse;XMStoreFloat4x4(&inverse,XMMatrixInverse(nullptr,matrix));std::memcpy(c+28,&inverse,64);std::memcpy(c+44,&world,64);}
    r->shadowRecorded=false;r->shadowDrawCount=0;
    if(c[16]>0 && c[25]>.02f){
        UINT size=r->geometrySettings[4]<.5f?512:r->geometrySettings[4]<1.5f?1024:2048;
        if(r->shadowSize!=size){
            release(r->shadowMap);release(r->shadowDsv);r->shadowSize=0;
            D3D12_RESOURCE_DESC desc={D3D12_RESOURCE_DIMENSION_TEXTURE2D,0,size,size,1,1,DXGI_FORMAT_R32_TYPELESS,{1,0},D3D12_TEXTURE_LAYOUT_UNKNOWN,D3D12_RESOURCE_FLAG_ALLOW_DEPTH_STENCIL};
            D3D12_HEAP_PROPERTIES heap={};heap.Type=D3D12_HEAP_TYPE_DEFAULT;D3D12_CLEAR_VALUE clear={};clear.Format=kDepthFormat;clear.DepthStencil.Depth=1;
            D3D12_DESCRIPTOR_HEAP_DESC dsv={D3D12_DESCRIPTOR_HEAP_TYPE_DSV,1,D3D12_DESCRIPTOR_HEAP_FLAG_NONE,0};
            if(FAILED(r->device->CreateCommittedResource(&heap,D3D12_HEAP_FLAG_NONE,&desc,D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE,&clear,IID_PPV_ARGS(&r->shadowMap)))||FAILED(r->device->CreateDescriptorHeap(&dsv,IID_PPV_ARGS(&r->shadowDsv)))){throwJava(env,"Shadow map allocation failed");return false;}
            D3D12_DEPTH_STENCIL_VIEW_DESC view={};view.Format=kDepthFormat;view.ViewDimension=D3D12_DSV_DIMENSION_TEXTURE2D;r->device->CreateDepthStencilView(r->shadowMap,&view,r->shadowDsv->GetCPUDescriptorHandleForHeapStart());setDepthSrv(r,r->shadowMap,66);r->shadowSize=size;
        }
        auto eye=XMVectorSet(c[20]*128,c[21]*128,c[22]*128,1);auto view=XMMatrixLookAtLH(eye,XMVectorZero(),XMVectorSet(0,0,1,0));auto projection=XMMatrixOrthographicLH(96,96,.1f,256);
        XMFLOAT4X4 shadow;XMStoreFloat4x4(&shadow,XMMatrixMultiply(view,projection));std::memcpy(c,&shadow,64);c[24]=1;
    }else if(r->shadowMap){release(r->shadowMap);release(r->shadowDsv);r->shadowSize=0;setDepthSrv(r,nullptr,66);}
    std::memcpy(slot.passConstantsMapped+kMaxExtensionPasses*256,c,256);
    if(c[24]>.5f){
        auto* list=r->shadowList;if(FAILED(slot.shadow->Reset())||FAILED(list->Reset(slot.shadow,r->shadowPipeline))){throwJava(env,"Shadow command list reset failed");return false;}
        if(r->profiling)list->EndQuery(r->timestampHeap,D3D12_QUERY_TYPE_TIMESTAMP,r->slotIndex*2);
        auto toDepth=transition(r->shadowMap,D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE,D3D12_RESOURCE_STATE_DEPTH_WRITE);list->ResourceBarrier(1,&toDepth);
        auto dsv=r->shadowDsv->GetCPUDescriptorHandleForHeapStart();list->OMSetRenderTargets(0,nullptr,FALSE,&dsv);list->ClearDepthStencilView(dsv,D3D12_CLEAR_FLAG_DEPTH,1,0,0,nullptr);
        D3D12_VIEWPORT viewport={0,0,static_cast<float>(r->shadowSize),static_cast<float>(r->shadowSize),0,1};D3D12_RECT rect={0,0,static_cast<LONG>(r->shadowSize),static_cast<LONG>(r->shadowSize)};list->RSSetViewports(1,&viewport);list->RSSetScissorRects(1,&rect);
        ID3D12DescriptorHeap* heaps[]={r->srvHeap};list->SetDescriptorHeaps(1,heaps);list->SetGraphicsRootSignature(r->rootSignature);list->SetGraphicsRootDescriptorTable(2,r->srvHeap->GetGPUDescriptorHandleForHeapStart());list->SetGraphicsRootConstantBufferView(3,slot.passConstants->GetGPUVirtualAddress()+kMaxExtensionPasses*256);list->IASetPrimitiveTopology(D3D_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
        float frame[28]={};std::memcpy(frame,c,64);list->SetGraphicsRoot32BitConstants(0,28,frame,0);
        for(const auto* section:r->sectionList){
            if(r->useShadowKeys&&r->shadowKeys.find(section->key)==r->shadowKeys.end())continue;
            double dx=section->x+8-x,dy=section->y+8-y,dz=section->z+8-z;if(dx*dx+dy*dy+dz*dz>80*80)continue;
            list->IASetVertexBuffers(0,1,&section->vertexView);list->IASetIndexBuffer(&section->indexView);
            for(UINT layer=0;layer<3;layer++)if(section->layerIndexCounts[layer]){
                r->shadowDrawCount++;
                float offset[]={static_cast<float>(section->x-x),static_cast<float>(section->y-y),static_cast<float>(section->z-z),kLayerAlphaCutoff[layer]};UINT constants[5];std::memcpy(constants,offset,16);constants[4]=r->atlasIndex;list->SetGraphicsRoot32BitConstants(1,5,constants,0);list->DrawIndexedInstanced(section->layerIndexCounts[layer],1,section->layerStart[layer],0,0);
            }
        }
        auto toRead=transition(r->shadowMap,D3D12_RESOURCE_STATE_DEPTH_WRITE,D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE);list->ResourceBarrier(1,&toRead);if(FAILED(list->Close())){throwJava(env,"Shadow list close failed");return false;}
        ID3D12CommandList* lists[]={list};r->queue->ExecuteCommandLists(1,lists);r->shadowRecorded=true;
    }
    return true;
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
    } else {renderer->visible.clear();renderer->translucentIndexBytes=0;}
    if (!submitUploads(renderer)) {
        throwJava(env, "DX12 terrain upload command list could not be closed.");
        return false;
    }
    if(!prepareGeometry(renderer,slot,cameraX,cameraY,cameraZ,env))return false;
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
    if (!copyToInteropTexture(renderer)) {
        throwJava(env, "D3D11On12 reported device removal while copying the completed terrain image.");
        return false;
    }

    if (renderer->profiling) {
        if (FAILED(slot.finish->Reset()) || FAILED(renderer->finishList->Reset(slot.finish, nullptr))) {
            throwJava(env, "Could not record GPU profiling timestamps."); return false;
        }
        const UINT query = renderer->slotIndex * 2;
        renderer->finishList->EndQuery(renderer->timestampHeap, D3D12_QUERY_TYPE_TIMESTAMP, query + 1);
        renderer->finishList->ResolveQueryData(renderer->timestampHeap, D3D12_QUERY_TYPE_TIMESTAMP, query, 2,
                renderer->timestampReadback, query * sizeof(UINT64));
        if (FAILED(renderer->finishList->Close())) return false;
        ID3D12CommandList* finish[] = {renderer->finishList};
        renderer->queue->ExecuteCommandLists(1, finish);
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
    slot.drawCount=renderer->shadowDrawCount;
    for(const auto& entry:renderer->visible)for(UINT layer=0;layer<3;layer++)slot.drawCount+=entry.section->layerIndexCounts[layer]!=0;
    slot.timestampValid = renderer->profiling;
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
        UINT width, UINT height, bool compress, UINT bc7EncoderThreads, JNIEnv* env, bool lightmap = false) {
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
    const UINT slot = lightmap ? 63 : renderer->nextAtlasSlot;
    if (!lightmap) renderer->nextAtlasSlot = (slot + 1) % (kAtlasSlots - 1);
    D3D12_SHADER_RESOURCE_VIEW_DESC view = {};
    view.Format = format;
    view.ViewDimension = D3D12_SRV_DIMENSION_TEXTURE2D;
    view.Shader4ComponentMapping = D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING;
    view.Texture2D.MipLevels = static_cast<UINT>(levelCount);
    D3D12_CPU_DESCRIPTOR_HANDLE handle = renderer->srvHeap->GetCPUDescriptorHandleForHeapStart();
    handle.ptr += static_cast<SIZE_T>(slot) * renderer->srvIncrement;
    renderer->device->CreateShaderResourceView(atlas, &view, handle);
    retire(renderer, upload);
    if (lightmap) {
        retire(renderer, renderer->lightmap);
        renderer->lightmap = atlas;
    } else {
        retire(renderer, renderer->atlas);
        renderer->atlas = atlas;
        renderer->atlasIndex = slot;
    }
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
        static_cast<jlong>(reinterpret_cast<intptr_t>(renderer->sharedGlDoneFenceHandle)),
        static_cast<jlong>(reinterpret_cast<intptr_t>(renderer->interopDepth))
    };
    jlongArray result = env->NewLongArray(5);
    if (result == nullptr) {
        destroyInteropRenderer(renderer);
        return nullptr;
    }
    env->SetLongArrayRegion(result, 0, 5, handles);
    if (env->ExceptionCheck()) {
        destroyInteropRenderer(renderer);
        return nullptr;
    }
    g_interopRenderer = renderer;
    return result;
}

// Retorna o novo ponteiro da textura D3D11. O Java deve desregistrar o objeto WGL antes.
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_redondoguibi_direct_NativeDx12_resizeInterop(JNIEnv* env, jclass, jint width, jint height) {
    InteropRenderer* renderer = g_interopRenderer;
    if (renderer == nullptr || width <= 0 || height <= 0) {
        throwJava(env, "DX12/OpenGL interop resize received an invalid state or size.");
        return nullptr;
    }
    renderer->d3d11Context->Flush();
    if (!waitFence(renderer, renderer->fence, renderer->lastSignaledValue)) {
        throwJava(env, "DX12/OpenGL interop timed out waiting for the GPU before resizing.");
        return nullptr;
    }
    releaseSizeResources(renderer);
    renderer->width = static_cast<UINT>(width);
    renderer->height = static_cast<UINT>(height);
    if (!createSizeResources(renderer, env)) {
        return nullptr;
    }
    const jlong handles[] = {reinterpret_cast<jlong>(renderer->interopTexture), reinterpret_cast<jlong>(renderer->interopDepth)};
    jlongArray result = env->NewLongArray(2);
    if (result) env->SetLongArrayRegion(result, 0, 2, handles);
    return result;
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
    if (matrix == nullptr || env->GetArrayLength(matrix) != 28 || frameIndex < 0
            || (expectedUniforms > 0 && (passUniforms == nullptr
                || env->GetArrayLength(passUniforms) != expectedUniforms))) {
        throwJava(env, "DX12 terrain rendering received an invalid matrix or frame index.");
        return -1;
    }
    jfloat values[28];
    env->GetFloatArrayRegion(matrix, 0, 28, values);
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
Java_com_redondoguibi_direct_NativeDx12_updateTerrainSectionV2(JNIEnv* env, jclass, jlong key,
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

    const auto* allIndices=static_cast<const UINT*>(indices);
    const UINT transparentStart=static_cast<UINT>(counts[0])+static_cast<UINT>(counts[1])+static_cast<UINT>(counts[2]);
    if(counts[3]%6!=0){throwJava(env,"Invalid translucent quads");return;}
    for(jlong i=0;i<indexCount;i++)if(allIndices[i]>=static_cast<UINT>(vertexCount)){
        throwJava(env,"Terrain index references an invalid vertex");return;
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
    section->transparent.reserve(static_cast<size_t>(counts[3])/6);
    for(UINT q=0;q<static_cast<UINT>(counts[3]);q+=6){
        TransparentQuad quad={};
        for(UINT i=0;i<6;i++)quad.indices[i]=allIndices[transparentStart+q+i];
        // Average the two triangle centroids; duplicated diagonal vertices do not affect distance ordering within a plane.
        for(UINT i:quad.indices){const auto* v=reinterpret_cast<const uint16_t*>(static_cast<const uint8_t*>(vertices)+i*kVertexStride);for(UINT a=0;a<3;a++)quad.center[a]+=(v[a]/1024.0f-16)/6.0f;}
        section->transparent.push_back(quad);
    }
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

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_updateLightmap(JNIEnv* env, jclass, jobject pixels) {
    if (!g_interopRenderer || !pixels || env->GetDirectBufferCapacity(pixels) < 1024) {
        throwJava(env, "Invalid lightmap pixels."); return;
    }
    const void* data[] = {env->GetDirectBufferAddress(pixels)};
    uploadAtlas(g_interopRenderer, data, 1, 16, 16, false, 1, env, true);
}
extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_setVisibleSections(JNIEnv* env, jclass, jlongArray keys, jint count, jboolean exactCoverage) {
    if (!g_interopRenderer || !keys || count < 0 || count > env->GetArrayLength(keys)) {
        throwJava(env, "Invalid visible terrain selection."); return;
    }
    auto* renderer = g_interopRenderer;
    renderer->visibleKeys.resize(count);
    env->GetLongArrayRegion(keys, 0, count, renderer->visibleKeys.data());
    renderer->useVisibleKeys = true;
    renderer->exactVisibleCoverage = exactCoverage == JNI_TRUE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_redondoguibi_direct_NativeDx12_waitInteropFrame(JNIEnv* env, jclass, jlong value) {
    if (!g_interopRenderer || value < 0 || !waitFence(g_interopRenderer, g_interopRenderer->fence, value)) {
        throwJava(env, "DX12 frame did not complete before composition."); return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_setShadowSections(JNIEnv* env,jclass,jlongArray keys){
    auto* r=g_interopRenderer;if(!r||!keys){throwJava(env,"Invalid shadow terrain selection");return;}
    jsize count=env->GetArrayLength(keys);std::vector<jlong> values(count);
    env->GetLongArrayRegion(keys,0,count,values.data());if(env->ExceptionCheck())return;
    r->shadowKeys.clear();r->shadowKeys.insert(values.begin(),values.end());r->useShadowKeys=true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_redondoguibi_direct_NativeDx12_isAtlasCompressed(JNIEnv*, jclass) {
    return g_interopRenderer && g_interopRenderer->atlas
        && g_interopRenderer->atlas->GetDesc().Format == DXGI_FORMAT_BC7_UNORM;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_redondoguibi_direct_NativeDx12_atlasMipLevels(JNIEnv*, jclass) {
    return g_interopRenderer && g_interopRenderer->atlas ? g_interopRenderer->atlas->GetDesc().MipLevels : 0;
}
extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_encodeAtlasRegion(JNIEnv* env, jclass, jobject input, jobject output,
        jint width, jint height, jint threads) {
    if (!input || !output || width <= 0 || height <= 0
            || env->GetDirectBufferCapacity(input) < static_cast<jlong>(width) * height * 4
            || env->GetDirectBufferCapacity(output) < static_cast<jlong>(direct::bc7LevelSize(width, height))) {
        throwJava(env, "Invalid BC7 sprite buffers."); return;
    }
    direct::encodeBc7Level(static_cast<const uint8_t*>(env->GetDirectBufferAddress(input)), width, height,
            static_cast<uint8_t*>(env->GetDirectBufferAddress(output)), (std::clamp)(threads, 1, 16));
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_redondoguibi_direct_NativeDx12_updateAtlasRegion(JNIEnv* env, jclass, jobject pixels,
        jint level, jint x, jint y, jint width, jint height) {
    auto* renderer = g_interopRenderer;
    if (!renderer || !renderer->atlas || !pixels || level < 0 || x < 0 || y < 0 || width <= 0 || height <= 0) {
        throwJava(env, "Invalid atlas region."); return JNI_FALSE;
    }
    const auto desc = renderer->atlas->GetDesc();
    const bool bc7 = desc.Format == DXGI_FORMAT_BC7_UNORM;
    if (level >= desc.MipLevels || static_cast<UINT64>(x) + width > (std::max)(1ULL, desc.Width >> level)
            || static_cast<UINT64>(y) + height > (std::max)(1u, desc.Height >> level)
            || (bc7 && ((x % 4) || (y % 4)))) {
        throwJava(env, "Atlas region exceeds its mip bounds."); return JNI_FALSE;
    }
    const UINT rows = bc7 ? (height + 3) / 4 : height;
    const UINT rowBytes = bc7 ? ((width + 3) / 4) * 16 : width * 4;
    D3D12_RESOURCE_DESC patchDesc = desc;
    patchDesc.Width = width;
    patchDesc.Height = height;
    patchDesc.MipLevels = 1;
    D3D12_PLACED_SUBRESOURCE_FOOTPRINT footprint = {};
    renderer->device->GetCopyableFootprints(&patchDesc, 0, 1, 0, &footprint, nullptr, nullptr, nullptr);
    const UINT rowPitch = footprint.Footprint.RowPitch;
    if (env->GetDirectBufferCapacity(pixels) < static_cast<jlong>(rows) * rowBytes || !ensureUploadOpen(renderer)) {
        throwJava(env, "Invalid atlas upload buffer."); return JNI_FALSE;
    }
    auto& slot = renderer->slots[renderer->slotIndex];
    const UINT64 offset = alignUp(slot.uploadOffset, D3D12_TEXTURE_DATA_PLACEMENT_ALIGNMENT);
    const UINT64 bytes = static_cast<UINT64>(rowPitch) * rows;
    if (offset > slot.uploadCapacity || bytes > slot.uploadCapacity - offset) {
        return JNI_FALSE; // Retry this patch next frame instead of stalling or disabling rendering.
    }
    const auto* source = static_cast<const uint8_t*>(env->GetDirectBufferAddress(pixels));
    for (UINT row = 0; row < rows; row++)
        std::memcpy(slot.uploadMapped + offset + row * rowPitch, source + row * rowBytes, rowBytes);
    slot.uploadOffset = offset + bytes;
    // A reload can create the atlas earlier in this same upload list. Apply its queued
    // COPY_DEST -> shader-resource transition before recording a patch's reverse transition.
    if (!renderer->uploadBarriers.empty()) {
        renderer->uploadList->ResourceBarrier(static_cast<UINT>(renderer->uploadBarriers.size()), renderer->uploadBarriers.data());
        renderer->uploadBarriers.clear();
    }
    auto before = transition(renderer->atlas, D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE, D3D12_RESOURCE_STATE_COPY_DEST);
    renderer->uploadList->ResourceBarrier(1, &before);
    D3D12_TEXTURE_COPY_LOCATION target = {};
    target.pResource = renderer->atlas;
    target.Type = D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX;
    target.SubresourceIndex = level;
    D3D12_TEXTURE_COPY_LOCATION src = {};
    src.pResource = slot.uploadBuffer;
    src.Type = D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;
    src.PlacedFootprint = footprint;
    src.PlacedFootprint.Offset = offset;
    renderer->uploadList->CopyTextureRegion(&target, x, y, 0, &src, nullptr);
    auto after = transition(renderer->atlas, D3D12_RESOURCE_STATE_COPY_DEST, D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE);
    renderer->uploadList->ResourceBarrier(1, &after);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_setProfilingEnabled(JNIEnv* env, jclass, jboolean enabled) {
    auto* renderer = g_interopRenderer;
    if (!renderer) return;
    if (enabled && !renderer->timestampHeap) {
        D3D12_QUERY_HEAP_DESC desc = {};
        desc.Type = D3D12_QUERY_HEAP_TYPE_TIMESTAMP;
        desc.Count = kFramesInFlight * 2;
        if (FAILED(renderer->device->CreateQueryHeap(&desc, IID_PPV_ARGS(&renderer->timestampHeap)))
                || !createBuffer(renderer, D3D12_HEAP_TYPE_READBACK, desc.Count * sizeof(UINT64),
                    D3D12_RESOURCE_STATE_COPY_DEST, &renderer->timestampReadback)
                || FAILED(renderer->queue->GetTimestampFrequency(&renderer->timestampFrequency))) {
            throwJava(env, "Could not initialize GPU profiling."); return;
        }
    }
    renderer->profiling = enabled;
}
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_redondoguibi_direct_NativeDx12_frameMetrics(JNIEnv* env, jclass) {
    auto* renderer = g_interopRenderer;
    if (!renderer) return nullptr;
    double gpuMs = -1;
    const UINT index = (renderer->slotIndex + kFramesInFlight - 1) % kFramesInFlight;
    if (renderer->profiling && renderer->slots[index].timestampValid && renderer->slots[index].fenceValue > 0
            && renderer->fence->GetCompletedValue() >= renderer->slots[index].fenceValue) {
        const UINT query = index * 2;
        const D3D12_RANGE range = {query * sizeof(UINT64), (query + 2) * sizeof(UINT64)};
        void* data = nullptr;
        if (SUCCEEDED(renderer->timestampReadback->Map(0, &range, &data))) {
            const auto* values = static_cast<const UINT64*>(data) + query;
            if(values[1]>=values[0])gpuMs = static_cast<double>(values[1] - values[0]) * 1000.0 / renderer->timestampFrequency;
            const D3D12_RANGE noWrite = {0,0};
            renderer->timestampReadback->Unmap(0, &noWrite);
        }
    }
    const UINT draws = renderer->slots[index].drawCount;
    UINT64 uploadReserved=0;
    for(const auto& frame:renderer->slots)uploadReserved+=frame.uploadCapacity;
    const double values[] = {gpuMs, static_cast<double>(renderer->visible.size()), static_cast<double>(draws),
        static_cast<double>(renderer->terrainArenaBytes),static_cast<double>(uploadReserved)};
    jdoubleArray result = env->NewDoubleArray(5);
    if (result) env->SetDoubleArrayRegion(result, 0, 5, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL Java_com_redondoguibi_direct_NativeDx12_setTerrainEffects(JNIEnv* env,jclass,jfloatArray settings){
    auto* r=g_interopRenderer;if(!r)return;
    if(!settings||env->GetArrayLength(settings)!=10){throwJava(env,"Invalid geometry settings");return;}
    float values[10];env->GetFloatArrayRegion(settings,0,10,values);
    for(UINT i=0;i<10;i++)if(!std::isfinite(values[i])){throwJava(env,"Nonfinite geometry setting");return;}
    for(UINT i=0;i<4;i++)if(values[i]<0||values[i]>1){throwJava(env,"Effect outside 0..1");return;}
    if(values[4]<0||values[4]>2||std::floor(values[4])!=values[4]||values[9]<0||values[9]>1){throwJava(env,"Invalid quality/weather");return;}
    std::memcpy(r->geometrySettings,values,sizeof(values));
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_redondoguibi_direct_NativeDx12_configureNativeCopy(JNIEnv*,jclass,jboolean enabled){
    auto* r=g_interopRenderer;if(!r)return JNI_FALSE;
    r->nativeCopy=enabled==JNI_TRUE&&r->d3d11On12v2&&r->exportFence&&!r->nativeCopyRejected;
    return r->nativeCopy?JNI_TRUE:JNI_FALSE;
}

// Isolated functional probe; it never changes the active presentation path or frame timeline.
extern "C" JNIEXPORT jlong JNICALL Java_com_redondoguibi_direct_NativeDx12_createDirectMemoryProbe(JNIEnv* env,jclass){
    auto* r=g_interopRenderer;if(!r){throwJava(env,"Interop not initialized");return 0;}
    if(r->directMemoryProbeHandle)return reinterpret_cast<jlong>(r->directMemoryProbeHandle);
    D3D12_HEAP_PROPERTIES heap={};heap.Type=D3D12_HEAP_TYPE_DEFAULT;
    D3D12_RESOURCE_DESC desc={D3D12_RESOURCE_DIMENSION_TEXTURE2D,0,32,32,1,1,kColorFormat,{1,0},D3D12_TEXTURE_LAYOUT_UNKNOWN,D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET};
    if(FAILED(r->device->CreateCommittedResource(&heap,D3D12_HEAP_FLAG_SHARED,&desc,D3D12_RESOURCE_STATE_RENDER_TARGET,nullptr,IID_PPV_ARGS(&r->directMemoryProbe)))
       ||FAILED(r->device->CreateSharedHandle(r->directMemoryProbe,nullptr,GENERIC_ALL,nullptr,&r->directMemoryProbeHandle))){throwJava(env,"Direct memory probe export failed");return 0;}
    ID3D12CommandAllocator* allocator=nullptr;ID3D12GraphicsCommandList* list=nullptr;ID3D12DescriptorHeap* rtvHeap=nullptr;ID3D12Fence* fence=nullptr;
    HANDLE event=CreateEvent(nullptr,FALSE,FALSE,nullptr);bool success=false;
    D3D12_DESCRIPTOR_HEAP_DESC rtv={D3D12_DESCRIPTOR_HEAP_TYPE_RTV,1,D3D12_DESCRIPTOR_HEAP_FLAG_NONE,0};
    if(event&&SUCCEEDED(r->device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT,IID_PPV_ARGS(&allocator)))
       &&SUCCEEDED(r->device->CreateCommandList(0,D3D12_COMMAND_LIST_TYPE_DIRECT,allocator,nullptr,IID_PPV_ARGS(&list)))
       &&SUCCEEDED(r->device->CreateDescriptorHeap(&rtv,IID_PPV_ARGS(&rtvHeap)))
       &&SUCCEEDED(r->device->CreateFence(0,D3D12_FENCE_FLAG_NONE,IID_PPV_ARGS(&fence)))){
        auto view=rtvHeap->GetCPUDescriptorHandleForHeapStart();r->device->CreateRenderTargetView(r->directMemoryProbe,nullptr,view);
        const float color[]={.25f,.5f,.75f,1};list->ClearRenderTargetView(view,color,0,nullptr);
        auto barrier=transition(r->directMemoryProbe,D3D12_RESOURCE_STATE_RENDER_TARGET,D3D12_RESOURCE_STATE_COMMON);list->ResourceBarrier(1,&barrier);
        if(SUCCEEDED(list->Close())){ID3D12CommandList* lists[]={list};r->queue->ExecuteCommandLists(1,lists);
            success=SUCCEEDED(r->queue->Signal(fence,1))&&SUCCEEDED(fence->SetEventOnCompletion(1,event))&&WaitForSingleObject(event,kFenceWaitTimeoutMs)==WAIT_OBJECT_0;}
    }
    release(list);release(allocator);release(rtvHeap);release(fence);closeHandle(event);
    if(!success){throwJava(env,"Direct memory probe submission failed");return 0;}
    return reinterpret_cast<jlong>(r->directMemoryProbeHandle);
}

extern "C" JNIEXPORT jint JNICALL Java_com_redondoguibi_direct_NativeDx12_translucentFrameStatus(JNIEnv* env,jclass){
    auto* r=g_interopRenderer;if(!r){throwJava(env,"Interop not initialized");return -1;}
    bool ready=false;if(!currentFrameSlotReady(r,env,&ready))return -1;if(!ready)return 2;
    return r->translucentIndexBytes>r->slots[r->slotIndex].uploadCapacity?3:0;
}

extern "C" JNIEXPORT jint JNICALL Java_com_redondoguibi_direct_NativeDx12_renderTranslucentInterop(JNIEnv* env,jclass type,jfloatArray matrix,jdouble cameraX,jdouble cameraY,jdouble cameraZ,jlong frameIndex){
    auto* r=g_interopRenderer;
    if(!r||!matrix||env->GetArrayLength(matrix)!=28||frameIndex<0||frameIndex>=INT64_MAX-1){throwJava(env,"Invalid translucent frame");return -1;}
    int status=Java_com_redondoguibi_direct_NativeDx12_translucentFrameStatus(env,type);if(status!=0)return status;
    auto& slot=r->slots[r->slotIndex];
    // Reject before importing the GL scene or recording commands. Every section's sorted
    // indices share the existing bounded upload ring; no unbounded emergency allocation.
    struct ImportFenceGuard { InteropRenderer* r;FrameSlot& slot;UINT64 value;bool submitted=false;~ImportFenceGuard(){if(!submitted){if(SUCCEEDED(r->queue->Signal(r->fence,value))){r->lastSignaledValue=value;slot.fenceValue=value;}}} } guard{r,slot,static_cast<UINT64>(frameIndex)+1};
    if(r->uploadOpen){throwJava(env,"Translucent frame cannot overtake pending uploads");return -1;}slot.uploadOffset=0;
    // GL has finished capturing the current opaque scene and entities into the interop images.
    ID3D11Resource* wrapped[]={r->wrappedColor,r->wrappedDepth};r->d3d11On12->AcquireWrappedResources(wrapped,2);
    r->d3d11Context->CopyResource(r->wrappedColor,r->interopTexture);r->d3d11Context->CopyResource(r->wrappedDepth,r->interopDepth);
    r->d3d11On12->ReleaseWrappedResources(wrapped,2);r->d3d11Context->Flush();
    if(FAILED(r->d3d11Device->GetDeviceRemovedReason())){throwJava(env,"Scene import failed");return -1;}
    env->GetFloatArrayRegion(matrix,0,28,r->viewProjection);
    std::memcpy(slot.passConstantsMapped+kMaxExtensionPasses*256,r->geometryConstants,256);
    auto* list=r->translucentList;if(FAILED(slot.translucent->Reset())||FAILED(list->Reset(slot.translucent,r->translucentPipeline))){throwJava(env,"Translucent list reset failed");return -1;}
    if(r->profiling)list->EndQuery(r->timestampHeap,D3D12_QUERY_TYPE_TIMESTAMP,r->slotIndex*2);
    if(r->geometrySettings[1]>0||r->geometrySettings[2]>0){
        if(!r->sceneColor){
            auto desc=r->colorTarget->GetDesc();desc.Flags=D3D12_RESOURCE_FLAG_NONE;D3D12_HEAP_PROPERTIES heap={};heap.Type=D3D12_HEAP_TYPE_DEFAULT;
            if(FAILED(r->device->CreateCommittedResource(&heap,D3D12_HEAP_FLAG_NONE,&desc,D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE,nullptr,IID_PPV_ARGS(&r->sceneColor)))){throwJava(env,"Water scene allocation failed");return -1;}
            D3D12_SHADER_RESOURCE_VIEW_DESC srv={};srv.Format=kColorFormat;srv.ViewDimension=D3D12_SRV_DIMENSION_TEXTURE2D;srv.Shader4ComponentMapping=D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING;srv.Texture2D.MipLevels=1;r->device->CreateShaderResourceView(r->sceneColor,&srv,materialSrv(r,64));
        }
        if(!r->sceneDepth){auto desc=r->depthTarget->GetDesc();desc.Flags=D3D12_RESOURCE_FLAG_NONE;D3D12_HEAP_PROPERTIES heap={};heap.Type=D3D12_HEAP_TYPE_DEFAULT;
            if(FAILED(r->device->CreateCommittedResource(&heap,D3D12_HEAP_FLAG_NONE,&desc,D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE,nullptr,IID_PPV_ARGS(&r->sceneDepth)))){throwJava(env,"Water depth snapshot allocation failed");return -1;}}
        setDepthSrv(r,r->sceneDepth,65);
        D3D12_RESOURCE_BARRIER copy[]={transition(r->sceneColor,D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE,D3D12_RESOURCE_STATE_COPY_DEST),transition(r->colorTarget,D3D12_RESOURCE_STATE_RENDER_TARGET,D3D12_RESOURCE_STATE_COPY_SOURCE),transition(r->sceneDepth,D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE,D3D12_RESOURCE_STATE_COPY_DEST),transition(r->depthTarget,D3D12_RESOURCE_STATE_DEPTH_WRITE,D3D12_RESOURCE_STATE_COPY_SOURCE)};list->ResourceBarrier(4,copy);list->CopyResource(r->sceneColor,r->colorTarget);list->CopyResource(r->sceneDepth,r->depthTarget);
        D3D12_RESOURCE_BARRIER restore[]={transition(r->sceneColor,D3D12_RESOURCE_STATE_COPY_DEST,D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE),transition(r->colorTarget,D3D12_RESOURCE_STATE_COPY_SOURCE,D3D12_RESOURCE_STATE_RENDER_TARGET),transition(r->sceneDepth,D3D12_RESOURCE_STATE_COPY_DEST,D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE),transition(r->depthTarget,D3D12_RESOURCE_STATE_COPY_SOURCE,D3D12_RESOURCE_STATE_DEPTH_WRITE)};list->ResourceBarrier(4,restore);
    }else if(r->sceneColor||r->sceneDepth){releaseSceneSnapshots(r);}
    auto rtv=r->rtvHeap->GetCPUDescriptorHandleForHeapStart();auto dsv=r->dsvHeap->GetCPUDescriptorHandleForHeapStart();
    list->OMSetRenderTargets(1,&rtv,FALSE,&dsv);ID3D12DescriptorHeap* heaps[]={r->srvHeap};list->SetDescriptorHeaps(1,heaps);list->SetGraphicsRootSignature(r->rootSignature);list->SetGraphicsRootDescriptorTable(2,r->srvHeap->GetGPUDescriptorHandleForHeapStart());list->SetGraphicsRootConstantBufferView(3,slot.passConstants->GetGPUVirtualAddress()+kMaxExtensionPasses*256);list->SetGraphicsRoot32BitConstants(0,28,r->viewProjection,0);list->IASetPrimitiveTopology(D3D_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    D3D12_VIEWPORT viewport={0,0,static_cast<float>(r->width),static_cast<float>(r->height),0,1};D3D12_RECT rect={0,0,static_cast<LONG>(r->width),static_cast<LONG>(r->height)};list->RSSetViewports(1,&viewport);list->RSSetScissorRects(1,&rect);
    std::vector<const TerrainSection*> sections;for(auto& entry:r->visible)if(!entry.section->transparent.empty())sections.push_back(entry.section);
    auto distance=[&](const TerrainSection* s){double x=s->x+8-cameraX,y=s->y+8-cameraY,z=s->z+8-cameraZ;return x*x+y*y+z*z;};
    std::stable_sort(sections.begin(),sections.end(),[&](auto* a,auto* b){return distance(a)>distance(b);});
    for(auto* section:sections){
        std::vector<UINT> order(section->transparent.size());for(UINT i=0;i<order.size();i++)order[i]=i;
        auto quadDistance=[&](UINT i){auto& q=section->transparent[i];double x=section->x+q.center[0]-cameraX,y=section->y+q.center[1]-cameraY,z=section->z+q.center[2]-cameraZ;return x*x+y*y+z*z;};
        std::stable_sort(order.begin(),order.end(),[&](UINT a,UINT b){return quadDistance(a)>quadDistance(b);});
        UINT64 bytes=static_cast<UINT64>(order.size())*24,offset=0;if(!reserveUploadRange(r,bytes,256,&offset)){throwJava(env,"Translucent index budget exceeded; retain vanilla layer");return -1;}
        auto* dst=reinterpret_cast<UINT*>(slot.uploadMapped+offset);for(UINT i:order){std::memcpy(dst,section->transparent[i].indices.data(),24);dst+=6;}
        D3D12_INDEX_BUFFER_VIEW indices={slot.uploadBuffer->GetGPUVirtualAddress()+offset,static_cast<UINT>(bytes),DXGI_FORMAT_R32_UINT};list->IASetIndexBuffer(&indices);list->IASetVertexBuffers(0,1,&section->vertexView);
        float positions[]={static_cast<float>(section->x-cameraX),static_cast<float>(section->y-cameraY),static_cast<float>(section->z-cameraZ),-1};UINT constants[5];std::memcpy(constants,positions,16);constants[4]=r->atlasIndex;list->SetGraphicsRoot32BitConstants(1,5,constants,0);list->DrawIndexedInstanced(static_cast<UINT>(order.size())*6,1,0,0,0);
    }
    if(FAILED(list->Close())){throwJava(env,"Translucent recording failed");return -1;}ID3D12CommandList* lists[]={list};r->queue->ExecuteCommandLists(1,lists);
    if(!copyToInteropTexture(r)){throwJava(env,"Translucent interop copy failed");return -1;}
    if(r->profiling){
        if(FAILED(slot.finish->Reset())||FAILED(r->finishList->Reset(slot.finish,nullptr))){throwJava(env,"Translucent profiling reset failed");return -1;}
        UINT query=r->slotIndex*2;r->finishList->EndQuery(r->timestampHeap,D3D12_QUERY_TYPE_TIMESTAMP,query+1);
        r->finishList->ResolveQueryData(r->timestampHeap,D3D12_QUERY_TYPE_TIMESTAMP,query,2,r->timestampReadback,query*sizeof(UINT64));
        if(FAILED(r->finishList->Close())){throwJava(env,"Translucent profiling close failed");return -1;}
        ID3D12CommandList* finish[]={r->finishList};r->queue->ExecuteCommandLists(1,finish);
    }
    UINT64 value=static_cast<UINT64>(frameIndex)+1;
    if(FAILED(r->queue->Signal(r->fence,value))){throwJava(env,"Translucent fence failed");return -1;}
    r->lastSignaledValue=value;slot.fenceValue=value;slot.timestampValid=r->profiling;slot.drawCount=static_cast<UINT>(sections.size());r->slotIndex=(r->slotIndex+1)%kFramesInFlight;guard.submitted=true;
    return 0;
}
