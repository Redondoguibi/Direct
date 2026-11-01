#include <jni.h>
#include <windows.h>
#include <d3d12.h>
#include <dxgi1_6.h>
#include <d3dcompiler.h>
#include <limits>

namespace {

constexpr UINT kFrameCount = 2;
constexpr jint kMaxPasses = 8;
constexpr UINT kUniformFloatCount = 64;
constexpr UINT kConstantBufferSize = 512;
constexpr UINT kIntermediateCount = kMaxPasses - 1;

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
    ID3D12Resource* sceneUpload;
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

void destroyRenderer(Renderer* renderer) {
    if (renderer == nullptr) {
        return;
    }
    if (renderer->queue != nullptr && renderer->fence != nullptr
            && renderer->fenceEvent != nullptr) {
        const UINT64 value = renderer->nextFenceValue++;
        if (SUCCEEDED(renderer->queue->Signal(renderer->fence, value))
                && renderer->fence->GetCompletedValue() < value) {
            if (SUCCEEDED(renderer->fence->SetEventOnCompletion(value, renderer->fenceEvent))) {
                WaitForSingleObject(renderer->fenceEvent, INFINITE);
            }
        }
    }
    for (UINT index = 0; index < kFrameCount; ++index) {
        release(renderer->backBuffers[index]);
        if (renderer->frames[index].sceneUpload != nullptr
                && renderer->frames[index].mappedSceneUpload != nullptr) {
            renderer->frames[index].sceneUpload->Unmap(0, nullptr);
            renderer->frames[index].mappedSceneUpload = nullptr;
        }
        release(renderer->frames[index].sceneUpload);
        for (UINT pass = 0; pass < kMaxPasses; ++pass) {
            if (renderer->frames[index].constants[pass] != nullptr
                    && renderer->frames[index].mappedConstants[pass] != nullptr) {
                renderer->frames[index].constants[pass]->Unmap(0, nullptr);
                renderer->frames[index].mappedConstants[pass] = nullptr;
            }
            release(renderer->frames[index].constants[pass]);
        }
        release(renderer->frames[index].allocator);
    }
    release(renderer->sceneTexture);
    for (UINT index = 0; index < kIntermediateCount; ++index) {
        release(renderer->intermediateTargets[index]);
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
        renderer->fenceEvent = nullptr;
    }
    delete renderer;
}

bool waitForGpu(Renderer* renderer, JNIEnv* env) {
    if (renderer->fenceEvent == nullptr) {
        throwJava(env, "DirectX 12 frame fence event is unavailable.");
        return false;
    }
    const UINT64 value = renderer->nextFenceValue++;
    if (FAILED(renderer->queue->Signal(renderer->fence, value))) {
        throwJava(env, "DirectX 12 failed to signal its frame fence.");
        return false;
    }
    if (renderer->fence->GetCompletedValue() < value) {
        if (FAILED(renderer->fence->SetEventOnCompletion(value, renderer->fenceEvent))) {
            throwJava(env, "DirectX 12 failed to wait for its frame fence.");
            return false;
        }
        if (WaitForSingleObject(renderer->fenceEvent, INFINITE) != WAIT_OBJECT_0) {
            throwJava(env, "DirectX 12 failed while waiting for its frame fence event.");
            return false;
        }
    }
    return true;
}

bool createRenderTargets(Renderer* renderer, JNIEnv* env) {
    D3D12_CPU_DESCRIPTOR_HANDLE handle = renderer->rtvHeap->GetCPUDescriptorHandleForHeapStart();
    for (UINT index = 0; index < kFrameCount; ++index) {
        if (FAILED(renderer->swapChain->GetBuffer(index, IID_PPV_ARGS(&renderer->backBuffers[index])))) {
            throwJava(env, "DirectX 12 failed to acquire a swap-chain buffer.");
            return false;
        }
        renderer->renderTargets[index] = handle;
        renderer->device->CreateRenderTargetView(renderer->backBuffers[index], nullptr, handle);
        handle.ptr += renderer->rtvDescriptorSize;
    }
    return true;
}

void releaseSceneResources(Renderer* renderer) {
    for (UINT index = 0; index < kFrameCount; ++index) {
        FrameResources& frame = renderer->frames[index];
        if (frame.sceneUpload != nullptr && frame.mappedSceneUpload != nullptr) {
            frame.sceneUpload->Unmap(0, nullptr);
            frame.mappedSceneUpload = nullptr;
        }
        release(frame.sceneUpload);
    }
    release(renderer->sceneTexture);
    for (UINT index = 0; index < kIntermediateCount; ++index) {
        release(renderer->intermediateTargets[index]);
    }
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
    if (FAILED(renderer->device->CreateCommittedResource(&defaultHeap, D3D12_HEAP_FLAG_NONE,
            &texture, D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE, nullptr,
            IID_PPV_ARGS(&renderer->sceneTexture)))) {
        throwJava(env, "DirectX 12 scene texture creation failed.");
        return false;
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
        if (FAILED(renderer->device->CreateCommittedResource(&uploadHeap, D3D12_HEAP_FLAG_NONE,
                &uploadBuffer, D3D12_RESOURCE_STATE_GENERIC_READ, nullptr,
                IID_PPV_ARGS(&frame.sceneUpload)))) {
            throwJava(env, "DirectX 12 scene upload buffer creation failed.");
            return false;
        }
        D3D12_RANGE readRange = {0, 0};
        void* mapped = nullptr;
        if (FAILED(frame.sceneUpload->Map(0, &readRange, &mapped))) {
            throwJava(env, "DirectX 12 scene upload buffer mapping failed.");
            return false;
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
    D3D12_RESOURCE_DESC renderTexture = texture;
    renderTexture.Flags = D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET;
    for (UINT index = 0; index + 1 < renderer->passCount; ++index) {
        if (FAILED(renderer->device->CreateCommittedResource(&defaultHeap,
                D3D12_HEAP_FLAG_NONE, &renderTexture,
                D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE, &clear,
                IID_PPV_ARGS(&renderer->intermediateTargets[index])))) {
            throwJava(env, "DirectX 12 shader pass texture creation failed.");
            return false;
        }
        renderer->intermediateRenderTargets[index] = rtv;
        renderer->device->CreateRenderTargetView(renderer->intermediateTargets[index], nullptr, rtv);
        renderer->device->CreateShaderResourceView(renderer->intermediateTargets[index], nullptr, srv);
        rtv.ptr += renderer->rtvDescriptorSize;
        srv.ptr += renderer->srvDescriptorSize;
    }
    return true;
}

bool createPipeline(Renderer* renderer, UINT index, const char* source, SIZE_T sourceLength,
        ID3DBlob* vertexShader, JNIEnv* env) {
    ID3DBlob* pixelShader = nullptr;
    ID3DBlob* errors = nullptr;
    HRESULT result = D3DCompile(source, sourceLength, nullptr, nullptr, nullptr, "main", "ps_5_0",
            D3DCOMPILE_ENABLE_STRICTNESS, 0, &pixelShader, &errors);
    if (FAILED(result)) {
        const char* message = errors != nullptr
                ? static_cast<const char*>(errors->GetBufferPointer())
                : "HLSL pixel shader compilation failed.";
        throwJava(env, message);
        release(errors);
        release(pixelShader);
        return false;
    }
    release(errors);

    D3D12_DESCRIPTOR_RANGE textureRange = {};
    textureRange.RangeType = D3D12_DESCRIPTOR_RANGE_TYPE_SRV;
    textureRange.NumDescriptors = 1;
    textureRange.BaseShaderRegister = 0;
    textureRange.OffsetInDescriptorsFromTableStart = D3D12_DESCRIPTOR_RANGE_OFFSET_APPEND;
    D3D12_ROOT_PARAMETER parameters[2] = {};
    parameters[0].ParameterType = D3D12_ROOT_PARAMETER_TYPE_CBV;
    parameters[0].Descriptor.ShaderRegister = 0;
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
    sampler.ShaderRegister = 0;
    sampler.MaxAnisotropy = 1;
    sampler.ComparisonFunc = D3D12_COMPARISON_FUNC_ALWAYS;
    sampler.BorderColor = D3D12_STATIC_BORDER_COLOR_TRANSPARENT_BLACK;
    sampler.MinLOD = 0.0f;
    sampler.MaxLOD = D3D12_FLOAT32_MAX;
    D3D12_ROOT_SIGNATURE_DESC rootDescription = {};
    rootDescription.NumParameters = 2;
    rootDescription.pParameters = parameters;
    rootDescription.NumStaticSamplers = 1;
    rootDescription.pStaticSamplers = &sampler;
    ID3DBlob* serializedRoot = nullptr;
    errors = nullptr;
    result = D3D12SerializeRootSignature(&rootDescription, D3D_ROOT_SIGNATURE_VERSION_1,
            &serializedRoot, &errors);
    if (FAILED(result)) {
        throwJava(env, "DirectX 12 failed to serialize the root signature.");
        release(errors);
        release(pixelShader);
        return false;
    }
    release(errors);
    if (renderer->rootSignature == nullptr) {
        result = renderer->device->CreateRootSignature(0, serializedRoot->GetBufferPointer(),
                serializedRoot->GetBufferSize(), IID_PPV_ARGS(&renderer->rootSignature));
        release(serializedRoot);
        if (FAILED(result)) {
            throwJava(env, "DirectX 12 failed to create the root signature.");
            release(pixelShader);
            return false;
        }
    } else {
        release(serializedRoot);
    }

    D3D12_GRAPHICS_PIPELINE_STATE_DESC description = {};
    description.pRootSignature = renderer->rootSignature;
    description.VS = {vertexShader->GetBufferPointer(), vertexShader->GetBufferSize()};
    description.PS = {pixelShader->GetBufferPointer(), pixelShader->GetBufferSize()};
    description.BlendState.AlphaToCoverageEnable = FALSE;
    description.BlendState.IndependentBlendEnable = FALSE;
    D3D12_RENDER_TARGET_BLEND_DESC& blend = description.BlendState.RenderTarget[0];
    blend.BlendEnable = FALSE;
    blend.LogicOpEnable = FALSE;
    blend.SrcBlend = D3D12_BLEND_SRC_ALPHA;
    blend.DestBlend = D3D12_BLEND_INV_SRC_ALPHA;
    blend.BlendOp = D3D12_BLEND_OP_ADD;
    blend.SrcBlendAlpha = D3D12_BLEND_ONE;
    blend.DestBlendAlpha = D3D12_BLEND_INV_SRC_ALPHA;
    blend.BlendOpAlpha = D3D12_BLEND_OP_ADD;
    blend.LogicOp = D3D12_LOGIC_OP_NOOP;
    blend.RenderTargetWriteMask = D3D12_COLOR_WRITE_ENABLE_ALL;
    description.SampleMask = UINT_MAX;
    description.RasterizerState.FillMode = D3D12_FILL_MODE_SOLID;
    description.RasterizerState.CullMode = D3D12_CULL_MODE_NONE;
    description.RasterizerState.DepthClipEnable = TRUE;
    description.DepthStencilState.DepthEnable = FALSE;
    description.DepthStencilState.StencilEnable = FALSE;
    description.PrimitiveTopologyType = D3D12_PRIMITIVE_TOPOLOGY_TYPE_TRIANGLE;
    description.NumRenderTargets = 1;
    description.RTVFormats[0] = DXGI_FORMAT_R8G8B8A8_UNORM;
    description.SampleDesc.Count = 1;
    result = renderer->device->CreateGraphicsPipelineState(&description,
            IID_PPV_ARGS(&renderer->pipelines[index]));
    release(pixelShader);
    if (FAILED(result)) {
        throwJava(env, "DirectX 12 failed to create a shader pass pipeline.");
        return false;
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
        throwJava(env, "DirectX 12 device creation failed.");
        return false;
    }
    D3D12_COMMAND_QUEUE_DESC queueDescription = {};
    queueDescription.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
    if (FAILED(renderer->device->CreateCommandQueue(&queueDescription, IID_PPV_ARGS(&renderer->queue)))) {
        throwJava(env, "DirectX 12 command queue creation failed.");
        return false;
    }

    IDXGIFactory4* factory = nullptr;
    if (FAILED(CreateDXGIFactory2(0, IID_PPV_ARGS(&factory)))) {
        throwJava(env, "DXGI factory creation failed.");
        return false;
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
    factory->MakeWindowAssociation(renderer->window, DXGI_MWA_NO_ALT_ENTER);
    release(factory);
    if (FAILED(result) || FAILED(swapChain->QueryInterface(IID_PPV_ARGS(&renderer->swapChain)))) {
        release(swapChain);
        throwJava(env, "DXGI failed to create a swap chain for the Minecraft window.");
        return false;
    }
    release(swapChain);

    D3D12_DESCRIPTOR_HEAP_DESC heapDescription = {};
    heapDescription.NumDescriptors = kFrameCount + renderer->passCount - 1;
    heapDescription.Type = D3D12_DESCRIPTOR_HEAP_TYPE_RTV;
    if (FAILED(renderer->device->CreateDescriptorHeap(&heapDescription, IID_PPV_ARGS(&renderer->rtvHeap)))) {
        throwJava(env, "DirectX 12 RTV descriptor heap creation failed.");
        return false;
    }
    renderer->rtvDescriptorSize = renderer->device->GetDescriptorHandleIncrementSize(
            D3D12_DESCRIPTOR_HEAP_TYPE_RTV);
    heapDescription.NumDescriptors = renderer->passCount;
    heapDescription.Type = D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV;
    heapDescription.Flags = D3D12_DESCRIPTOR_HEAP_FLAG_SHADER_VISIBLE;
    if (FAILED(renderer->device->CreateDescriptorHeap(&heapDescription, IID_PPV_ARGS(&renderer->srvHeap)))) {
        throwJava(env, "DirectX 12 shader resource descriptor heap creation failed.");
        return false;
    }
    renderer->srvDescriptorSize = renderer->device->GetDescriptorHandleIncrementSize(
            D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV);
    if (!createRenderTargets(renderer, env)) {
        return false;
    }
    if (!createSceneResources(renderer, env)) {
        return false;
    }

    ID3DBlob* vertexShader = nullptr;
    ID3DBlob* errors = nullptr;
    static const char vertexSource[] =
            "struct VertexOut { float4 position : SV_Position; };"
            "VertexOut main(uint id : SV_VertexID) {"
            " VertexOut o; float2 p = float2((id << 1) & 2, id & 2);"
            " o.position = float4(p * float2(2.0, -2.0) + float2(-1.0, 1.0), 0.0, 1.0);"
            " return o; }";
    result = D3DCompile(vertexSource, sizeof(vertexSource) - 1, nullptr, nullptr, nullptr,
            "main", "vs_5_0", D3DCOMPILE_ENABLE_STRICTNESS, 0, &vertexShader, &errors);
    if (FAILED(result)) {
        throwJava(env, "DirectX 12 fullscreen vertex shader compilation failed.");
        release(errors);
        return false;
    }
    release(errors);
    for (jsize index = 0; index < passCount; ++index) {
        jstring shader = static_cast<jstring>(env->GetObjectArrayElement(shaders, index));
        if (shader == nullptr) {
            release(vertexShader);
            throwJava(env, "Shader pack contains a null HLSL source.");
            return false;
        }
        const char* source = env->GetStringUTFChars(shader, nullptr);
        if (source == nullptr) {
            env->DeleteLocalRef(shader);
            release(vertexShader);
            return false;
        }
        const jsize sourceLength = env->GetStringUTFLength(shader);
        const bool succeeded = createPipeline(renderer, static_cast<UINT>(index), source,
                static_cast<SIZE_T>(sourceLength), vertexShader, env);
        env->ReleaseStringUTFChars(shader, source);
        env->DeleteLocalRef(shader);
        if (!succeeded) {
            release(vertexShader);
            return false;
        }
    }
    release(vertexShader);

    for (UINT index = 0; index < kFrameCount; ++index) {
        if (FAILED(renderer->device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT,
                IID_PPV_ARGS(&renderer->frames[index].allocator)))) {
            throwJava(env, "DirectX 12 command allocator creation failed.");
            return false;
        }
        for (UINT pass = 0; pass < renderer->passCount; ++pass) {
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
            if (FAILED(renderer->device->CreateCommittedResource(&heap, D3D12_HEAP_FLAG_NONE, &buffer,
                    D3D12_RESOURCE_STATE_GENERIC_READ, nullptr,
                    IID_PPV_ARGS(&renderer->frames[index].constants[pass])))) {
                throwJava(env, "DirectX 12 constant buffer creation failed.");
                return false;
            }
            D3D12_RANGE readRange = {0, 0};
            void* mapped = nullptr;
            if (FAILED(renderer->frames[index].constants[pass]->Map(0, &readRange, &mapped))) {
                throwJava(env, "DirectX 12 constant buffer mapping failed.");
                return false;
            }
            renderer->frames[index].mappedConstants[pass] = static_cast<float*>(mapped);
        }
    }
    if (FAILED(renderer->device->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT,
            renderer->frames[0].allocator, nullptr, IID_PPV_ARGS(&renderer->commandList)))
            || FAILED(renderer->commandList->Close())) {
        throwJava(env, "DirectX 12 command list creation failed.");
        return false;
    }
    if (FAILED(renderer->device->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&renderer->fence)))) {
        throwJava(env, "DirectX 12 frame fence creation failed.");
        return false;
    }
    renderer->fenceEvent = CreateEvent(nullptr, FALSE, FALSE, nullptr);
    if (renderer->fenceEvent == nullptr) {
        throwJava(env, "DirectX 12 frame fence event creation failed.");
        return false;
    }
    return true;
}

bool resizeRenderer(Renderer* renderer, UINT width, UINT height, JNIEnv* env) {
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
    if (FAILED(renderer->swapChain->ResizeBuffers(kFrameCount, width, height,
            DXGI_FORMAT_R8G8B8A8_UNORM, 0))) {
        throwJava(env, "DXGI failed to resize the Minecraft swap chain.");
        return false;
    }
    renderer->width = width;
    renderer->height = height;
    return createRenderTargets(renderer, env) && createSceneResources(renderer, env);
}

bool renderFrame(Renderer* renderer, jfloat timeSeconds, jint width, jint height,
        jobjectArray uniforms, jobject rgba, JNIEnv* env) {
    if (uniforms == nullptr || env->GetArrayLength(uniforms) != static_cast<jsize>(renderer->passCount)) {
        throwJava(env, "Shader uniform data does not match the compiled pass count.");
        return false;
    }
    const jlong sourceCapacity = env->GetDirectBufferCapacity(rgba);
    const jlong requiredCapacity = static_cast<jlong>(width) * height * 4;
    const BYTE* sourcePixels = static_cast<const BYTE*>(env->GetDirectBufferAddress(rgba));
    if (sourcePixels == nullptr || sourceCapacity < requiredCapacity || width <= 0 || height <= 0) {
        throwJava(env, "The OpenGL scene capture must be a direct RGBA buffer matching the framebuffer size.");
        return false;
    }
    if (!resizeRenderer(renderer, static_cast<UINT>(width), static_cast<UINT>(height), env)) {
        return false;
    }
    const UINT frameIndex = renderer->swapChain->GetCurrentBackBufferIndex();
    FrameResources& frame = renderer->frames[frameIndex];
    const UINT64 completedFenceValue = renderer->fence->GetCompletedValue();
    if (completedFenceValue == std::numeric_limits<UINT64>::max()) {
        throwJava(env, "DirectX 12 device was removed while rendering a frame.");
        return false;
    }
    if (frame.fenceValue != 0 && completedFenceValue < frame.fenceValue) {
        if (FAILED(renderer->fence->SetEventOnCompletion(frame.fenceValue, renderer->fenceEvent))) {
            throwJava(env, "DirectX 12 failed to wait for the back buffer.");
            return false;
        }
        if (WaitForSingleObject(renderer->fenceEvent, INFINITE) != WAIT_OBJECT_0) {
            throwJava(env, "DirectX 12 failed while waiting for the back buffer.");
            return false;
        }
    }
    if (FAILED(frame.allocator->Reset()) || FAILED(renderer->commandList->Reset(frame.allocator, nullptr))) {
        throwJava(env, "DirectX 12 command list reset failed.");
        return false;
    }

    for (UINT row = 0; row < renderer->height; ++row) {
        CopyMemory(frame.mappedSceneUpload
                + renderer->sceneFootprint.Offset
                + static_cast<SIZE_T>(row) * renderer->sceneFootprint.Footprint.RowPitch,
                sourcePixels + static_cast<SIZE_T>(row) * renderer->width * 4,
                static_cast<SIZE_T>(renderer->width) * 4);
    }

    D3D12_RESOURCE_BARRIER barrier = {};
    barrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
    barrier.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;
    barrier.Transition.pResource = renderer->sceneTexture;
    barrier.Transition.StateBefore = D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE;
    barrier.Transition.StateAfter = D3D12_RESOURCE_STATE_COPY_DEST;
    renderer->commandList->ResourceBarrier(1, &barrier);
    D3D12_TEXTURE_COPY_LOCATION destination = {};
    destination.pResource = renderer->sceneTexture;
    destination.Type = D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX;
    destination.SubresourceIndex = 0;
    D3D12_TEXTURE_COPY_LOCATION source = {};
    source.pResource = frame.sceneUpload;
    source.Type = D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;
    source.PlacedFootprint = renderer->sceneFootprint;
    renderer->commandList->CopyTextureRegion(&destination, 0, 0, 0, &source, nullptr);
    barrier.Transition.StateBefore = D3D12_RESOURCE_STATE_COPY_DEST;
    barrier.Transition.StateAfter = D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE;
    renderer->commandList->ResourceBarrier(1, &barrier);

    ID3D12DescriptorHeap* descriptorHeaps[] = {renderer->srvHeap};
    renderer->commandList->SetDescriptorHeaps(1, descriptorHeaps);
    D3D12_GPU_DESCRIPTOR_HANDLE sceneDescriptor = renderer->srvHeap->GetGPUDescriptorHandleForHeapStart();
    const float clearColor[4] = {0.0f, 0.0f, 0.0f, 1.0f};
    D3D12_VIEWPORT viewport = {0.0f, 0.0f, static_cast<float>(renderer->width),
            static_cast<float>(renderer->height), 0.0f, 1.0f};
    D3D12_RECT scissor = {0, 0, static_cast<LONG>(renderer->width), static_cast<LONG>(renderer->height)};
    renderer->commandList->RSSetViewports(1, &viewport);
    renderer->commandList->RSSetScissorRects(1, &scissor);
    renderer->commandList->IASetPrimitiveTopology(D3D_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    for (UINT pass = 0; pass < renderer->passCount; ++pass) {
        jfloatArray values = static_cast<jfloatArray>(env->GetObjectArrayElement(uniforms, pass));
        if (values == nullptr || env->GetArrayLength(values) != kUniformFloatCount) {
            if (values != nullptr) {
                env->DeleteLocalRef(values);
            }
            throwJava(env, "Each shader pass must provide exactly 16 vec4 uniforms.");
            return false;
        }
        jfloat data[kUniformFloatCount];
        env->GetFloatArrayRegion(values, 0, kUniformFloatCount, data);
        env->DeleteLocalRef(values);
        if (env->ExceptionCheck()) {
            return false;
        }
        float* constants = frame.mappedConstants[pass];
        constants[0] = timeSeconds;
        constants[1] = static_cast<float>(renderer->width);
        constants[2] = static_cast<float>(renderer->height);
        constants[3] = static_cast<float>(renderer->frameNumber);
        CopyMemory(constants + 4, data, sizeof(data));
        ID3D12Resource* output = pass + 1 == renderer->passCount
                ? renderer->backBuffers[frameIndex]
                : renderer->intermediateTargets[pass];
        D3D12_CPU_DESCRIPTOR_HANDLE outputRtv = pass + 1 == renderer->passCount
                ? renderer->renderTargets[frameIndex]
                : renderer->intermediateRenderTargets[pass];
        barrier.Transition.pResource = output;
        barrier.Transition.StateBefore = pass + 1 == renderer->passCount
                ? D3D12_RESOURCE_STATE_PRESENT
                : D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE;
        barrier.Transition.StateAfter = D3D12_RESOURCE_STATE_RENDER_TARGET;
        renderer->commandList->ResourceBarrier(1, &barrier);
        renderer->commandList->ClearRenderTargetView(outputRtv, clearColor, 0, nullptr);
        renderer->commandList->OMSetRenderTargets(1, &outputRtv, FALSE, nullptr);
        renderer->commandList->SetGraphicsRootSignature(renderer->rootSignature);
        renderer->commandList->SetPipelineState(renderer->pipelines[pass]);
        renderer->commandList->SetGraphicsRootConstantBufferView(0,
                frame.constants[pass]->GetGPUVirtualAddress());
        D3D12_GPU_DESCRIPTOR_HANDLE inputDescriptor = sceneDescriptor;
        inputDescriptor.ptr += static_cast<UINT64>(pass) * renderer->srvDescriptorSize;
        renderer->commandList->SetGraphicsRootDescriptorTable(1, inputDescriptor);
        renderer->commandList->DrawInstanced(3, 1, 0, 0);
        barrier.Transition.StateBefore = D3D12_RESOURCE_STATE_RENDER_TARGET;
        barrier.Transition.StateAfter = pass + 1 == renderer->passCount
                ? D3D12_RESOURCE_STATE_PRESENT
                : D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE;
        renderer->commandList->ResourceBarrier(1, &barrier);
    }
    if (FAILED(renderer->commandList->Close())) {
        throwJava(env, "DirectX 12 command list close failed.");
        return false;
    }
    ID3D12CommandList* commandLists[] = {renderer->commandList};
    renderer->queue->ExecuteCommandLists(1, commandLists);
    if (FAILED(renderer->swapChain->Present(1, 0))) {
        throwJava(env, "DXGI failed to present the DirectX 12 frame.");
        return false;
    }
    const UINT64 value = renderer->nextFenceValue++;
    if (FAILED(renderer->queue->Signal(renderer->fence, value))) {
        throwJava(env, "DirectX 12 failed to signal completion of the rendered frame.");
        return false;
    }
    frame.fenceValue = value;
    renderer->frameNumber++;
    return true;
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_initialize(JNIEnv* env, jclass, jlong hwnd,
        jint width, jint height, jobjectArray shaders) {
    if (g_renderer != nullptr) {
        destroyRenderer(g_renderer);
        g_renderer = nullptr;
    }
    Renderer* renderer = new Renderer{};
    if (!initializeRenderer(renderer, hwnd, width, height, shaders, env)) {
        destroyRenderer(renderer);
        return;
    }
    g_renderer = renderer;
}

extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_resize(JNIEnv* env, jclass, jint width, jint height) {
    if (g_renderer == nullptr || !resizeRenderer(g_renderer,
            static_cast<UINT>(width), static_cast<UINT>(height), env)) {
        if (g_renderer == nullptr) {
            throwJava(env, "DirectX 12 renderer is not initialized.");
        }
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
