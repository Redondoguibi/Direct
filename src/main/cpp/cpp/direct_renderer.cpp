#include <jni.h>
#include <windows.h>
#include <d3d12.h>
#include <dxgi1_6.h>
#include <d3dcompiler.h>
#include <cstring>
#include <limits>

namespace {

constexpr UINT kFrameCount = 2;
constexpr jint kMaxPasses = 8;
constexpr UINT kUniformFloatCount = 64;
constexpr UINT kConstantBufferSize = 512;
constexpr UINT kIntermediateCount = kMaxPasses - 1;
constexpr DWORD kWaitTimeoutMs = 5000;
constexpr UINT64 kFenceError = std::numeric_limits<UINT64>::max();
constexpr float kClearColor[4] = {0.0f, 0.0f, 0.0f, 1.0f};

struct FrameResources {
    ID3D12CommandAllocator* allocator;
    ID3D12Resource* constants[kMaxPasses];
    float* mappedConstants[kMaxPasses];
    ID3D12Resource* sceneUpload;
    BYTE* mappedSceneUpload;
    UINT64 fenceValue;
};

struct Renderer {
    HWND window;
    UINT width;
    UINT height;
    UINT passCount;
    UINT64 nextFenceValue;
    UINT64 frameNumber;
    bool broken;
    ID3D12Device* device;
    ID3D12CommandQueue* queue;
    IDXGISwapChain3* swapChain;
    ID3D12DescriptorHeap* rtvHeap;
    ID3D12DescriptorHeap* srvHeap;
    ID3D12RootSignature* rootSignature;
    ID3D12PipelineState* pipelines[kMaxPasses];
    ID3D12GraphicsCommandList* commandList;
    ID3D12Fence* fence;
    HANDLE fenceEvent;
    UINT rtvDescriptorSize;
    UINT srvDescriptorSize;
    ID3D12Resource* backBuffers[kFrameCount];
    D3D12_CPU_DESCRIPTOR_HANDLE renderTargets[kFrameCount];
    ID3D12Resource* sceneTexture;
    D3D12_PLACED_SUBRESOURCE_FOOTPRINT sceneFootprint;
    UINT sceneRowCount;
    UINT64 sceneRowSize;
    UINT64 sceneUploadSize;
    ID3D12Resource* intermediateTargets[kIntermediateCount];
    D3D12_CPU_DESCRIPTOR_HANDLE intermediateRenderTargets[kIntermediateCount];
    FrameResources frames[kFrameCount];
};

Renderer* g_renderer;

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

bool fail(Renderer* renderer, JNIEnv* env, const char* message) {
    renderer->broken = true;
    throwJava(env, message);
    return false;
}

bool waitFenceValue(Renderer* renderer, UINT64 value, JNIEnv* env) {
    const UINT64 completed = renderer->fence->GetCompletedValue();
    if (completed == kFenceError) {
        return fail(renderer, env, "DirectX 12 device was removed.");
    }
    if (completed >= value) {
        return true;
    }
    if (FAILED(renderer->fence->SetEventOnCompletion(value, renderer->fenceEvent))
            || WaitForSingleObject(renderer->fenceEvent, kWaitTimeoutMs) != WAIT_OBJECT_0) {
        return fail(renderer, env, "DirectX 12 timed out waiting for its frame fence.");
    }
    return renderer->fence->GetCompletedValue() != kFenceError
            || fail(renderer, env, "DirectX 12 device was removed.");
}

bool waitForGpu(Renderer* renderer, JNIEnv* env) {
    const UINT64 value = ++renderer->nextFenceValue;
    if (FAILED(renderer->queue->Signal(renderer->fence, value))) {
        return fail(renderer, env, "DirectX 12 failed to signal its frame fence.");
    }
    return waitFenceValue(renderer, value, env);
}

void releaseSceneResources(Renderer* renderer) {
    for (UINT index = 0; index < kFrameCount; ++index) {
        FrameResources& frame = renderer->frames[index];
        if (frame.sceneUpload != nullptr && frame.mappedSceneUpload != nullptr) {
            frame.sceneUpload->Unmap(0, nullptr);
        }
        frame.mappedSceneUpload = nullptr;
        release(frame.sceneUpload);
    }
    release(renderer->sceneTexture);
    for (UINT index = 0; index < kIntermediateCount; ++index) {
        release(renderer->intermediateTargets[index]);
    }
}

void destroyRenderer(Renderer* renderer) {
    if (renderer == nullptr) {
        return;
    }
    if (renderer->queue != nullptr && renderer->fence != nullptr && renderer->fenceEvent != nullptr
            && renderer->fence->GetCompletedValue() != kFenceError) {
        const UINT64 value = ++renderer->nextFenceValue;
        if (SUCCEEDED(renderer->queue->Signal(renderer->fence, value))
                && renderer->fence->GetCompletedValue() < value
                && SUCCEEDED(renderer->fence->SetEventOnCompletion(value, renderer->fenceEvent))) {
            WaitForSingleObject(renderer->fenceEvent, kWaitTimeoutMs);
        }
    }
    releaseSceneResources(renderer);
    for (UINT index = 0; index < kFrameCount; ++index) {
        release(renderer->backBuffers[index]);
        FrameResources& frame = renderer->frames[index];
        for (UINT pass = 0; pass < kMaxPasses; ++pass) {
            if (frame.constants[pass] != nullptr && frame.mappedConstants[pass] != nullptr) {
                frame.constants[pass]->Unmap(0, nullptr);
            }
            frame.mappedConstants[pass] = nullptr;
            release(frame.constants[pass]);
        }
        release(frame.allocator);
    }
    for (UINT index = 0; index < kMaxPasses; ++index) {
        release(renderer->pipelines[index]);
    }
    release(renderer->fence);
    release(renderer->commandList);
    release(renderer->rootSignature);
    release(renderer->rtvHeap);
    release(renderer->srvHeap);
    release(renderer->swapChain);
    release(renderer->queue);
    release(renderer->device);
    if (renderer->fenceEvent != nullptr) {
        CloseHandle(renderer->fenceEvent);
    }
    delete renderer;
}

bool createRenderTargets(Renderer* renderer, JNIEnv* env) {
    D3D12_CPU_DESCRIPTOR_HANDLE handle = renderer->rtvHeap->GetCPUDescriptorHandleForHeapStart();
    for (UINT index = 0; index < kFrameCount; ++index) {
        if (FAILED(renderer->swapChain->GetBuffer(index, IID_PPV_ARGS(&renderer->backBuffers[index])))) {
            return fail(renderer, env, "DirectX 12 failed to acquire a swap-chain buffer.");
        }
        renderer->renderTargets[index] = handle;
        renderer->device->CreateRenderTargetView(renderer->backBuffers[index], nullptr, handle);
        handle.ptr += renderer->rtvDescriptorSize;
    }
    return true;
}

bool createSceneResources(Renderer* renderer, JNIEnv* env) {
    D3D12_RESOURCE_DESC texture = {};
    texture.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
    texture.Width = renderer->width;
    texture.Height = renderer->height;
    texture.DepthOrArraySize = 1;
    texture.MipLevels = 1;
    texture.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    texture.SampleDesc.Count = 1;
    texture.Layout = D3D12_TEXTURE_LAYOUT_UNKNOWN;

    D3D12_HEAP_PROPERTIES defaultHeap = {};
    defaultHeap.Type = D3D12_HEAP_TYPE_DEFAULT;
    if (FAILED(renderer->device->CreateCommittedResource(&defaultHeap, D3D12_HEAP_FLAG_NONE, &texture,
            D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE, nullptr, IID_PPV_ARGS(&renderer->sceneTexture)))) {
        return fail(renderer, env, "DirectX 12 scene texture creation failed.");
    }
    renderer->device->GetCopyableFootprints(&texture, 0, 1, 0, &renderer->sceneFootprint,
            &renderer->sceneRowCount, &renderer->sceneRowSize, &renderer->sceneUploadSize);

    D3D12_HEAP_PROPERTIES uploadHeap = {};
    uploadHeap.Type = D3D12_HEAP_TYPE_UPLOAD;
    D3D12_RESOURCE_DESC uploadBuffer = {};
    uploadBuffer.Dimension = D3D12_RESOURCE_DIMENSION_BUFFER;
    uploadBuffer.Width = renderer->sceneUploadSize;
    uploadBuffer.Height = 1;
    uploadBuffer.DepthOrArraySize = 1;
    uploadBuffer.MipLevels = 1;
    uploadBuffer.SampleDesc.Count = 1;
    uploadBuffer.Layout = D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
    for (UINT index = 0; index < kFrameCount; ++index) {
        FrameResources& frame = renderer->frames[index];
        void* mapped = nullptr;
        const D3D12_RANGE readRange = {0, 0};
        if (FAILED(renderer->device->CreateCommittedResource(&uploadHeap, D3D12_HEAP_FLAG_NONE, &uploadBuffer,
                D3D12_RESOURCE_STATE_GENERIC_READ, nullptr, IID_PPV_ARGS(&frame.sceneUpload)))
                || FAILED(frame.sceneUpload->Map(0, &readRange, &mapped))) {
            return fail(renderer, env, "DirectX 12 scene upload buffer creation failed.");
        }
        frame.mappedSceneUpload = static_cast<BYTE*>(mapped);
    }

    D3D12_CPU_DESCRIPTOR_HANDLE rtv = renderer->rtvHeap->GetCPUDescriptorHandleForHeapStart();
    rtv.ptr += static_cast<SIZE_T>(kFrameCount) * renderer->rtvDescriptorSize;
    D3D12_CPU_DESCRIPTOR_HANDLE srv = renderer->srvHeap->GetCPUDescriptorHandleForHeapStart();
    renderer->device->CreateShaderResourceView(renderer->sceneTexture, nullptr, srv);
    srv.ptr += renderer->srvDescriptorSize;

    D3D12_CLEAR_VALUE clear = {};
    clear.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    std::memcpy(clear.Color, kClearColor, sizeof(kClearColor));
    D3D12_RESOURCE_DESC renderTexture = texture;
    renderTexture.Flags = D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET;
    for (UINT index = 0; index + 1 < renderer->passCount; ++index) {
        if (FAILED(renderer->device->CreateCommittedResource(&defaultHeap, D3D12_HEAP_FLAG_NONE, &renderTexture,
                D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE, &clear,
                IID_PPV_ARGS(&renderer->intermediateTargets[index])))) {
            return fail(renderer, env, "DirectX 12 shader pass texture creation failed.");
        }
        renderer->intermediateRenderTargets[index] = rtv;
        renderer->device->CreateRenderTargetView(renderer->intermediateTargets[index], nullptr, rtv);
        renderer->device->CreateShaderResourceView(renderer->intermediateTargets[index], nullptr, srv);
        rtv.ptr += renderer->rtvDescriptorSize;
        srv.ptr += renderer->srvDescriptorSize;
    }
    return true;
}

bool createRootSignature(Renderer* renderer, JNIEnv* env) {
    D3D12_DESCRIPTOR_RANGE textureRange = {};
    textureRange.RangeType = D3D12_DESCRIPTOR_RANGE_TYPE_SRV;
    textureRange.NumDescriptors = 1;
    textureRange.OffsetInDescriptorsFromTableStart = D3D12_DESCRIPTOR_RANGE_OFFSET_APPEND;
    D3D12_ROOT_PARAMETER parameters[2] = {};
    parameters[0].ParameterType = D3D12_ROOT_PARAMETER_TYPE_CBV;
    parameters[0].ShaderVisibility = D3D12_SHADER_VISIBILITY_PIXEL;
    parameters[1].ParameterType = D3D12_ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE;
    parameters[1].DescriptorTable.NumDescriptorRanges = 1;
    parameters[1].DescriptorTable.pDescriptorRanges = &textureRange;
    parameters[1].ShaderVisibility = D3D12_SHADER_VISIBILITY_PIXEL;
    D3D12_STATIC_SAMPLER_DESC sampler = {};
    sampler.Filter = D3D12_FILTER_MIN_MAG_MIP_LINEAR;
    sampler.AddressU = D3D12_TEXTURE_ADDRESS_MODE_CLAMP;
    sampler.AddressV = D3D12_TEXTURE_ADDRESS_MODE_CLAMP;
    sampler.AddressW = D3D12_TEXTURE_ADDRESS_MODE_CLAMP;
    sampler.MaxAnisotropy = 1;
    sampler.ComparisonFunc = D3D12_COMPARISON_FUNC_NEVER;
    sampler.MaxLOD = D3D12_FLOAT32_MAX;
    sampler.ShaderVisibility = D3D12_SHADER_VISIBILITY_PIXEL;
    D3D12_ROOT_SIGNATURE_DESC description = {};
    description.NumParameters = 2;
    description.pParameters = parameters;
    description.NumStaticSamplers = 1;
    description.pStaticSamplers = &sampler;
    ID3DBlob* serialized = nullptr;
    ID3DBlob* errors = nullptr;
    HRESULT result = D3D12SerializeRootSignature(&description, D3D_ROOT_SIGNATURE_VERSION_1,
            &serialized, &errors);
    release(errors);
    if (SUCCEEDED(result)) {
        result = renderer->device->CreateRootSignature(0, serialized->GetBufferPointer(),
                serialized->GetBufferSize(), IID_PPV_ARGS(&renderer->rootSignature));
    }
    release(serialized);
    return SUCCEEDED(result) || fail(renderer, env, "DirectX 12 root signature creation failed.");
}

bool createPipeline(Renderer* renderer, UINT index, const char* source, SIZE_T sourceLength,
        ID3DBlob* vertexShader, JNIEnv* env) {
    ID3DBlob* pixelShader = nullptr;
    ID3DBlob* errors = nullptr;
    if (FAILED(D3DCompile(source, sourceLength, nullptr, nullptr, nullptr, "main", "ps_5_0",
            D3DCOMPILE_ENABLE_STRICTNESS, 0, &pixelShader, &errors))) {
        throwJava(env, errors != nullptr ? static_cast<const char*>(errors->GetBufferPointer())
                : "HLSL pixel shader compilation failed.");
        release(errors);
        release(pixelShader);
        return false;
    }
    release(errors);

    D3D12_GRAPHICS_PIPELINE_STATE_DESC description = {};
    description.pRootSignature = renderer->rootSignature;
    description.VS = {vertexShader->GetBufferPointer(), vertexShader->GetBufferSize()};
    description.PS = {pixelShader->GetBufferPointer(), pixelShader->GetBufferSize()};
    description.BlendState.RenderTarget[0].RenderTargetWriteMask = D3D12_COLOR_WRITE_ENABLE_ALL;
    description.SampleMask = UINT_MAX;
    description.RasterizerState.FillMode = D3D12_FILL_MODE_SOLID;
    description.RasterizerState.CullMode = D3D12_CULL_MODE_NONE;
    description.RasterizerState.DepthClipEnable = TRUE;
    description.PrimitiveTopologyType = D3D12_PRIMITIVE_TOPOLOGY_TYPE_TRIANGLE;
    description.NumRenderTargets = 1;
    description.RTVFormats[0] = DXGI_FORMAT_R8G8B8A8_UNORM;
    description.SampleDesc.Count = 1;
    const HRESULT result = renderer->device->CreateGraphicsPipelineState(&description,
            IID_PPV_ARGS(&renderer->pipelines[index]));
    release(pixelShader);
    if (FAILED(result)) {
        throwJava(env, "DirectX 12 failed to create a shader pass pipeline.");
        return false;
    }
    return true;
}

bool createFrameResources(Renderer* renderer, JNIEnv* env) {
    D3D12_HEAP_PROPERTIES heap = {};
    heap.Type = D3D12_HEAP_TYPE_UPLOAD;
    D3D12_RESOURCE_DESC buffer = {};
    buffer.Dimension = D3D12_RESOURCE_DIMENSION_BUFFER;
    buffer.Width = kConstantBufferSize;
    buffer.Height = 1;
    buffer.DepthOrArraySize = 1;
    buffer.MipLevels = 1;
    buffer.SampleDesc.Count = 1;
    buffer.Layout = D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
    for (UINT index = 0; index < kFrameCount; ++index) {
        FrameResources& frame = renderer->frames[index];
        if (FAILED(renderer->device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT,
                IID_PPV_ARGS(&frame.allocator)))) {
            return fail(renderer, env, "DirectX 12 command allocator creation failed.");
        }
        for (UINT pass = 0; pass < renderer->passCount; ++pass) {
            void* mapped = nullptr;
            const D3D12_RANGE readRange = {0, 0};
            if (FAILED(renderer->device->CreateCommittedResource(&heap, D3D12_HEAP_FLAG_NONE, &buffer,
                    D3D12_RESOURCE_STATE_GENERIC_READ, nullptr, IID_PPV_ARGS(&frame.constants[pass])))
                    || FAILED(frame.constants[pass]->Map(0, &readRange, &mapped))) {
                return fail(renderer, env, "DirectX 12 constant buffer creation failed.");
            }
            frame.mappedConstants[pass] = static_cast<float*>(mapped);
        }
    }
    return true;
}

bool initializeRenderer(Renderer* renderer, jlong hwnd, jint width, jint height,
        jobjectArray shaders, JNIEnv* env) {
    if (hwnd == 0 || width <= 0 || height <= 0 || shaders == nullptr) {
        throwJava(env, "DirectX 12 initialization received invalid window or shader data.");
        return false;
    }
    const jsize passCount = env->GetArrayLength(shaders);
    if (passCount < 1 || passCount > kMaxPasses) {
        throwJava(env, "A DirectX 12 shader pack must contain between 1 and 8 passes.");
        return false;
    }
    renderer->window = reinterpret_cast<HWND>(static_cast<intptr_t>(hwnd));
    renderer->width = static_cast<UINT>(width);
    renderer->height = static_cast<UINT>(height);
    renderer->passCount = static_cast<UINT>(passCount);

    if (FAILED(D3D12CreateDevice(nullptr, D3D_FEATURE_LEVEL_11_0, IID_PPV_ARGS(&renderer->device)))) {
        return fail(renderer, env, "DirectX 12 device creation failed.");
    }
    D3D12_COMMAND_QUEUE_DESC queueDescription = {};
    queueDescription.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
    if (FAILED(renderer->device->CreateCommandQueue(&queueDescription, IID_PPV_ARGS(&renderer->queue)))) {
        return fail(renderer, env, "DirectX 12 command queue creation failed.");
    }

    IDXGIFactory4* factory = nullptr;
    if (FAILED(CreateDXGIFactory2(0, IID_PPV_ARGS(&factory)))) {
        return fail(renderer, env, "DXGI factory creation failed.");
    }
    DXGI_SWAP_CHAIN_DESC1 swapDescription = {};
    swapDescription.Width = renderer->width;
    swapDescription.Height = renderer->height;
    swapDescription.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    swapDescription.SampleDesc.Count = 1;
    swapDescription.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    swapDescription.BufferCount = kFrameCount;
    swapDescription.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;
    IDXGISwapChain1* swapChain = nullptr;
    HRESULT result = factory->CreateSwapChainForHwnd(renderer->queue, renderer->window,
            &swapDescription, nullptr, nullptr, &swapChain);
    if (SUCCEEDED(result)) {
        factory->MakeWindowAssociation(renderer->window, DXGI_MWA_NO_ALT_ENTER);
        result = swapChain->QueryInterface(IID_PPV_ARGS(&renderer->swapChain));
    }
    release(swapChain);
    release(factory);
    if (FAILED(result)) {
        return fail(renderer, env, "DXGI failed to create a swap chain for the Minecraft window.");
    }

    D3D12_DESCRIPTOR_HEAP_DESC heapDescription = {};
    heapDescription.NumDescriptors = kFrameCount + renderer->passCount - 1;
    heapDescription.Type = D3D12_DESCRIPTOR_HEAP_TYPE_RTV;
    if (FAILED(renderer->device->CreateDescriptorHeap(&heapDescription, IID_PPV_ARGS(&renderer->rtvHeap)))) {
        return fail(renderer, env, "DirectX 12 RTV descriptor heap creation failed.");
    }
    renderer->rtvDescriptorSize = renderer->device->GetDescriptorHandleIncrementSize(D3D12_DESCRIPTOR_HEAP_TYPE_RTV);
    heapDescription.NumDescriptors = renderer->passCount;
    heapDescription.Type = D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV;
    heapDescription.Flags = D3D12_DESCRIPTOR_HEAP_FLAG_SHADER_VISIBLE;
    if (FAILED(renderer->device->CreateDescriptorHeap(&heapDescription, IID_PPV_ARGS(&renderer->srvHeap)))) {
        return fail(renderer, env, "DirectX 12 shader resource descriptor heap creation failed.");
    }
    renderer->srvDescriptorSize = renderer->device->GetDescriptorHandleIncrementSize(
            D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV);
    if (!createRenderTargets(renderer, env) || !createSceneResources(renderer, env)
            || !createRootSignature(renderer, env)) {
        return false;
    }

    static const char vertexSource[] =
            "struct VertexOut { float4 position : SV_Position; };"
            "VertexOut main(uint id : SV_VertexID) {"
            " VertexOut o; float2 p = float2((id << 1) & 2, id & 2);"
            " o.position = float4(p * float2(2.0, -2.0) + float2(-1.0, 1.0), 0.0, 1.0);"
            " return o; }";
    ID3DBlob* vertexShader = nullptr;
    ID3DBlob* errors = nullptr;
    result = D3DCompile(vertexSource, sizeof(vertexSource) - 1, nullptr, nullptr, nullptr,
            "main", "vs_5_0", D3DCOMPILE_ENABLE_STRICTNESS, 0, &vertexShader, &errors);
    release(errors);
    if (FAILED(result)) {
        throwJava(env, "DirectX 12 fullscreen vertex shader compilation failed.");
        return false;
    }
    for (jsize index = 0; index < passCount; ++index) {
        jstring shader = static_cast<jstring>(env->GetObjectArrayElement(shaders, index));
        if (shader == nullptr) {
            release(vertexShader);
            throwJava(env, "Shader pack contains a null HLSL source.");
            return false;
        }
        const char* source = env->GetStringUTFChars(shader, nullptr);
        bool succeeded = false;
        if (source != nullptr) {
            succeeded = createPipeline(renderer, static_cast<UINT>(index), source,
                    static_cast<SIZE_T>(env->GetStringUTFLength(shader)), vertexShader, env);
            env->ReleaseStringUTFChars(shader, source);
        }
        env->DeleteLocalRef(shader);
        if (!succeeded) {
            release(vertexShader);
            return false;
        }
    }
    release(vertexShader);

    if (!createFrameResources(renderer, env)) {
        return false;
    }
    if (FAILED(renderer->device->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT,
            renderer->frames[0].allocator, nullptr, IID_PPV_ARGS(&renderer->commandList)))
            || FAILED(renderer->commandList->Close())) {
        return fail(renderer, env, "DirectX 12 command list creation failed.");
    }
    if (FAILED(renderer->device->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&renderer->fence)))) {
        return fail(renderer, env, "DirectX 12 frame fence creation failed.");
    }
    renderer->fenceEvent = CreateEvent(nullptr, FALSE, FALSE, nullptr);
    return renderer->fenceEvent != nullptr || fail(renderer, env, "DirectX 12 frame fence event creation failed.");
}

bool resizeRenderer(Renderer* renderer, UINT width, UINT height, JNIEnv* env) {
    if (renderer->broken) {
        throwJava(env, "DirectX 12 renderer is in a failed state.");
        return false;
    }
    if (width == 0 || height == 0 || (renderer->width == width && renderer->height == height)) {
        return true;
    }
    if (!waitForGpu(renderer, env)) {
        return false;
    }
    releaseSceneResources(renderer);
    for (UINT index = 0; index < kFrameCount; ++index) {
        release(renderer->backBuffers[index]);
        renderer->frames[index].fenceValue = 0;
    }
    if (FAILED(renderer->swapChain->ResizeBuffers(kFrameCount, width, height, DXGI_FORMAT_R8G8B8A8_UNORM, 0))) {
        return fail(renderer, env, "DXGI failed to resize the Minecraft swap chain.");
    }
    renderer->width = width;
    renderer->height = height;
    return createRenderTargets(renderer, env) && createSceneResources(renderer, env);
}

void transition(ID3D12GraphicsCommandList* list, ID3D12Resource* resource,
        D3D12_RESOURCE_STATES before, D3D12_RESOURCE_STATES after) {
    D3D12_RESOURCE_BARRIER barrier = {};
    barrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
    barrier.Transition.pResource = resource;
    barrier.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;
    barrier.Transition.StateBefore = before;
    barrier.Transition.StateAfter = after;
    list->ResourceBarrier(1, &barrier);
}

bool renderFrame(Renderer* renderer, jfloat timeSeconds, jint width, jint height,
        jobjectArray uniforms, jobject rgba, JNIEnv* env) {
    if (renderer->broken) {
        throwJava(env, "DirectX 12 renderer is in a failed state.");
        return false;
    }
    if (uniforms == nullptr || env->GetArrayLength(uniforms) != static_cast<jsize>(renderer->passCount)) {
        throwJava(env, "Shader uniform data does not match the compiled pass count.");
        return false;
    }
    const BYTE* sourcePixels = rgba != nullptr ? static_cast<const BYTE*>(env->GetDirectBufferAddress(rgba)) : nullptr;
    const jlong sourceCapacity = rgba != nullptr ? env->GetDirectBufferCapacity(rgba) : -1;
    if (width <= 0 || height <= 0 || sourcePixels == nullptr
            || sourceCapacity < static_cast<jlong>(width) * height * 4) {
        throwJava(env, "The OpenGL scene capture must be a direct RGBA buffer matching the framebuffer size.");
        return false;
    }

    // Lê e valida tudo antes de abrir a command list: nenhum erro pode deixá-la aberta.
    jfloat passData[kMaxPasses][kUniformFloatCount];
    for (UINT pass = 0; pass < renderer->passCount; ++pass) {
        jfloatArray values = static_cast<jfloatArray>(env->GetObjectArrayElement(uniforms, pass));
        const bool valid = values != nullptr && env->GetArrayLength(values) == static_cast<jsize>(kUniformFloatCount);
        if (valid) {
            env->GetFloatArrayRegion(values, 0, kUniformFloatCount, passData[pass]);
        }
        if (values != nullptr) {
            env->DeleteLocalRef(values);
        }
        if (!valid) {
            throwJava(env, "Each shader pass must provide exactly 16 vec4 uniforms.");
            return false;
        }
        if (env->ExceptionCheck()) {
            return false;
        }
    }

    if (!resizeRenderer(renderer, static_cast<UINT>(width), static_cast<UINT>(height), env)) {
        return false;
    }
    const UINT frameIndex = renderer->swapChain->GetCurrentBackBufferIndex();
    FrameResources& frame = renderer->frames[frameIndex];
    if (frame.fenceValue != 0 && !waitFenceValue(renderer, frame.fenceValue, env)) {
        return false;
    }
    if (FAILED(frame.allocator->Reset()) || FAILED(renderer->commandList->Reset(frame.allocator, nullptr))) {
        return fail(renderer, env, "DirectX 12 command list reset failed.");
    }

    const SIZE_T rowBytes = static_cast<SIZE_T>(renderer->width) * 4;
    BYTE* uploadBase = frame.mappedSceneUpload + renderer->sceneFootprint.Offset;
    for (UINT row = 0; row < renderer->height; ++row) {
        std::memcpy(uploadBase + static_cast<SIZE_T>(row) * renderer->sceneFootprint.Footprint.RowPitch,
                sourcePixels + static_cast<SIZE_T>(row) * rowBytes, rowBytes);
    }

    ID3D12GraphicsCommandList* list = renderer->commandList;
    transition(list, renderer->sceneTexture, D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE,
            D3D12_RESOURCE_STATE_COPY_DEST);
    D3D12_TEXTURE_COPY_LOCATION destination = {};
    destination.pResource = renderer->sceneTexture;
    destination.Type = D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX;
    D3D12_TEXTURE_COPY_LOCATION source = {};
    source.pResource = frame.sceneUpload;
    source.Type = D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;
    source.PlacedFootprint = renderer->sceneFootprint;
    list->CopyTextureRegion(&destination, 0, 0, 0, &source, nullptr);
    transition(list, renderer->sceneTexture, D3D12_RESOURCE_STATE_COPY_DEST,
            D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE);

    ID3D12DescriptorHeap* heaps[] = {renderer->srvHeap};
    list->SetDescriptorHeaps(1, heaps);
    list->SetGraphicsRootSignature(renderer->rootSignature);
    const D3D12_VIEWPORT viewport = {0.0f, 0.0f, static_cast<float>(renderer->width),
            static_cast<float>(renderer->height), 0.0f, 1.0f};
    const D3D12_RECT scissor = {0, 0, static_cast<LONG>(renderer->width), static_cast<LONG>(renderer->height)};
    list->RSSetViewports(1, &viewport);
    list->RSSetScissorRects(1, &scissor);
    list->IASetPrimitiveTopology(D3D_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    const D3D12_GPU_DESCRIPTOR_HANDLE srvBase = renderer->srvHeap->GetGPUDescriptorHandleForHeapStart();

    for (UINT pass = 0; pass < renderer->passCount; ++pass) {
        float* constants = frame.mappedConstants[pass];
        constants[0] = timeSeconds;
        constants[1] = static_cast<float>(renderer->width);
        constants[2] = static_cast<float>(renderer->height);
        constants[3] = static_cast<float>(renderer->frameNumber);
        std::memcpy(constants + 4, passData[pass], sizeof(passData[pass]));

        const bool last = pass + 1 == renderer->passCount;
        ID3D12Resource* output = last ? renderer->backBuffers[frameIndex] : renderer->intermediateTargets[pass];
        const D3D12_CPU_DESCRIPTOR_HANDLE rtv = last ? renderer->renderTargets[frameIndex]
                : renderer->intermediateRenderTargets[pass];
        const D3D12_RESOURCE_STATES idle = last ? D3D12_RESOURCE_STATE_PRESENT
                : D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE;
        transition(list, output, idle, D3D12_RESOURCE_STATE_RENDER_TARGET);
        list->ClearRenderTargetView(rtv, kClearColor, 0, nullptr);
        list->OMSetRenderTargets(1, &rtv, FALSE, nullptr);
        list->SetPipelineState(renderer->pipelines[pass]);
        list->SetGraphicsRootConstantBufferView(0, frame.constants[pass]->GetGPUVirtualAddress());
        D3D12_GPU_DESCRIPTOR_HANDLE input = srvBase;
        input.ptr += static_cast<UINT64>(pass) * renderer->srvDescriptorSize;
        list->SetGraphicsRootDescriptorTable(1, input);
        list->DrawInstanced(3, 1, 0, 0);
        transition(list, output, D3D12_RESOURCE_STATE_RENDER_TARGET, idle);
    }
    if (FAILED(list->Close())) {
        return fail(renderer, env, "DirectX 12 command list close failed.");
    }
    ID3D12CommandList* lists[] = {list};
    renderer->queue->ExecuteCommandLists(1, lists);
    const HRESULT presentResult = renderer->swapChain->Present(1, 0);
    const UINT64 value = ++renderer->nextFenceValue;
    if (FAILED(renderer->queue->Signal(renderer->fence, value))) {
        return fail(renderer, env, "DirectX 12 failed to signal completion of the rendered frame.");
    }
    frame.fenceValue = value;
    if (FAILED(presentResult)) {
        return fail(renderer, env, presentResult == DXGI_ERROR_DEVICE_REMOVED
                || presentResult == DXGI_ERROR_DEVICE_RESET
                ? "DirectX 12 device was removed while presenting."
                : "DXGI failed to present the DirectX 12 frame.");
    }
    renderer->frameNumber++;
    return true;
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_initialize(JNIEnv* env, jclass, jlong hwnd,
        jint width, jint height, jobjectArray shaders) {
    destroyRenderer(g_renderer);
    g_renderer = nullptr;
    Renderer* renderer = new Renderer{};
    if (!initializeRenderer(renderer, hwnd, width, height, shaders, env)) {
        destroyRenderer(renderer);
        return;
    }
    g_renderer = renderer;
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_resize(JNIEnv* env, jclass, jint width, jint height) {
    if (g_renderer == nullptr) {
        throwJava(env, "DirectX 12 renderer is not initialized.");
        return;
    }
    if (width > 0 && height > 0) {
        resizeRenderer(g_renderer, static_cast<UINT>(width), static_cast<UINT>(height), env);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_render(JNIEnv* env, jclass, jfloat timeSeconds,
        jint width, jint height, jobjectArray uniforms, jobject rgba) {
    if (g_renderer == nullptr) {
        throwJava(env, "DirectX 12 renderer is not initialized.");
        return;
    }
    renderFrame(g_renderer, timeSeconds, width, height, uniforms, rgba, env);
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_shutdown(JNIEnv*, jclass) {
    destroyRenderer(g_renderer);
    g_renderer = nullptr;
} 