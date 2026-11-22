// Phase 1 DX12 backend (shadow mode): replays Minecraft's blaze3d draws on a private D3D12 device, in the
// order the game issues them, so its render targets can be compared with OpenGL's pixel by pixel. Textures,
// framebuffers and programs are keyed by their OpenGL names. The hybrid renderer never calls into this file.
//
// Conventions shared with docs/bench/shaders/translate-runtime.py: vertex shaders flip Y, so every D3D12 image keeps
// OpenGL's bottom-up row order (row 0 = GL y 0) and GL viewport/scissor rectangles map to D3D12 unchanged; GL's
// counter-clockwise front faces keep D3D's default clockwise front. cbuffer b0, textures t0..t11 and samplers s0..s11, all in register space 0.
#include <windows.h>
#include <d3d12.h>
#include <d3dcompiler.h>
#include <dxgi1_6.h>
#include <dwmapi.h>
#include <wrl/client.h>
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
using Microsoft::WRL::ComPtr;

constexpr UINT kMaxTextures = 12;
constexpr UINT64 kRingBytes = 64ull << 20;
constexpr UINT kViewRing = 65536;
constexpr UINT kSamplerRing = 2048;
constexpr UINT kCpuViews = 32768;

void check(HRESULT result, const char* what) {
    if (SUCCEEDED(result)) return;
    char message[160];
    std::snprintf(message, sizeof(message), "%s failed (0x%08lx)", what, static_cast<unsigned long>(result));
    throw std::runtime_error(message);
}

UINT64 alignUp(UINT64 value, UINT64 alignment) { return (value + alignment - 1) / alignment * alignment; }

struct Texture {
    ComPtr<ID3D12Resource> resource;
    D3D12_RESOURCE_STATES state = D3D12_RESOURCE_STATE_COMMON;
    DXGI_FORMAT format = DXGI_FORMAT_UNKNOWN;
    int glInternal = 0;
    UINT width = 0, height = 0, levels = 0;
    bool depth = false;
    int cpuView = -1, rtv = -1, dsv = -1;
    // OpenGL defaults: NEAREST_MIPMAP_LINEAR / LINEAR / REPEAT, max level 1000.
    int minFilter = 9986, magFilter = 9729, wrapS = 10497, wrapT = 10497, maxLevel = 1000;
    float lodBias = 0, minLod = -1000, maxLod = 1000;
};

struct Program {
    ComPtr<ID3DBlob> vs, ps;
    UINT cbufferBytes = 0, textures = 0;
};

struct PsoKey {
    int program;
    uint64_t layout;
    int blend[7];  // enable, srcRgb, dstRgb, srcA, dstA, eqRgb, eqA
    int depth[3];  // test, func, mask
    int cull, colorMask, topologyType, colorFormat, depthFormat;
    int bias, slope;  // polygon offset (raw float bits)
    bool operator==(const PsoKey& o) const { return std::memcmp(this, &o, sizeof(*this)) == 0; }
};
struct PsoHash {
    size_t operator()(const PsoKey& key) const {
        const auto* bytes = reinterpret_cast<const unsigned char*>(&key);
        uint64_t hash = 1469598103934665603ull;
        for (size_t i = 0; i < sizeof(key); ++i) hash = (hash ^ bytes[i]) * 1099511628211ull;
        return static_cast<size_t>(hash);
    }
};

DXGI_FORMAT textureFormat(int internal, bool* depth) {
    *depth = false;
    switch (internal) {
        case 6403: case 33321: return DXGI_FORMAT_R8_UNORM;          // RED, R8
        case 33319: case 33323: return DXGI_FORMAT_R8G8_UNORM;       // RG, RG8
        case 6402: case 33189: case 33190: case 33191: case 36012:   // DEPTH_COMPONENT(16/24/32/32F)
            *depth = true; return DXGI_FORMAT_R32_TYPELESS;
        case 36013: case 35056: case 34041:                          // DEPTH32F_STENCIL8, DEPTH24_STENCIL8, DEPTH_STENCIL
            *depth = true; return DXGI_FORMAT_R32G8X24_TYPELESS;
        default: return DXGI_FORMAT_R8G8B8A8_UNORM;                  // RGBA, RGBA8, RGB, unknown
    }
}
DXGI_FORMAT viewFormat(DXGI_FORMAT format) {
    if (format == DXGI_FORMAT_R32_TYPELESS) return DXGI_FORMAT_R32_FLOAT;
    if (format == DXGI_FORMAT_R32G8X24_TYPELESS) return DXGI_FORMAT_R32_FLOAT_X8X24_TYPELESS;
    return format;
}
DXGI_FORMAT depthViewFormat(DXGI_FORMAT format) {
    return format == DXGI_FORMAT_R32G8X24_TYPELESS ? DXGI_FORMAT_D32_FLOAT_S8X24_UINT : DXGI_FORMAT_D32_FLOAT;
}
UINT formatBytes(DXGI_FORMAT format) {
    switch (format) {
        case DXGI_FORMAT_R8_UNORM: return 1;
        case DXGI_FORMAT_R8G8_UNORM: return 2;
        default: return 4;
    }
}
D3D12_BLEND blendFactor(int gl, bool alpha) {
    switch (gl) {
        case 0: return D3D12_BLEND_ZERO;
        case 1: return D3D12_BLEND_ONE;
        case 768: return alpha ? D3D12_BLEND_SRC_ALPHA : D3D12_BLEND_SRC_COLOR;
        case 769: return alpha ? D3D12_BLEND_INV_SRC_ALPHA : D3D12_BLEND_INV_SRC_COLOR;
        case 770: return D3D12_BLEND_SRC_ALPHA;
        case 771: return D3D12_BLEND_INV_SRC_ALPHA;
        case 772: return D3D12_BLEND_DEST_ALPHA;
        case 773: return D3D12_BLEND_INV_DEST_ALPHA;
        case 774: return alpha ? D3D12_BLEND_DEST_ALPHA : D3D12_BLEND_DEST_COLOR;
        case 775: return alpha ? D3D12_BLEND_INV_DEST_ALPHA : D3D12_BLEND_INV_DEST_COLOR;
        case 776: return D3D12_BLEND_SRC_ALPHA_SAT;
        case 32769: case 32771: return D3D12_BLEND_BLEND_FACTOR;
        case 32770: case 32772: return D3D12_BLEND_INV_BLEND_FACTOR;
        default: return D3D12_BLEND_ONE;
    }
}
D3D12_BLEND_OP blendOp(int gl) {
    switch (gl) {
        case 32778: return D3D12_BLEND_OP_SUBTRACT;
        case 32779: return D3D12_BLEND_OP_REV_SUBTRACT;
        case 32775: return D3D12_BLEND_OP_MIN;
        case 32776: return D3D12_BLEND_OP_MAX;
        default: return D3D12_BLEND_OP_ADD;
    }
}
D3D12_COMPARISON_FUNC compare(int gl) {
    switch (gl) {
        case 512: return D3D12_COMPARISON_FUNC_NEVER;
        case 513: return D3D12_COMPARISON_FUNC_LESS;
        case 514: return D3D12_COMPARISON_FUNC_EQUAL;
        case 516: return D3D12_COMPARISON_FUNC_GREATER;
        case 517: return D3D12_COMPARISON_FUNC_NOT_EQUAL;
        case 518: return D3D12_COMPARISON_FUNC_GREATER_EQUAL;
        case 519: return D3D12_COMPARISON_FUNC_ALWAYS;
        default: return D3D12_COMPARISON_FUNC_LESS_EQUAL;
    }
}
D3D12_TEXTURE_ADDRESS_MODE address(int gl) {
    switch (gl) {
        case 33071: return D3D12_TEXTURE_ADDRESS_MODE_CLAMP;
        case 33648: return D3D12_TEXTURE_ADDRESS_MODE_MIRROR;
        case 33069: return D3D12_TEXTURE_ADDRESS_MODE_BORDER;
        default: return D3D12_TEXTURE_ADDRESS_MODE_WRAP;
    }
}

class Backend {
public:
    explicit Backend(bool debug) {
        if (debug) {
            ComPtr<ID3D12Debug> layer;
            if (SUCCEEDED(D3D12GetDebugInterface(IID_PPV_ARGS(&layer)))) layer->EnableDebugLayer();
        }
        check(D3D12CreateDevice(nullptr, D3D_FEATURE_LEVEL_11_0, IID_PPV_ARGS(&device)), "D3D12CreateDevice");
        if (debug) device.As(&info);
        D3D12_COMMAND_QUEUE_DESC queueDesc{};
        queueDesc.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
        check(device->CreateCommandQueue(&queueDesc, IID_PPV_ARGS(&queue)), "CreateCommandQueue");
        check(device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT, IID_PPV_ARGS(&allocator)), "CreateCommandAllocator");
        check(device->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT, allocator.Get(), nullptr, IID_PPV_ARGS(&list)), "CreateCommandList");
        check(device->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&fence)), "CreateFence");
        event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
        heap(D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV, kViewRing, true, &views);
        heap(D3D12_DESCRIPTOR_HEAP_TYPE_SAMPLER, kSamplerRing, true, &samplers);
        heap(D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV, kCpuViews, false, &cpuViews);
        heap(D3D12_DESCRIPTOR_HEAP_TYPE_RTV, 4096, false, &rtvs);
        heap(D3D12_DESCRIPTOR_HEAP_TYPE_DSV, 1024, false, &dsvs);
        viewSize = device->GetDescriptorHandleIncrementSize(D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV);
        samplerSize = device->GetDescriptorHandleIncrementSize(D3D12_DESCRIPTOR_HEAP_TYPE_SAMPLER);
        rtvSize = device->GetDescriptorHandleIncrementSize(D3D12_DESCRIPTOR_HEAP_TYPE_RTV);
        dsvSize = device->GetDescriptorHandleIncrementSize(D3D12_DESCRIPTOR_HEAP_TYPE_DSV);
        // Slot 0 of the CPU view heap is a null SRV for samplers without a texture.
        nullView = allocateCpuView();
        D3D12_SHADER_RESOURCE_VIEW_DESC null{};
        null.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
        null.ViewDimension = D3D12_SRV_DIMENSION_TEXTURE2D;
        null.Shader4ComponentMapping = D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING;
        null.Texture2D.MipLevels = 1;
        device->CreateShaderResourceView(nullptr, &null, cpuView(nullView));
        createRootSignature();
        D3D12_HEAP_PROPERTIES upload{D3D12_HEAP_TYPE_UPLOAD};
        auto desc = bufferDesc(kRingBytes);
        check(device->CreateCommittedResource(&upload, D3D12_HEAP_FLAG_NONE, &desc, D3D12_RESOURCE_STATE_GENERIC_READ,
                nullptr, IID_PPV_ARGS(&ring)), "upload ring");
        check(ring->Map(0, nullptr, reinterpret_cast<void**>(&ringData)), "Map upload ring");
        // GL's default for an attribute without an array: (0, 0, 0, 1).
        const float defaults[4] = {0, 0, 0, 1};
        defaultAttribute = allocate(16, 16);
        std::memcpy(ringData + defaultAttribute, defaults, 16);
        persistentRing = ringOffset;
    }
    ~Backend() {
        try { flush(); } catch (...) {}
        if (event) CloseHandle(event);
    }

    // ---- Textures -------------------------------------------------------------------------------------------
    void textureImage(int id, int level, int internal, UINT width, UINT height) {
        auto& texture = textures[id];
        bool depth = false;
        DXGI_FORMAT format = textureFormat(internal, &depth);
        if (level == 0) {
            // GL_TEXTURE_MAX_LEVEL is set before the levels are specified (TextureUtil.prepareImage).
            UINT full = 1;
            for (UINT size = (std::max)(width, height); size > 1; size >>= 1) ++full;
            UINT wanted = (std::min)(static_cast<UINT>((std::min)((std::max)(texture.maxLevel, 0), 15)) + 1, full);
            if (texture.resource && texture.format == format && texture.width == width && texture.height == height
                    && texture.levels == wanted) return;
            create(texture, format, depth, internal, width, height, wanted);
            return;
        }
        // A later level only needs storage; its contents arrive with glTexSubImage2D.
        if (!texture.resource || static_cast<UINT>(level) < texture.levels) return;
        UINT full = 1;
        for (UINT size = (std::max)(texture.width, texture.height); size > 1; size >>= 1) ++full;
        create(texture, texture.format, texture.depth, texture.glInternal, texture.width, texture.height,
                (std::min)(static_cast<UINT>(level) + 1, full));
    }

    void textureSubImage(int id, int level, int x, int y, UINT w, UINT h, int glFormat, const uint8_t* pixels,
            UINT rowLength, UINT skipPixels, UINT skipRows, UINT alignment) {
        auto found = textures.find(id);
        if (found == textures.end() || !found->second.resource || w == 0 || h == 0) return;
        auto& texture = found->second;
        if (static_cast<UINT>(level) >= texture.levels || texture.depth) return;
        UINT sourceBytes = glFormat == 6407 ? 3 : glFormat == 6403 ? 1 : glFormat == 33319 ? 2 : 4;
        UINT destBytes = formatBytes(texture.format);
        UINT rowPixels = rowLength ? rowLength : w;
        UINT sourcePitch = static_cast<UINT>(alignUp(static_cast<UINT64>(rowPixels) * sourceBytes, alignment ? alignment : 1));
        UINT pitch = static_cast<UINT>(alignUp(static_cast<UINT64>(w) * destBytes, D3D12_TEXTURE_DATA_PITCH_ALIGNMENT));
        UINT64 offset = allocate(static_cast<UINT64>(pitch) * h, D3D12_TEXTURE_DATA_PLACEMENT_ALIGNMENT);
        const uint8_t* origin = pixels + static_cast<size_t>(skipRows) * sourcePitch + static_cast<size_t>(skipPixels) * sourceBytes;
        for (UINT row = 0; row < h; ++row) {
            const uint8_t* src = origin + static_cast<size_t>(row) * sourcePitch;
            uint8_t* dst = ringData + offset + static_cast<size_t>(row) * pitch;
            if (sourceBytes == destBytes) {
                std::memcpy(dst, src, static_cast<size_t>(w) * destBytes);
            } else {
                for (UINT i = 0; i < w; ++i) {
                    uint8_t px[4] = {0, 0, 0, 255};
                    for (UINT c = 0; c < (std::min)(sourceBytes, 4u); ++c) px[c] = src[i * sourceBytes + c];
                    std::memcpy(dst + i * destBytes, px, destBytes);
                }
            }
        }
        transition(texture, D3D12_RESOURCE_STATE_COPY_DEST);
        D3D12_TEXTURE_COPY_LOCATION dest{texture.resource.Get(), D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX};
        dest.SubresourceIndex = static_cast<UINT>(level);
        D3D12_TEXTURE_COPY_LOCATION source{ring.Get(), D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT};
        source.PlacedFootprint.Offset = offset;
        source.PlacedFootprint.Footprint = {texture.format, w, h, 1, pitch};
        list->CopyTextureRegion(&dest, static_cast<UINT>(x), static_cast<UINT>(y), 0, &source, nullptr);
    }

    void textureParams(int id, int minFilter, int magFilter, int wrapS, int wrapT, int maxLevel, float lodBias,
            float minLod, float maxLod) {
        auto& texture = textures[id];
        texture.minFilter = minFilter;
        texture.magFilter = magFilter;
        texture.wrapS = wrapS;
        texture.wrapT = wrapT;
        texture.maxLevel = maxLevel;
        texture.lodBias = lodBias;
        texture.minLod = minLod;
        texture.maxLod = maxLod;
    }

    void textureDelete(int id) {
        auto found = textures.find(id);
        if (found == textures.end()) return;
        retire(found->second.resource);
        // Descriptor contents are copied when a draw or clear is recorded, so the slots are reusable at once.
        if (found->second.cpuView >= 0) freeCpuViews.push_back(found->second.cpuView);
        if (found->second.rtv >= 0) freeRtvs.push_back(found->second.rtv);
        if (found->second.dsv >= 0) freeDsvs.push_back(found->second.dsv);
        textures.erase(found);
    }

    // ---- Framebuffers ---------------------------------------------------------------------------------------
    void attach(int fbo, int slot, int texture) {
        auto& target = framebuffers[fbo];
        (slot == 0 ? target.color : target.depth) = texture;
    }
    void deleteFramebuffer(int fbo) { framebuffers.erase(fbo); }

    void clear(int fbo, int mask, const float color[4], float depth, const int scissor[5], const int colorWrite) {
        auto found = framebuffers.find(fbo);
        if (fbo == 0 || found == framebuffers.end()) return;
        D3D12_RECT rect{};
        D3D12_RECT* rects = nullptr;
        UINT count = 0;
        if (scissor[0]) {
            rect = {scissor[1], scissor[2], scissor[1] + scissor[3], scissor[2] + scissor[4]};
            rects = &rect;
            count = 1;
        }
        if ((mask & 0x4000) && colorWrite) {
            auto* texture = colorTarget(found->second.color);
            if (texture) {
                transition(*texture, D3D12_RESOURCE_STATE_RENDER_TARGET);
                list->ClearRenderTargetView(rtv(*texture), color, count, rects);
            }
        }
        if (mask & 0x100) {
            auto* texture = depthTarget(found->second.depth);
            if (texture) {
                transition(*texture, D3D12_RESOURCE_STATE_DEPTH_WRITE);
                list->ClearDepthStencilView(dsv(*texture), D3D12_CLEAR_FLAG_DEPTH, depth, 0, count, rects);
            }
        }
    }

    // ---- Programs and draws ---------------------------------------------------------------------------------
    int createProgram(const std::string& name, const std::string& vertex, const std::string& fragment,
            UINT cbufferBytes, UINT textureCount, std::string& error) {
        Program program;
        if (!compile(name + "/vert.hlsl", vertex, "vs_5_1", program.vs, error)
                || !compile(name + "/frag.hlsl", fragment, "ps_5_1", program.ps, error)) return -1;
        program.cbufferBytes = cbufferBytes;
        program.textures = (std::min)(textureCount, kMaxTextures);
        programs.push_back(std::move(program));
        return static_cast<int>(programs.size()) - 1;
    }

    // layout: per element {location, dxgiFormat, offset}; location -1 is ignored. inputs: shader input locations.
    void draw(int programId, int fbo, const uint8_t* vertices, UINT vertexBytes, UINT stride, const int* layout,
            int layoutCount, const int* inputs, int inputCount, const uint8_t* indices, UINT indexBytes, bool wideIndices,
            UINT indexCount, int topology, const int* state, const uint8_t* cbuffer, UINT cbufferBytes,
            const int* textureIds, int textureCount) {
        if (fbo == 0 || programId < 0 || programId >= static_cast<int>(programs.size()) || indexCount == 0) return;
        auto target = framebuffers.find(fbo);
        if (target == framebuffers.end()) return;
        Texture* color = colorTarget(target->second.color);
        Texture* depth = depthTarget(target->second.depth);
        if (!color && !depth) return;
        auto& program = programs[programId];

        // state: blend[7], depth[3], cull, colorMask, scissor[5], viewport[4], biasEnable, factor bits, units bits
        PsoKey key{};
        key.program = programId;
        uint64_t layoutHash = 1469598103934665603ull;
        auto mix = [&](int value) { layoutHash = (layoutHash ^ static_cast<uint32_t>(value)) * 1099511628211ull; };
        mix(static_cast<int>(stride));
        for (int i = 0; i < layoutCount * 3; ++i) mix(layout[i]);
        for (int i = 0; i < inputCount; ++i) mix(inputs[i]);
        key.layout = layoutHash;
        std::memcpy(key.blend, state, sizeof(key.blend));
        if (!key.blend[0]) std::fill(key.blend + 1, key.blend + 7, 0);
        key.depth[0] = depth ? state[7] : 0;
        key.depth[1] = key.depth[0] ? state[8] : 0;
        key.depth[2] = key.depth[0] ? state[9] : 0;
        key.cull = state[10];
        key.colorMask = state[11];
        key.topologyType = topology == 1 || topology == 3 ? 2 : topology == 0 ? 1 : 3;
        key.colorFormat = color ? static_cast<int>(color->format) : 0;
        key.depthFormat = depth ? static_cast<int>(depth->format) : 0;
        key.bias = state[21] ? state[23] : 0;  // units
        key.slope = state[21] ? state[22] : 0; // factor
        ID3D12PipelineState* pso = pipeline(key, program, stride, layout, layoutCount, inputs, inputCount);
        if (!pso) return;

        // Data: vertices, indices and constants all come from the upload ring for this frame.
        UINT64 vertexOffset = allocate(vertexBytes, 16);
        std::memcpy(ringData + vertexOffset, vertices, vertexBytes);
        UINT64 indexOffset = allocate(indexBytes, 16);
        std::memcpy(ringData + indexOffset, indices, indexBytes);
        UINT64 constantOffset = 0;
        UINT constantBytes = static_cast<UINT>(alignUp((std::max)(program.cbufferBytes, 16u), 256));
        constantOffset = allocate(constantBytes, 256);
        std::memset(ringData + constantOffset, 0, constantBytes);
        std::memcpy(ringData + constantOffset, cbuffer, (std::min)(cbufferBytes, program.cbufferBytes));

        if (color) transition(*color, D3D12_RESOURCE_STATE_RENDER_TARGET);
        if (depth) transition(*depth, key.depth[0] ? D3D12_RESOURCE_STATE_DEPTH_WRITE : D3D12_RESOURCE_STATE_DEPTH_READ);
        // The root table always spans t0..t11/s0..s11; slots the program does not use get a null view.
        UINT tableCount = kMaxTextures;
        UINT viewBase = allocateViews(tableCount);
        UINT samplerBase = allocateSamplers(tableCount);
        for (UINT i = 0; i < tableCount; ++i) {
            Texture* texture = nullptr;
            if (static_cast<int>(i) < textureCount && textureIds[i] > 0) {
                auto it = textures.find(textureIds[i]);
                if (it != textures.end() && it->second.resource && &it->second != color && &it->second != depth) texture = &it->second;
            }
            if (texture) transition(*texture, D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE | D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
            device->CopyDescriptorsSimple(1, gpuViewCpu(viewBase + i), cpuView(texture ? texture->cpuView : nullView),
                    D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV);
            createSampler(texture, samplerBase + i);
        }

        D3D12_CPU_DESCRIPTOR_HANDLE rtvHandle{}, dsvHandle{};
        if (color) rtvHandle = rtv(*color);
        if (depth) dsvHandle = dsv(*depth);
        list->OMSetRenderTargets(color ? 1 : 0, color ? &rtvHandle : nullptr, FALSE, depth ? &dsvHandle : nullptr);
        list->SetPipelineState(pso);
        list->SetGraphicsRootSignature(root.Get());
        ID3D12DescriptorHeap* heaps[] = {views.Get(), samplers.Get()};
        list->SetDescriptorHeaps(2, heaps);
        list->SetGraphicsRootConstantBufferView(0, ring->GetGPUVirtualAddress() + constantOffset);
        list->SetGraphicsRootDescriptorTable(1, gpuView(viewBase));
        list->SetGraphicsRootDescriptorTable(2, gpuSampler(samplerBase));
        const UINT rtWidth = color ? color->width : depth->width, rtHeight = color ? color->height : depth->height;
        D3D12_VIEWPORT viewport{static_cast<float>(state[17]), static_cast<float>(state[18]),
                                static_cast<float>(state[19]), static_cast<float>(state[20]), 0, 1};
        list->RSSetViewports(1, &viewport);
        D3D12_RECT scissor{0, 0, static_cast<LONG>(rtWidth), static_cast<LONG>(rtHeight)};
        if (state[12]) scissor = {state[13], state[14], state[13] + state[15], state[14] + state[16]};
        list->RSSetScissorRects(1, &scissor);
        D3D12_PRIMITIVE_TOPOLOGY primitive = topology == 1 ? D3D_PRIMITIVE_TOPOLOGY_LINELIST
                : topology == 3 ? D3D_PRIMITIVE_TOPOLOGY_LINESTRIP : topology == 5 ? D3D_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP
                : topology == 0 ? D3D_PRIMITIVE_TOPOLOGY_POINTLIST : D3D_PRIMITIVE_TOPOLOGY_TRIANGLELIST;
        list->IASetPrimitiveTopology(primitive);
        D3D12_VERTEX_BUFFER_VIEW buffers[2] = {
            {ring->GetGPUVirtualAddress() + vertexOffset, vertexBytes, stride},
            {ring->GetGPUVirtualAddress() + defaultAttribute, 16, 0}};
        list->IASetVertexBuffers(0, 2, buffers);
        D3D12_INDEX_BUFFER_VIEW indexView{ring->GetGPUVirtualAddress() + indexOffset, indexBytes,
                                          wideIndices ? DXGI_FORMAT_R32_UINT : DXGI_FORMAT_R16_UINT};
        list->IASetIndexBuffer(&indexView);
        list->DrawIndexedInstanced(indexCount, 1, 0, 0, 0);
        ++drawCount;
    }

    // Presents a texture on the game window through a DXGI flip-model swap chain. The texture keeps GL's bottom-up
    // rows, so the full-screen copy flips V. Runs everything recorded for the frame first.
    // verify: after presenting, compares 64 desktop pixels of the window with the texture; returns how many match.
    int present(HWND window, int id, UINT width, UINT height, bool vsync, bool verify) {
        auto found = textures.find(id);
        if (found == textures.end() || !found->second.resource || width == 0 || height == 0) { flush(); return -1; }
        if (!swapChain) createSwapChain(window, width, height);
        if (width != chainWidth || height != chainHeight) {
            flush();
            for (auto& buffer : backBuffers) buffer.Reset();
            check(swapChain->ResizeBuffers(kBackBuffers, width, height, DXGI_FORMAT_R8G8B8A8_UNORM, chainFlags), "ResizeBuffers");
            acquireBackBuffers(width, height);
        }
        if (!blitPso) createBlit();
        auto& texture = found->second;
        UINT index = swapChain->GetCurrentBackBufferIndex();
        transition(texture, D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE | D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE);
        D3D12_RESOURCE_BARRIER barrier{};
        barrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
        barrier.Transition = {backBuffers[index].Get(), D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES,
                              D3D12_RESOURCE_STATE_PRESENT, D3D12_RESOURCE_STATE_RENDER_TARGET};
        list->ResourceBarrier(1, &barrier);
        UINT viewBase = allocateViews(kMaxTextures), samplerBase = allocateSamplers(kMaxTextures);
        for (UINT i = 0; i < kMaxTextures; ++i) {
            device->CopyDescriptorsSimple(1, gpuViewCpu(viewBase + i), cpuView(i == 0 ? texture.cpuView : nullView),
                    D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV);
            createSampler(nullptr, samplerBase + i); // point, clamp
        }
        UINT64 constants = allocate(256, 256);
        auto rtvHandle = rtvs->GetCPUDescriptorHandleForHeapStart();
        rtvHandle.ptr += static_cast<SIZE_T>(backBufferRtv[index]) * rtvSize;
        list->OMSetRenderTargets(1, &rtvHandle, FALSE, nullptr);
        list->SetPipelineState(blitPso.Get());
        list->SetGraphicsRootSignature(root.Get());
        ID3D12DescriptorHeap* heaps[] = {views.Get(), samplers.Get()};
        list->SetDescriptorHeaps(2, heaps);
        list->SetGraphicsRootConstantBufferView(0, ring->GetGPUVirtualAddress() + constants);
        list->SetGraphicsRootDescriptorTable(1, gpuView(viewBase));
        list->SetGraphicsRootDescriptorTable(2, gpuSampler(samplerBase));
        D3D12_VIEWPORT viewport{0, 0, static_cast<float>(width), static_cast<float>(height), 0, 1};
        D3D12_RECT scissor{0, 0, static_cast<LONG>(width), static_cast<LONG>(height)};
        list->RSSetViewports(1, &viewport);
        list->RSSetScissorRects(1, &scissor);
        list->IASetPrimitiveTopology(D3D_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
        list->DrawInstanced(3, 1, 0, 0);
        std::swap(barrier.Transition.StateBefore, barrier.Transition.StateAfter);
        list->ResourceBarrier(1, &barrier);
        flush();
        check(swapChain->Present(vsync ? 1 : 0, !vsync && chainFlags ? DXGI_PRESENT_ALLOW_TEARING : 0), "Present");
        if (!verify || texture.width != width || texture.height != height || texture.format != DXGI_FORMAT_R8G8B8A8_UNORM) return -1;
        std::vector<uint8_t> pixels(static_cast<size_t>(width) * height * 4);
        readback(id, pixels.data(), pixels.size());
        DwmFlush();
        POINT origin{};
        ClientToScreen(window, &origin);
        HDC desktop = GetDC(nullptr);
        int matches = 0;
        for (int gy = 0; gy < 8; ++gy) for (int gx = 0; gx < 8; ++gx) {
            UINT x = (2 * gx + 1) * width / 16, y = (2 * gy + 1) * height / 16; // window rows, top-down
            const uint8_t* p = pixels.data() + (static_cast<size_t>(height - 1 - y) * width + x) * 4;
            COLORREF shown = GetPixel(desktop, origin.x + static_cast<int>(x), origin.y + static_cast<int>(y));
            int d = (std::max)({std::abs(GetRValue(shown) - p[0]), std::abs(GetGValue(shown) - p[1]), std::abs(GetBValue(shown) - p[2])});
            if (d <= 2) ++matches;
        }
        ReleaseDC(nullptr, desktop);
        return matches;
    }

    // Tightly packed rows of the texture's level 0, OpenGL row order (see file header).
    void readback(int id, uint8_t* out, UINT64 capacity) {
        auto found = textures.find(id);
        if (found == textures.end() || !found->second.resource) throw std::runtime_error("readback: unknown texture");
        auto& texture = found->second;
        D3D12_PLACED_SUBRESOURCE_FOOTPRINT footprint{};
        UINT64 total = 0;
        auto desc = texture.resource->GetDesc();
        device->GetCopyableFootprints(&desc, 0, 1, 0, &footprint, nullptr, nullptr, &total);
        UINT bytes = formatBytes(texture.depth ? DXGI_FORMAT_R8G8B8A8_UNORM : texture.format);
        if (capacity < static_cast<UINT64>(texture.width) * texture.height * bytes) throw std::runtime_error("readback: small buffer");
        ComPtr<ID3D12Resource> buffer;
        D3D12_HEAP_PROPERTIES heapProps{D3D12_HEAP_TYPE_READBACK};
        auto bd = bufferDesc(total);
        check(device->CreateCommittedResource(&heapProps, D3D12_HEAP_FLAG_NONE, &bd, D3D12_RESOURCE_STATE_COPY_DEST, nullptr,
                IID_PPV_ARGS(&buffer)), "readback buffer");
        transition(texture, D3D12_RESOURCE_STATE_COPY_SOURCE);
        D3D12_TEXTURE_COPY_LOCATION source{texture.resource.Get(), D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX};
        D3D12_TEXTURE_COPY_LOCATION dest{buffer.Get(), D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT};
        dest.PlacedFootprint = footprint;
        list->CopyTextureRegion(&dest, 0, 0, 0, &source, nullptr);
        flush();
        uint8_t* mapped = nullptr;
        D3D12_RANGE range{0, static_cast<SIZE_T>(total)};
        check(buffer->Map(0, &range, reinterpret_cast<void**>(&mapped)), "Map readback");
        for (UINT row = 0; row < texture.height; ++row)
            std::memcpy(out + static_cast<size_t>(row) * texture.width * bytes, mapped + footprint.Offset
                    + static_cast<size_t>(row) * footprint.Footprint.RowPitch, static_cast<size_t>(texture.width) * bytes);
        D3D12_RANGE none{0, 0};
        buffer->Unmap(0, &none);
    }

    // Executes everything recorded so far and waits; the shadow backend is synchronous by design.
    void flush() {
        check(list->Close(), "Close");
        ID3D12CommandList* lists[] = {list.Get()};
        queue->ExecuteCommandLists(1, lists);
        check(queue->Signal(fence.Get(), ++fenceValue), "Signal");
        check(fence->SetEventOnCompletion(fenceValue, event), "SetEventOnCompletion");
        if (WaitForSingleObject(event, 10000) != WAIT_OBJECT_0) throw std::runtime_error("DX12 backend fence timed out");
        check(device->GetDeviceRemovedReason(), "device");
        check(allocator->Reset(), "allocator Reset");
        check(list->Reset(allocator.Get(), nullptr), "list Reset");
        ringOffset = persistentRing;
        viewOffset = samplerOffset = 0;
        retired.clear();
    }

    UINT64 draws() const { return drawCount; }

private:
    struct Framebuffer { int color = 0, depth = 0; };

    ComPtr<ID3D12Device> device;
    ComPtr<ID3D12InfoQueue> info;
    ComPtr<ID3D12CommandQueue> queue;
    ComPtr<ID3D12CommandAllocator> allocator;
    ComPtr<ID3D12GraphicsCommandList> list;
    ComPtr<ID3D12Fence> fence;
    HANDLE event = nullptr;
    UINT64 fenceValue = 0;
    ComPtr<ID3D12DescriptorHeap> views, samplers, cpuViews, rtvs, dsvs;
    UINT viewSize = 0, samplerSize = 0, rtvSize = 0, dsvSize = 0;
    UINT viewOffset = 0, samplerOffset = 0, cpuViewNext = 0, rtvNext = 0, dsvNext = 0;
    std::vector<int> freeCpuViews, freeRtvs, freeDsvs;
    int nullView = 0;
    ComPtr<ID3D12RootSignature> root;
    ComPtr<ID3D12Resource> ring;
    uint8_t* ringData = nullptr;
    UINT64 ringOffset = 0, persistentRing = 0, defaultAttribute = 0;
    std::unordered_map<int, Texture> textures;
    std::unordered_map<int, Framebuffer> framebuffers;
    std::vector<Program> programs;
    std::unordered_map<PsoKey, ComPtr<ID3D12PipelineState>, PsoHash> psos;
    std::vector<ComPtr<ID3D12Resource>> retired;
    UINT64 drawCount = 0;
    static constexpr UINT kBackBuffers = 2;
    ComPtr<IDXGISwapChain3> swapChain;
    ComPtr<ID3D12Resource> backBuffers[kBackBuffers];
    int backBufferRtv[kBackBuffers] = {-1, -1};
    UINT chainWidth = 0, chainHeight = 0, chainFlags = 0;
    ComPtr<ID3D12PipelineState> blitPso;

    void createSwapChain(HWND window, UINT width, UINT height) {
        ComPtr<IDXGIFactory5> factory;
        check(CreateDXGIFactory2(0, IID_PPV_ARGS(&factory)), "CreateDXGIFactory2");
        BOOL tearing = FALSE;
        if (SUCCEEDED(factory->CheckFeatureSupport(DXGI_FEATURE_PRESENT_ALLOW_TEARING, &tearing, sizeof(tearing))) && tearing)
            chainFlags = DXGI_SWAP_CHAIN_FLAG_ALLOW_TEARING;
        DXGI_SWAP_CHAIN_DESC1 desc{};
        desc.Width = width;
        desc.Height = height;
        desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
        desc.SampleDesc.Count = 1;
        desc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
        desc.BufferCount = kBackBuffers;
        desc.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;
        desc.Flags = chainFlags;
        ComPtr<IDXGISwapChain1> chain;
        check(factory->CreateSwapChainForHwnd(queue.Get(), window, &desc, nullptr, nullptr, &chain), "CreateSwapChainForHwnd");
        check(factory->MakeWindowAssociation(window, DXGI_MWA_NO_ALT_ENTER), "MakeWindowAssociation");
        check(chain.As(&swapChain), "IDXGISwapChain3");
        acquireBackBuffers(width, height);
    }

    void acquireBackBuffers(UINT width, UINT height) {
        for (UINT i = 0; i < kBackBuffers; ++i) {
            check(swapChain->GetBuffer(i, IID_PPV_ARGS(&backBuffers[i])), "GetBuffer");
            if (backBufferRtv[i] < 0) backBufferRtv[i] = take(freeRtvs, rtvNext, 4096);
            auto handle = rtvs->GetCPUDescriptorHandleForHeapStart();
            handle.ptr += static_cast<SIZE_T>(backBufferRtv[i]) * rtvSize;
            device->CreateRenderTargetView(backBuffers[i].Get(), nullptr, handle);
        }
        chainWidth = width;
        chainHeight = height;
    }

    void createBlit() {
        static const char source[] = R"(
Texture2D<float4> Source : register(t0);
SamplerState Point : register(s0);
struct V { float4 position : SV_Position; float2 uv : TEXCOORD0; };
V vs(uint id : SV_VertexID) {
    V v;
    float2 corner = float2((id << 1) & 2, id & 2);
    v.position = float4(corner * float2(2, -2) + float2(-1, 1), 0, 1);
    v.uv = float2(corner.x, 1 - corner.y); // GL rows are bottom-up
    return v;
}
float4 ps(V v) : SV_Target0 { return float4(Source.SampleLevel(Point, v.uv, 0).rgb, 1); }
)";
        ComPtr<ID3DBlob> vs, ps, log;
        check(D3DCompile(source, sizeof(source) - 1, "present", nullptr, nullptr, "vs", "vs_5_1", D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, &vs, &log), "blit vs");
        check(D3DCompile(source, sizeof(source) - 1, "present", nullptr, nullptr, "ps", "ps_5_1", D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, &ps, &log), "blit ps");
        D3D12_GRAPHICS_PIPELINE_STATE_DESC desc{};
        desc.pRootSignature = root.Get();
        desc.VS = {vs->GetBufferPointer(), vs->GetBufferSize()};
        desc.PS = {ps->GetBufferPointer(), ps->GetBufferSize()};
        desc.BlendState.RenderTarget[0].RenderTargetWriteMask = D3D12_COLOR_WRITE_ENABLE_ALL;
        desc.SampleMask = UINT_MAX;
        desc.RasterizerState.FillMode = D3D12_FILL_MODE_SOLID;
        desc.RasterizerState.CullMode = D3D12_CULL_MODE_NONE;
        desc.RasterizerState.DepthClipEnable = TRUE;
        desc.PrimitiveTopologyType = D3D12_PRIMITIVE_TOPOLOGY_TYPE_TRIANGLE;
        desc.NumRenderTargets = 1;
        desc.RTVFormats[0] = DXGI_FORMAT_R8G8B8A8_UNORM;
        desc.SampleDesc.Count = 1;
        check(device->CreateGraphicsPipelineState(&desc, IID_PPV_ARGS(&blitPso)), "present pipeline");
    }

    void heap(D3D12_DESCRIPTOR_HEAP_TYPE type, UINT count, bool visible, ComPtr<ID3D12DescriptorHeap>* out) {
        D3D12_DESCRIPTOR_HEAP_DESC desc{type, count, visible ? D3D12_DESCRIPTOR_HEAP_FLAG_SHADER_VISIBLE : D3D12_DESCRIPTOR_HEAP_FLAG_NONE};
        check(device->CreateDescriptorHeap(&desc, IID_PPV_ARGS(out->GetAddressOf())), "CreateDescriptorHeap");
    }
    static D3D12_RESOURCE_DESC bufferDesc(UINT64 bytes) {
        D3D12_RESOURCE_DESC desc{};
        desc.Dimension = D3D12_RESOURCE_DIMENSION_BUFFER;
        desc.Width = bytes;
        desc.Height = desc.DepthOrArraySize = desc.MipLevels = 1;
        desc.SampleDesc.Count = 1;
        desc.Layout = D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
        return desc;
    }

    void createRootSignature() {
        // spirv-cross drops register spaces for SM 5.0: cbuffer b0, textures t0..t11 and samplers s0..s11, space 0.
        D3D12_DESCRIPTOR_RANGE srv{D3D12_DESCRIPTOR_RANGE_TYPE_SRV, kMaxTextures, 0, 0, 0};
        D3D12_DESCRIPTOR_RANGE sampler{D3D12_DESCRIPTOR_RANGE_TYPE_SAMPLER, kMaxTextures, 0, 0, 0};
        D3D12_ROOT_PARAMETER params[3]{};
        params[0].ParameterType = D3D12_ROOT_PARAMETER_TYPE_CBV;
        params[0].Descriptor = {0, 0};
        params[1].ParameterType = D3D12_ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE;
        params[1].DescriptorTable = {1, &srv};
        params[2].ParameterType = D3D12_ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE;
        params[2].DescriptorTable = {1, &sampler};
        for (auto& p : params) p.ShaderVisibility = D3D12_SHADER_VISIBILITY_ALL;
        D3D12_ROOT_SIGNATURE_DESC desc{3, params, 0, nullptr, D3D12_ROOT_SIGNATURE_FLAG_ALLOW_INPUT_ASSEMBLER_INPUT_LAYOUT};
        ComPtr<ID3DBlob> blob, error;
        check(D3D12SerializeRootSignature(&desc, D3D_ROOT_SIGNATURE_VERSION_1, &blob, &error), "D3D12SerializeRootSignature");
        check(device->CreateRootSignature(0, blob->GetBufferPointer(), blob->GetBufferSize(), IID_PPV_ARGS(&root)), "CreateRootSignature");
    }

    bool compile(const std::string& name, const std::string& source, const char* profile, ComPtr<ID3DBlob>& out, std::string& error) {
        ComPtr<ID3DBlob> log;
        HRESULT result = D3DCompile(source.data(), source.size(), name.c_str(), nullptr, nullptr, "main", profile,
                D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, &out, &log);
        if (SUCCEEDED(result)) return true;
        error = name + ": " + (log ? std::string(static_cast<const char*>(log->GetBufferPointer()), log->GetBufferSize()) : "D3DCompile failed");
        return false;
    }

    UINT64 allocate(UINT64 bytes, UINT64 alignment) {
        UINT64 offset = alignUp(ringOffset, alignment);
        if (offset + bytes > kRingBytes) {
            if (bytes + persistentRing > kRingBytes) throw std::runtime_error("upload larger than the DX12 backend ring");
            flush();
            offset = alignUp(ringOffset, alignment);
        }
        ringOffset = offset + bytes;
        return offset;
    }
    UINT allocateViews(UINT count) {
        if (viewOffset + count > kViewRing) flush();
        UINT base = viewOffset;
        viewOffset += count;
        return base;
    }
    UINT allocateSamplers(UINT count) {
        if (samplerOffset + count > kSamplerRing) flush();
        UINT base = samplerOffset;
        samplerOffset += count;
        return base;
    }
    int allocateCpuView() {
        if (!freeCpuViews.empty()) { int index = freeCpuViews.back(); freeCpuViews.pop_back(); return index; }
        if (cpuViewNext >= kCpuViews) throw std::runtime_error("DX12 backend texture views exhausted");
        return static_cast<int>(cpuViewNext++);
    }
    D3D12_CPU_DESCRIPTOR_HANDLE cpuView(int index) {
        auto handle = cpuViews->GetCPUDescriptorHandleForHeapStart();
        handle.ptr += static_cast<SIZE_T>(index) * viewSize;
        return handle;
    }
    D3D12_CPU_DESCRIPTOR_HANDLE gpuViewCpu(UINT index) {
        auto handle = views->GetCPUDescriptorHandleForHeapStart();
        handle.ptr += static_cast<SIZE_T>(index) * viewSize;
        return handle;
    }
    D3D12_GPU_DESCRIPTOR_HANDLE gpuView(UINT index) {
        auto handle = views->GetGPUDescriptorHandleForHeapStart();
        handle.ptr += static_cast<UINT64>(index) * viewSize;
        return handle;
    }
    D3D12_GPU_DESCRIPTOR_HANDLE gpuSampler(UINT index) {
        auto handle = samplers->GetGPUDescriptorHandleForHeapStart();
        handle.ptr += static_cast<UINT64>(index) * samplerSize;
        return handle;
    }
    D3D12_CPU_DESCRIPTOR_HANDLE rtv(Texture& texture) {
        auto handle = rtvs->GetCPUDescriptorHandleForHeapStart();
        handle.ptr += static_cast<SIZE_T>(texture.rtv) * rtvSize;
        return handle;
    }
    D3D12_CPU_DESCRIPTOR_HANDLE dsv(Texture& texture) {
        auto handle = dsvs->GetCPUDescriptorHandleForHeapStart();
        handle.ptr += static_cast<SIZE_T>(texture.dsv) * dsvSize;
        return handle;
    }

    static int take(std::vector<int>& free, UINT& next, UINT capacity) {
        if (!free.empty()) { int index = free.back(); free.pop_back(); return index; }
        if (next >= capacity) throw std::runtime_error("DX12 backend render target views exhausted");
        return static_cast<int>(next++);
    }

    void retire(ComPtr<ID3D12Resource>& resource) {
        if (resource) retired.push_back(resource);
        resource.Reset();
    }

    void create(Texture& texture, DXGI_FORMAT format, bool depth, int internal, UINT width, UINT height, UINT levels) {
        retire(texture.resource);
        D3D12_RESOURCE_DESC desc{};
        desc.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
        desc.Width = width;
        desc.Height = height;
        desc.DepthOrArraySize = 1;
        desc.MipLevels = static_cast<UINT16>(levels);
        desc.Format = format;
        desc.SampleDesc.Count = 1;
        desc.Flags = depth ? D3D12_RESOURCE_FLAG_ALLOW_DEPTH_STENCIL : D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET;
        D3D12_HEAP_PROPERTIES heapProps{D3D12_HEAP_TYPE_DEFAULT};
        D3D12_CLEAR_VALUE clear{};
        clear.Format = depth ? depthViewFormat(format) : format;
        if (depth) clear.DepthStencil.Depth = 1.0f;
        check(device->CreateCommittedResource(&heapProps, D3D12_HEAP_FLAG_NONE, &desc, D3D12_RESOURCE_STATE_COMMON,
                &clear, IID_PPV_ARGS(&texture.resource)), "texture");
        texture.state = D3D12_RESOURCE_STATE_COMMON;
        texture.format = format;
        texture.depth = depth;
        texture.glInternal = internal;
        texture.width = width;
        texture.height = height;
        texture.levels = levels;
        if (texture.cpuView < 0) texture.cpuView = allocateCpuView();
        D3D12_SHADER_RESOURCE_VIEW_DESC srv{};
        srv.Format = viewFormat(format);
        srv.ViewDimension = D3D12_SRV_DIMENSION_TEXTURE2D;
        srv.Shader4ComponentMapping = D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING;
        srv.Texture2D.MipLevels = levels;
        device->CreateShaderResourceView(texture.resource.Get(), &srv, cpuView(texture.cpuView));
        if (depth) {
            if (texture.dsv < 0) texture.dsv = take(freeDsvs, dsvNext, 1024);
            D3D12_DEPTH_STENCIL_VIEW_DESC view{};
            view.Format = depthViewFormat(format);
            view.ViewDimension = D3D12_DSV_DIMENSION_TEXTURE2D;
            device->CreateDepthStencilView(texture.resource.Get(), &view, dsv(texture));
        } else {
            if (texture.rtv < 0) texture.rtv = take(freeRtvs, rtvNext, 4096);
            D3D12_RENDER_TARGET_VIEW_DESC view{};
            view.Format = format;
            view.ViewDimension = D3D12_RTV_DIMENSION_TEXTURE2D;
            device->CreateRenderTargetView(texture.resource.Get(), &view, rtv(texture));
        }
    }

    Texture* colorTarget(int id) {
        auto it = textures.find(id);
        return id > 0 && it != textures.end() && it->second.resource && !it->second.depth ? &it->second : nullptr;
    }
    Texture* depthTarget(int id) {
        auto it = textures.find(id);
        return id > 0 && it != textures.end() && it->second.resource && it->second.depth ? &it->second : nullptr;
    }

    void transition(Texture& texture, D3D12_RESOURCE_STATES state) {
        if (texture.state == state) return;
        D3D12_RESOURCE_BARRIER barrier{};
        barrier.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
        barrier.Transition = {texture.resource.Get(), D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES, texture.state, state};
        list->ResourceBarrier(1, &barrier);
        texture.state = state;
    }

    void createSampler(Texture* texture, UINT slot) {
        D3D12_SAMPLER_DESC desc{};
        int minFilter = texture ? texture->minFilter : 9728, magFilter = texture ? texture->magFilter : 9728;
        bool minLinear = minFilter == 9729 || minFilter == 9985 || minFilter == 9987;
        bool mipmapped = minFilter >= 9984 && minFilter <= 9987;
        bool mipLinear = minFilter == 9986 || minFilter == 9987;
        bool magLinear = magFilter == 9729;
        desc.Filter = D3D12_ENCODE_BASIC_FILTER(minLinear ? D3D12_FILTER_TYPE_LINEAR : D3D12_FILTER_TYPE_POINT,
                magLinear ? D3D12_FILTER_TYPE_LINEAR : D3D12_FILTER_TYPE_POINT,
                mipLinear ? D3D12_FILTER_TYPE_LINEAR : D3D12_FILTER_TYPE_POINT, D3D12_FILTER_REDUCTION_TYPE_STANDARD);
        desc.AddressU = address(texture ? texture->wrapS : 33071);
        desc.AddressV = address(texture ? texture->wrapT : 33071);
        desc.AddressW = D3D12_TEXTURE_ADDRESS_MODE_CLAMP;
        desc.MipLODBias = texture ? texture->lodBias : 0;
        desc.MaxAnisotropy = 1;
        desc.ComparisonFunc = D3D12_COMPARISON_FUNC_NEVER;
        desc.MinLOD = mipmapped ? (std::max)(0.0f, texture->minLod) : 0.0f;
        float top = texture ? static_cast<float>((std::min)(static_cast<UINT>(texture->maxLevel), texture->levels - 1)) : 0.0f;
        desc.MaxLOD = mipmapped ? (std::min)(top, texture->maxLod) : 0.0f;
        auto handle = samplers->GetCPUDescriptorHandleForHeapStart();
        handle.ptr += static_cast<SIZE_T>(slot) * samplerSize;
        device->CreateSampler(&desc, handle);
    }

    ID3D12PipelineState* pipeline(const PsoKey& key, Program& program, UINT stride, const int* layout, int layoutCount,
            const int* inputs, int inputCount) {
        auto found = psos.find(key);
        if (found != psos.end()) return found->second.Get();
        std::vector<D3D12_INPUT_ELEMENT_DESC> elements;
        for (int i = 0; i < inputCount; ++i) {
            int location = inputs[i];
            D3D12_INPUT_ELEMENT_DESC element{"TEXCOORD", static_cast<UINT>(location), DXGI_FORMAT_R32G32B32A32_FLOAT, 1, 0,
                                             D3D12_INPUT_CLASSIFICATION_PER_VERTEX_DATA, 0};
            for (int e = 0; e < layoutCount; ++e) {
                if (layout[e * 3] == location) {
                    element.Format = static_cast<DXGI_FORMAT>(layout[e * 3 + 1]);
                    element.InputSlot = 0;
                    element.AlignedByteOffset = static_cast<UINT>(layout[e * 3 + 2]);
                }
            }
            elements.push_back(element);
        }
        D3D12_GRAPHICS_PIPELINE_STATE_DESC desc{};
        desc.pRootSignature = root.Get();
        desc.VS = {program.vs->GetBufferPointer(), program.vs->GetBufferSize()};
        desc.PS = {program.ps->GetBufferPointer(), program.ps->GetBufferSize()};
        auto& blend = desc.BlendState.RenderTarget[0];
        blend.BlendEnable = key.blend[0] ? TRUE : FALSE;
        blend.SrcBlend = blendFactor(key.blend[1], false);
        blend.DestBlend = blendFactor(key.blend[2], false);
        blend.SrcBlendAlpha = blendFactor(key.blend[3], true);
        blend.DestBlendAlpha = blendFactor(key.blend[4], true);
        blend.BlendOp = blendOp(key.blend[5]);
        blend.BlendOpAlpha = blendOp(key.blend[6]);
        blend.LogicOp = D3D12_LOGIC_OP_NOOP;
        blend.RenderTargetWriteMask = static_cast<UINT8>(key.colorMask & 15);
        desc.SampleMask = UINT_MAX;
        desc.RasterizerState.FillMode = D3D12_FILL_MODE_SOLID;
        desc.RasterizerState.CullMode = key.cull ? D3D12_CULL_MODE_BACK : D3D12_CULL_MODE_NONE;
        // Y-flipped clip space makes D3D's y-down screen coordinates numerically equal to GL's y-up window
        // coordinates, so GL's counter-clockwise front faces arrive with D3D's clockwise winding (the D3D default).
        desc.RasterizerState.FrontCounterClockwise = FALSE;
        float units, factor;
        std::memcpy(&units, &key.bias, 4);
        std::memcpy(&factor, &key.slope, 4);
        desc.RasterizerState.DepthBias = static_cast<INT>(units);
        desc.RasterizerState.SlopeScaledDepthBias = factor;
        desc.RasterizerState.DepthClipEnable = TRUE;
        desc.DepthStencilState.DepthEnable = key.depth[0] ? TRUE : FALSE;
        desc.DepthStencilState.DepthWriteMask = key.depth[2] ? D3D12_DEPTH_WRITE_MASK_ALL : D3D12_DEPTH_WRITE_MASK_ZERO;
        desc.DepthStencilState.DepthFunc = compare(key.depth[1]);
        desc.InputLayout = {elements.data(), static_cast<UINT>(elements.size())};
        desc.PrimitiveTopologyType = key.topologyType == 2 ? D3D12_PRIMITIVE_TOPOLOGY_TYPE_LINE
                : key.topologyType == 1 ? D3D12_PRIMITIVE_TOPOLOGY_TYPE_POINT : D3D12_PRIMITIVE_TOPOLOGY_TYPE_TRIANGLE;
        desc.NumRenderTargets = key.colorFormat ? 1 : 0;
        desc.RTVFormats[0] = static_cast<DXGI_FORMAT>(key.colorFormat);
        desc.DSVFormat = key.depthFormat ? depthViewFormat(static_cast<DXGI_FORMAT>(key.depthFormat)) : DXGI_FORMAT_UNKNOWN;
        desc.SampleDesc.Count = 1;
        ComPtr<ID3D12PipelineState> pso;
        HRESULT result = device->CreateGraphicsPipelineState(&desc, IID_PPV_ARGS(&pso));
        if (FAILED(result)) {
            char message[96];
            std::snprintf(message, sizeof(message), "CreateGraphicsPipelineState failed (0x%08lx)", static_cast<unsigned long>(result));
            throw std::runtime_error(std::string(message) + debugMessages());
        }
        return (psos[key] = pso).Get();
    }

    /** With the debug layer (-Ddirect.phase1.debug=true): the validation messages since the last call. */
    std::string debugMessages() {
        if (!info) return "";
        std::string out;
        for (UINT64 i = 0, n = info->GetNumStoredMessages(); i < n; ++i) {
            SIZE_T length = 0;
            info->GetMessage(i, nullptr, &length);
            std::vector<char> data(length);
            auto* message = reinterpret_cast<D3D12_MESSAGE*>(data.data());
            if (SUCCEEDED(info->GetMessage(i, message, &length))) out += std::string(" | ") + message->pDescription;
        }
        info->ClearStoredMessages();
        return out;
    }
};

std::unique_ptr<Backend> g_backend;

void throwJava(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

template <typename F> void guarded(JNIEnv* env, F&& body) {
    try {
        if (!g_backend) throw std::runtime_error("DX12 backend is not initialized");
        body();
    } catch (const std::exception& error) {
        throwJava(env, error.what());
    }
}

std::string utf(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}
std::string bytes(JNIEnv* env, jbyteArray array) {
    std::string out(static_cast<size_t>(env->GetArrayLength(array)), '\0');
    env->GetByteArrayRegion(array, 0, static_cast<jsize>(out.size()), reinterpret_cast<jbyte*>(out.data()));
    return out;
}
}

#define BACKEND(name) extern "C" JNIEXPORT
#define JNI_NAME(name) Java_com_redondoguibi_direct_backend_Dx12BackendNative_##name

BACKEND(create) void JNICALL JNI_NAME(create)(JNIEnv* env, jclass, jboolean debug) {
    try { g_backend = std::make_unique<Backend>(debug == JNI_TRUE); }
    catch (const std::exception& error) { throwJava(env, error.what()); }
}
BACKEND(destroy) void JNICALL JNI_NAME(destroy)(JNIEnv*, jclass) { g_backend.reset(); }
BACKEND(flush) void JNICALL JNI_NAME(flush)(JNIEnv* env, jclass) { guarded(env, [&] { g_backend->flush(); }); }
BACKEND(draws) jlong JNICALL JNI_NAME(draws)(JNIEnv*, jclass) { return g_backend ? static_cast<jlong>(g_backend->draws()) : 0; }

BACKEND(textureImage) void JNICALL JNI_NAME(textureImage)(JNIEnv* env, jclass, jint id, jint level, jint internal, jint w, jint h) {
    guarded(env, [&] { g_backend->textureImage(id, level, internal, static_cast<UINT>(w), static_cast<UINT>(h)); });
}
BACKEND(textureSubImage) void JNICALL JNI_NAME(textureSubImage)(JNIEnv* env, jclass, jint id, jint level, jint x, jint y, jint w,
        jint h, jint format, jlong pixels, jint rowLength, jint skipPixels, jint skipRows, jint alignment) {
    guarded(env, [&] {
        g_backend->textureSubImage(id, level, x, y, static_cast<UINT>(w), static_cast<UINT>(h), format,
                reinterpret_cast<const uint8_t*>(pixels), static_cast<UINT>(rowLength), static_cast<UINT>(skipPixels),
                static_cast<UINT>(skipRows), static_cast<UINT>(alignment));
    });
}
BACKEND(textureParams) void JNICALL JNI_NAME(textureParams)(JNIEnv* env, jclass, jint id, jint minFilter, jint magFilter,
        jint wrapS, jint wrapT, jint maxLevel, jfloat lodBias, jfloat minLod, jfloat maxLod) {
    guarded(env, [&] { g_backend->textureParams(id, minFilter, magFilter, wrapS, wrapT, maxLevel, lodBias, minLod, maxLod); });
}
BACKEND(textureDelete) void JNICALL JNI_NAME(textureDelete)(JNIEnv* env, jclass, jint id) {
    guarded(env, [&] { g_backend->textureDelete(id); });
}
BACKEND(attach) void JNICALL JNI_NAME(attach)(JNIEnv* env, jclass, jint fbo, jint slot, jint texture) {
    guarded(env, [&] { g_backend->attach(fbo, slot, texture); });
}
BACKEND(deleteFramebuffer) void JNICALL JNI_NAME(deleteFramebuffer)(JNIEnv* env, jclass, jint fbo) {
    guarded(env, [&] { g_backend->deleteFramebuffer(fbo); });
}
BACKEND(clear) void JNICALL JNI_NAME(clear)(JNIEnv* env, jclass, jint fbo, jint mask, jfloat r, jfloat g, jfloat b, jfloat a,
        jfloat depth, jintArray scissor, jint colorWrite) {
    guarded(env, [&] {
        jint rect[5];
        env->GetIntArrayRegion(scissor, 0, 5, rect);
        const float color[4] = {r, g, b, a};
        g_backend->clear(fbo, mask, color, depth, reinterpret_cast<int*>(rect), colorWrite);
    });
}
BACKEND(createProgram) jint JNICALL JNI_NAME(createProgram)(JNIEnv* env, jclass, jstring name, jbyteArray vertex,
        jbyteArray fragment, jint cbufferBytes, jint textures) {
    jint result = -1;
    guarded(env, [&] {
        std::string error;
        result = g_backend->createProgram(utf(env, name), bytes(env, vertex), bytes(env, fragment),
                static_cast<UINT>(cbufferBytes), static_cast<UINT>(textures), error);
        if (result < 0) throw std::runtime_error(error);
    });
    return result;
}
BACKEND(draw) void JNICALL JNI_NAME(draw)(JNIEnv* env, jclass, jint program, jint fbo, jlong vertices, jint vertexBytes,
        jint stride, jintArray layout, jintArray inputs, jlong indices, jint indexBytes, jboolean wide, jint indexCount,
        jint topology, jintArray state, jobject cbuffer, jint cbufferBytes, jintArray textures) {
    guarded(env, [&] {
        jint* layoutData = env->GetIntArrayElements(layout, nullptr);
        jint* inputData = env->GetIntArrayElements(inputs, nullptr);
        jint* stateData = env->GetIntArrayElements(state, nullptr);
        jint* textureData = env->GetIntArrayElements(textures, nullptr);
        const auto* constants = static_cast<const uint8_t*>(env->GetDirectBufferAddress(cbuffer));
        try {
            g_backend->draw(program, fbo, reinterpret_cast<const uint8_t*>(vertices), static_cast<UINT>(vertexBytes),
                    static_cast<UINT>(stride), reinterpret_cast<int*>(layoutData), env->GetArrayLength(layout) / 3,
                    reinterpret_cast<int*>(inputData), env->GetArrayLength(inputs),
                    reinterpret_cast<const uint8_t*>(indices), static_cast<UINT>(indexBytes), wide == JNI_TRUE,
                    static_cast<UINT>(indexCount), topology, reinterpret_cast<int*>(stateData), constants,
                    static_cast<UINT>(cbufferBytes), reinterpret_cast<int*>(textureData), env->GetArrayLength(textures));
        } catch (...) {
            env->ReleaseIntArrayElements(layout, layoutData, JNI_ABORT);
            env->ReleaseIntArrayElements(inputs, inputData, JNI_ABORT);
            env->ReleaseIntArrayElements(state, stateData, JNI_ABORT);
            env->ReleaseIntArrayElements(textures, textureData, JNI_ABORT);
            throw;
        }
        env->ReleaseIntArrayElements(layout, layoutData, JNI_ABORT);
        env->ReleaseIntArrayElements(inputs, inputData, JNI_ABORT);
        env->ReleaseIntArrayElements(state, stateData, JNI_ABORT);
        env->ReleaseIntArrayElements(textures, textureData, JNI_ABORT);
    });
}
BACKEND(present) jint JNICALL JNI_NAME(present)(JNIEnv* env, jclass, jlong hwnd, jint texture, jint width, jint height,
        jboolean vsync, jboolean verify) {
    jint matches = -1;
    guarded(env, [&] {
        matches = g_backend->present(reinterpret_cast<HWND>(hwnd), texture, static_cast<UINT>(width), static_cast<UINT>(height),
                vsync == JNI_TRUE, verify == JNI_TRUE);
    });
    return matches;
}
BACKEND(readback) void JNICALL JNI_NAME(readback)(JNIEnv* env, jclass, jint texture, jobject out) {
    guarded(env, [&] {
        g_backend->readback(texture, static_cast<uint8_t*>(env->GetDirectBufferAddress(out)),
                static_cast<UINT64>(env->GetDirectBufferCapacity(out)));
    });
}
