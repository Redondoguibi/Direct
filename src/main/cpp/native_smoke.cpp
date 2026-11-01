#include <windows.h>
#include <d3d12.h>
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
    event = CreateEvent(nullptr, FALSE, FALSE, nullptr);
    if (event == nullptr || FAILED(queue->Signal(fence, 1))
            || FAILED(fence->SetEventOnCompletion(1, event))
            || WaitForSingleObject(event, 10000) != WAIT_OBJECT_0
            || fence->GetCompletedValue() < 1) {
        std::fprintf(stderr, "D3D12 queue/fence smoke synchronization failed.\n");
        goto cleanup;
    }
    std::puts("D3D12 WARP device, direct command list, queue, and fence passed.");
    result = 0;

cleanup:
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
