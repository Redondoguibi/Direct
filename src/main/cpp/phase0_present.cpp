// Isolated feasibility test. The hybrid renderer never instantiates this swap chain.
#include <windows.h>
#include <dwmapi.h>
#include <d3d12.h>
#include <dxgi1_6.h>
#include <wrl/client.h>
#include <jni.h>
#include <algorithm>
#include <array>
#include <cstdio>
#include <memory>
#include <stdexcept>

namespace {
using Microsoft::WRL::ComPtr;
constexpr UINT count = 2;

void check(HRESULT result, const char* operation) {
    if (result == S_OK) return;
    char message[192];
    std::snprintf(message, sizeof(message), "%s returned 0x%08lx", operation, static_cast<unsigned long>(result));
    throw std::runtime_error(message);
}

class Probe {
    HWND window;
    ComPtr<ID3D12Device> device;
    ComPtr<ID3D12CommandQueue> queue;
    ComPtr<IDXGISwapChain3> chain;
    ComPtr<ID3D12DescriptorHeap> heap;
    std::array<ComPtr<ID3D12Resource>, count> buffers;
    ComPtr<ID3D12CommandAllocator> allocator;
    ComPtr<ID3D12GraphicsCommandList> list;
    ComPtr<ID3D12Fence> fence;
    HANDLE event = nullptr;
    UINT64 serial = 0;
    UINT width = 0, height = 0, flags = 0;

    void drain() {
        check(queue->Signal(fence.Get(), ++serial), "Signal");
        check(fence->SetEventOnCompletion(serial, event), "SetEventOnCompletion");
        if (WaitForSingleObject(event, 5000) != WAIT_OBJECT_0) throw std::runtime_error("DXGI probe fence timed out");
        check(device->GetDeviceRemovedReason(), "GetDeviceRemovedReason");
    }

    void resize(UINT w, UINT h) {
        if (w == width && h == height) return;
        drain();
        for (auto& buffer : buffers) buffer.Reset();
        check(chain->ResizeBuffers(count, w, h, DXGI_FORMAT_R8G8B8A8_UNORM, flags), "ResizeBuffers");
        auto rtv = heap->GetCPUDescriptorHandleForHeapStart();
        for (UINT i = 0; i < count; ++i) {
            check(chain->GetBuffer(i, IID_PPV_ARGS(&buffers[i])), "GetBuffer");
            const auto desc = buffers[i]->GetDesc();
            if (desc.Width != w || desc.Height != h) throw std::runtime_error("Back buffer size mismatch");
            device->CreateRenderTargetView(buffers[i].Get(), nullptr, rtv);
            rtv.ptr += device->GetDescriptorHandleIncrementSize(D3D12_DESCRIPTOR_HEAP_TYPE_RTV);
        }
        width = w;
        height = h;
    }

public:
    explicit Probe(HWND hwnd) : window(hwnd) {
        ComPtr<IDXGIFactory5> factory;
        check(CreateDXGIFactory2(0, IID_PPV_ARGS(&factory)), "CreateDXGIFactory2");
        BOOL tearing = FALSE;
        if (SUCCEEDED(factory->CheckFeatureSupport(DXGI_FEATURE_PRESENT_ALLOW_TEARING, &tearing, sizeof(tearing))) && tearing)
            flags = DXGI_SWAP_CHAIN_FLAG_ALLOW_TEARING;
        check(D3D12CreateDevice(nullptr, D3D_FEATURE_LEVEL_11_0, IID_PPV_ARGS(&device)), "D3D12CreateDevice");
        D3D12_COMMAND_QUEUE_DESC queueDesc{};
        queueDesc.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
        check(device->CreateCommandQueue(&queueDesc, IID_PPV_ARGS(&queue)), "CreateCommandQueue");
        DXGI_SWAP_CHAIN_DESC1 desc{};
        desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
        desc.SampleDesc.Count = 1;
        desc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
        desc.BufferCount = count;
        desc.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;
        desc.Flags = flags;
        ComPtr<IDXGISwapChain1> initial;
        check(factory->CreateSwapChainForHwnd(queue.Get(), hwnd, &desc, nullptr, nullptr, &initial), "CreateSwapChainForHwnd");
        check(factory->MakeWindowAssociation(hwnd, DXGI_MWA_NO_ALT_ENTER), "MakeWindowAssociation");
        check(initial.As(&chain), "IDXGISwapChain3");
        D3D12_DESCRIPTOR_HEAP_DESC heapDesc{};
        heapDesc.Type = D3D12_DESCRIPTOR_HEAP_TYPE_RTV;
        heapDesc.NumDescriptors = count;
        check(device->CreateDescriptorHeap(&heapDesc, IID_PPV_ARGS(&heap)), "CreateDescriptorHeap");
        check(device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT, IID_PPV_ARGS(&allocator)), "CreateCommandAllocator");
        check(device->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT, allocator.Get(), nullptr, IID_PPV_ARGS(&list)), "CreateCommandList");
        check(list->Close(), "Close");
        check(device->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&fence)), "CreateFence");
        event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        if (!event) throw std::runtime_error("CreateEvent failed");
    }
    ~Probe() {
        if (event) {
            try { drain(); } catch (...) { /* Device loss or failed fence already reported to the caller. */ }
            CloseHandle(event);
        }
    }

    bool frame(int w, int h, int phase, bool vsync, bool sample) {
        RECT client{};
        if (!GetClientRect(window, &client) || w <= 0 || h <= 0 || w != client.right || h != client.bottom)
            throw std::runtime_error("GLFW framebuffer size differs from HWND client area");
        resize(w, h);
        UINT index = chain->GetCurrentBackBufferIndex();
        auto rtv = heap->GetCPUDescriptorHandleForHeapStart();
        rtv.ptr += index * device->GetDescriptorHandleIncrementSize(D3D12_DESCRIPTOR_HEAP_TYPE_RTV);
        check(allocator->Reset(), "Allocator reset");
        check(list->Reset(allocator.Get(), nullptr), "Command list reset");
        D3D12_RESOURCE_BARRIER barrier{};
        barrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
        barrier.Transition = {buffers[index].Get(), D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES,
                              D3D12_RESOURCE_STATE_PRESENT, D3D12_RESOURCE_STATE_RENDER_TARGET};
        list->ResourceBarrier(1, &barrier);
        constexpr float colors[][4] = {{1,0,0,1}, {0,1,0,1}, {0,0,1,1}};
        const int left = phase % 3, right = (phase + 1) % 3;
        list->ClearRenderTargetView(rtv, colors[left], 0, nullptr);
        const D3D12_RECT half{w/2, 0, w, h};
        list->ClearRenderTargetView(rtv, colors[right], 1, &half);
        std::swap(barrier.Transition.StateBefore, barrier.Transition.StateAfter);
        list->ResourceBarrier(1, &barrier);
        check(list->Close(), "Close");
        ID3D12CommandList* work[] = {list.Get()};
        queue->ExecuteCommandLists(1, work);
        check(chain->Present(vsync ? 1 : 0, !vsync && flags ? DXGI_PRESENT_ALLOW_TEARING : 0), "Present");
        drain();
        if (!sample) return true;

        // Read the displayed desktop, not the back buffer. A competing/occluding window fails this check.
        check(DwmFlush(), "DwmFlush");
        POINT origin{};
        if (!ClientToScreen(window, &origin)) throw std::runtime_error("ClientToScreen failed");
        HDC desktop = GetDC(nullptr);
        if (!desktop) throw std::runtime_error("No desktop DC");
        const auto a = GetPixel(desktop, origin.x + w/4, origin.y + h/2);
        const auto b = GetPixel(desktop, origin.x + 3*w/4, origin.y + h/2);
        ReleaseDC(nullptr, desktop);
        constexpr COLORREF expected[] = {RGB(255,0,0), RGB(0,255,0), RGB(0,0,255)};
        return a == expected[left] && b == expected[right];
    }
};

void fail(JNIEnv* env, const std::exception& error) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_redondoguibi_direct_PhaseZeroPresentation_open(JNIEnv* env, jclass, jlong hwnd) {
    try { return reinterpret_cast<jlong>(new Probe(reinterpret_cast<HWND>(hwnd))); }
    catch (const std::exception& ex) { fail(env, ex); return 0; }
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_redondoguibi_direct_PhaseZeroPresentation_frame(
        JNIEnv* env, jclass, jlong probe, jint w, jint h, jint phase, jboolean vsync, jboolean sample) {
    try { return reinterpret_cast<Probe*>(probe)->frame(w, h, phase, vsync, sample); }
    catch (const std::exception& ex) { fail(env, ex); return JNI_FALSE; }
}

extern "C" JNIEXPORT void JNICALL Java_com_redondoguibi_direct_PhaseZeroPresentation_close(JNIEnv*, jclass, jlong probe) {
    delete reinterpret_cast<Probe*>(probe);
}
