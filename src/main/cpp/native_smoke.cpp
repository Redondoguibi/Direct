#include <windows.h>
#include <d3d12.h>
#include <d3dcompiler.h>
#include <dxgi1_4.h>
#include <cstdio>

int main() {
    IDXGIFactory4* factory = nullptr;
    IDXGIAdapter* adapter = nullptr;
    ID3D12Device* device = nullptr;
    ID3D12CommandQueue* queue = nullptr;
    ID3D12CommandAllocator* allocator = nullptr;
    ID3D12GraphicsCommandList* commandList = nullptr;
    ID3D12Fence* fence = nullptr;
    ID3D12RootSignature* rootSignature = nullptr;
    ID3D12CommandSignature* indirectSignature = nullptr;
    ID3DBlob* serializedRootSignature = nullptr;
    ID3DBlob* serializationErrors = nullptr;
    HANDLE event = nullptr;
    int result = 1;

    if (FAILED(CreateDXGIFactory2(0, IID_PPV_ARGS(&factory)))
            || FAILED(factory->EnumWarpAdapter(IID_PPV_ARGS(&adapter)))
            || FAILED(D3D12CreateDevice(adapter, D3D_FEATURE_LEVEL_11_0, IID_PPV_ARGS(&device)))) {
        std::fprintf(stderr, "D3D12 WARP device creation failed.\n");
        goto cleanup;
    }
    {
        D3D12_COMMAND_QUEUE_DESC queueDescription = {};
        queueDescription.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
        if (FAILED(device->CreateCommandQueue(&queueDescription, IID_PPV_ARGS(&queue)))
                || FAILED(device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT,
                        IID_PPV_ARGS(&allocator)))
                || FAILED(device->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT, allocator, nullptr,
                        IID_PPV_ARGS(&commandList)))
                || FAILED(commandList->Close())
                || FAILED(device->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&fence)))) {
            std::fprintf(stderr, "D3D12 queue, command list, or fence creation failed.\n");
            goto cleanup;
        }
    }
    {
        D3D12_DESCRIPTOR_RANGE atlasRange = {};
        atlasRange.RangeType = D3D12_DESCRIPTOR_RANGE_TYPE_SRV;
        atlasRange.NumDescriptors = 64;
        atlasRange.BaseShaderRegister = 0;
        atlasRange.OffsetInDescriptorsFromTableStart = 0;

        D3D12_ROOT_PARAMETER parameters[3] = {};
        parameters[0].ParameterType = D3D12_ROOT_PARAMETER_TYPE_32BIT_CONSTANTS;
        parameters[0].Constants.ShaderRegister = 0;
        parameters[0].Constants.Num32BitValues = 16;
        parameters[0].ShaderVisibility = D3D12_SHADER_VISIBILITY_VERTEX;
        parameters[1].ParameterType = D3D12_ROOT_PARAMETER_TYPE_32BIT_CONSTANTS;
        parameters[1].Constants.ShaderRegister = 1;
        parameters[1].Constants.Num32BitValues = 5;
        parameters[1].ShaderVisibility = D3D12_SHADER_VISIBILITY_ALL;
        parameters[2].ParameterType = D3D12_ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE;
        parameters[2].DescriptorTable.NumDescriptorRanges = 1;
        parameters[2].DescriptorTable.pDescriptorRanges = &atlasRange;

        D3D12_ROOT_SIGNATURE_DESC rootDescription = {};
        rootDescription.NumParameters = ARRAYSIZE(parameters);
        rootDescription.pParameters = parameters;
        rootDescription.Flags = D3D12_ROOT_SIGNATURE_FLAG_ALLOW_INPUT_ASSEMBLER_INPUT_LAYOUT;
        if (FAILED(D3D12SerializeRootSignature(&rootDescription, D3D_ROOT_SIGNATURE_VERSION_1,
                    &serializedRootSignature, &serializationErrors))
                || FAILED(device->CreateRootSignature(0, serializedRootSignature->GetBufferPointer(),
                    serializedRootSignature->GetBufferSize(), IID_PPV_ARGS(&rootSignature)))) {
            std::fprintf(stderr, "D3D12 terrain root signature creation failed.\\n");
            goto cleanup;
        }

        D3D12_INDIRECT_ARGUMENT_DESC arguments[4] = {};
        arguments[0].Type = D3D12_INDIRECT_ARGUMENT_TYPE_VERTEX_BUFFER_VIEW;
        arguments[0].VertexBuffer.Slot = 0;
        arguments[1].Type = D3D12_INDIRECT_ARGUMENT_TYPE_INDEX_BUFFER_VIEW;
        arguments[2].Type = D3D12_INDIRECT_ARGUMENT_TYPE_CONSTANT;
        arguments[2].Constant.RootParameterIndex = 1;
        arguments[2].Constant.DestOffsetIn32BitValues = 0;
        arguments[2].Constant.Num32BitValuesToSet = 5;
        arguments[3].Type = D3D12_INDIRECT_ARGUMENT_TYPE_DRAW_INDEXED;
        D3D12_COMMAND_SIGNATURE_DESC commandDescription = {};
        commandDescription.ByteStride = 72;
        commandDescription.NumArgumentDescs = ARRAYSIZE(arguments);
        commandDescription.pArgumentDescs = arguments;
        if (FAILED(device->CreateCommandSignature(&commandDescription, rootSignature,
                IID_PPV_ARGS(&indirectSignature)))) {
            std::fprintf(stderr, "D3D12 ExecuteIndirect command signature creation failed.\\n");
            goto cleanup;
        }
    }
    event = CreateEvent(nullptr, FALSE, FALSE, nullptr);
    if (event == nullptr || FAILED(queue->Signal(fence, 1))
            || FAILED(fence->SetEventOnCompletion(1, event))
            || WaitForSingleObject(event, 10000) != WAIT_OBJECT_0
            || fence->GetCompletedValue() < 1) {
        std::fprintf(stderr, "D3D12 queue/fence smoke synchronization failed.\n");
        goto cleanup;
    }
    std::puts("D3D12 WARP device, root signature, ExecuteIndirect signature, command list, queue, and fence passed.");
    result = 0;

cleanup:
    if (serializationErrors != nullptr) serializationErrors->Release();
    if (serializedRootSignature != nullptr) serializedRootSignature->Release();
    if (indirectSignature != nullptr) indirectSignature->Release();
    if (rootSignature != nullptr) rootSignature->Release();
    if (event != nullptr) {
        CloseHandle(event);
    }
    if (fence != nullptr) fence->Release();
    if (commandList != nullptr) commandList->Release();
    if (allocator != nullptr) allocator->Release();
    if (queue != nullptr) queue->Release();
    if (device != nullptr) device->Release();
    if (adapter != nullptr) adapter->Release();
    if (factory != nullptr) factory->Release();
    return result;
}
