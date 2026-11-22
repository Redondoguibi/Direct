// Phase 0 replays the frozen inputs on a private D3D12 device. No shared images,
// D3D11 device, GL context, swap or CPU clock is involved in these timestamps.
namespace {
constexpr char kPhase0Gui[] = R"(
struct Vertex { float4 position : SV_Position; float4 color : COLOR; };
Vertex vsMain(uint id:SV_VertexID, uint instance:SV_InstanceID) {
    float2 corners[4] = {float2(0,0),float2(1,0),float2(0,1),float2(1,1)};
    uint indices[6] = {0,1,2,2,1,3};
    bool icon = instance >= 9;
    uint slot = instance % 9;
    float2 origin = float2(-0.252 + slot * 0.056, -0.96);
    float2 size = float2(0.054, 0.096);
    float4 color = float4(0.1, 0.1, 0.1, 0.75);
    if (icon) { origin += size * 0.24; size *= 0.52; color = float4(0.8, 0.7, 0.5, 0.9); }
    if (instance >= 18) {
        size = instance == 18 ? float2(0.018, 0.003) : float2(0.002, 0.032);
        origin = -0.5 * size; color = 1;
    }
    Vertex v;
    v.position = float4(origin + corners[indices[id]] * size, 0, 1);
    v.color = color;
    return v;
}
float4 psMain(Vertex v):SV_Target0 { return v.color; }
)";

bool createPhase0Probe(InteropRenderer* r) {
    ID3DBlob* vs = nullptr;
    ID3DBlob* ps = nullptr;
    HRESULT result = D3DCompile(kPhase0Gui, sizeof(kPhase0Gui)-1, nullptr, nullptr, nullptr,
            "vsMain", "vs_5_1", D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, &vs, nullptr);
    if (SUCCEEDED(result)) result = D3DCompile(kPhase0Gui, sizeof(kPhase0Gui)-1, nullptr, nullptr, nullptr,
            "psMain", "ps_5_1", D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, &ps, nullptr);
    if (SUCCEEDED(result)) {
        auto desc = terrainPipelineDescription(r);
        desc.VS = {vs->GetBufferPointer(), vs->GetBufferSize()};
        desc.PS = {ps->GetBufferPointer(), ps->GetBufferSize()};
        desc.InputLayout = {};
        configureTranslucentState(desc);
        desc.DepthStencilState.DepthEnable = FALSE;
        desc.DepthStencilState.DepthWriteMask = D3D12_DEPTH_WRITE_MASK_ZERO;
        desc.DSVFormat = DXGI_FORMAT_UNKNOWN;
        desc.RasterizerState.CullMode = D3D12_CULL_MODE_NONE;
        result = r->device->CreateGraphicsPipelineState(&desc, IID_PPV_ARGS(&r->phase0Gui));
    }
    release(vs);
    release(ps);
    const D3D12_QUERY_HEAP_DESC queries = {D3D12_QUERY_HEAP_TYPE_TIMESTAMP, 4, 0};
    return SUCCEEDED(result)
            && SUCCEEDED(r->device->CreateQueryHeap(&queries, IID_PPV_ARGS(&r->phase0Queries)))
            && createBuffer(r, D3D12_HEAP_TYPE_READBACK, 4 * sizeof(UINT64), D3D12_RESOURCE_STATE_COPY_DEST,
                    &r->phase0Times)
            && SUCCEEDED(r->queue->GetTimestampFrequency(&r->timestampFrequency)) && r->timestampFrequency;
}

bool phase0Marker(InteropRenderer* r, ID3D12CommandAllocator* allocator,
        ID3D12GraphicsCommandList* list, UINT index) {
    if (FAILED(allocator->Reset()) || FAILED(list->Reset(allocator, nullptr))) return false;
    list->EndQuery(r->phase0Queries, D3D12_QUERY_TYPE_TIMESTAMP, index);
    return SUCCEEDED(list->Close());
}

bool recordPhase0Gui(InteropRenderer* r, FrameSlot& slot) {
    auto* list = r->finishList;
    if (FAILED(slot.finish->Reset()) || FAILED(list->Reset(slot.finish, r->phase0Gui))) return false;
    list->EndQuery(r->phase0Queries, D3D12_QUERY_TYPE_TIMESTAMP, 2);
    auto rtv = r->rtvHeap->GetCPUDescriptorHandleForHeapStart();
    list->OMSetRenderTargets(1, &rtv, FALSE, nullptr);
    list->SetGraphicsRootSignature(r->rootSignature);
    const D3D12_VIEWPORT viewport = {0, 0, static_cast<float>(r->width), static_cast<float>(r->height), 0, 1};
    const D3D12_RECT scissor = {0, 0, static_cast<LONG>(r->width), static_cast<LONG>(r->height)};
    list->RSSetViewports(1, &viewport);
    list->RSSetScissorRects(1, &scissor);
    list->IASetPrimitiveTopology(D3D_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    list->DrawInstanced(6, 20, 0, 0);
    list->EndQuery(r->phase0Queries, D3D12_QUERY_TYPE_TIMESTAMP, 3);
    list->ResolveQueryData(r->phase0Queries, D3D12_QUERY_TYPE_TIMESTAMP, 0, 4, r->phase0Times, 0);
    return SUCCEEDED(list->Close());
}
}

extern "C" JNIEXPORT void JNICALL Java_com_redondoguibi_direct_Phase0FrameNative_initialize(
        JNIEnv* env, jclass, jint width, jint height, jint uploadMiB, jint pageMiB, jint arenaMiB) {
    if (g_interopRenderer) {
        throwJava(env, "Phase 0 requires an isolated process with no active Direct renderer.");
        return;
    }
    auto* r = new InteropRenderer{};
    r->phase0Replay = true;
    if (!initializeInteropRenderer(r, width, height, false, uploadMiB, pageMiB, arenaMiB, env)
            || !createPhase0Probe(r)) {
        destroyInteropRenderer(r);
        throwJava(env, "Could not create the phase 0 GUI or GPU timestamps.");
        return;
    }
    g_interopRenderer = r;
}

extern "C" JNIEXPORT jdoubleArray JNICALL Java_com_redondoguibi_direct_Phase0FrameNative_frame(
        JNIEnv* env, jclass, jfloatArray terrain, jdouble x, jdouble y, jdouble z, jfloat distance,
        jfloatArray uniforms, jboolean indirect) {
    auto* r = g_interopRenderer;
    if (!r || !r->phase0Replay || !r->atlas || !r->lightmap || !r->packTerrainPipelines[0]
            || r->worldPasses.empty() || !r->extensionPasses.empty() || r->packSceneInputs
            || r->packShadows || r->geometrySettings[0] > 0 || r->graph.terrainDepth || r->arenaRejected
            || !terrain || env->GetArrayLength(terrain) != 28 || !uniforms
            || env->GetArrayLength(uniforms) != static_cast<jsize>(r->worldPasses.size() * kPassUniformFloatCount)) {
        throwJava(env, "Incomplete replay or unsupported extra shadow, terrain-depth or scene-input pass.");
        return nullptr;
    }
    float values[kMaxWorldPasses * kPassUniformFloatCount];
    env->GetFloatArrayRegion(terrain, 0, 28, r->viewProjection);
    env->GetFloatArrayRegion(uniforms, 0, env->GetArrayLength(uniforms), values);
    if (env->ExceptionCheck()) return nullptr;
    auto& slot = r->slots[r->slotIndex];
    // Every returned sample has completed its fence. Initial staging copies precede timestamp 0.
    if (!r->uploadOpen) slot.uploadOffset = 0;
    if (!submitUploads(r) || !prepareGeometry(r, slot, x, y, z, env)) {
        throwJava(env, "Phase 0 terrain staging failed.");
        return nullptr;
    }
    collectVisibleSections(r, x, y, z, distance);
    if (r->submittedCulledLayerIndices == 0 || r->visible.empty()) {
        throwJava(env, "Phase 0 rejected an empty terrain draw.");
        return nullptr;
    }
    prepareTerrainIndirectCommands(r, slot, true, indirect == JNI_TRUE);
    const UINT64 value = r->lastSignaledValue + 1;
    if (!phase0Marker(r, slot.bridgeBegin, r->bridgeBeginList, 0) || !recordTerrain(r, slot)
            || !phase0Marker(r, slot.bridgeEnd, r->bridgeEndList, 1)
            || !recordWorldGraph(r, slot, value-1, values, nullptr, 0, false) || !recordPhase0Gui(r, slot)) {
        throwJava(env, "Failed to record the phase 0 D3D12 frame.");
        return nullptr;
    }
    // These marker lists contain EndQuery only. All passes execute as one ordered D3D12 submission.
    ID3D12CommandList* lists[6] = {r->bridgeBeginList, r->opaqueList};
    UINT count = 2;
    if (r->visibleCutoutDraws) lists[count++] = r->cutoutList;
    lists[count++] = r->bridgeEndList;
    lists[count++] = r->extensionList;
    lists[count++] = r->finishList;
    r->queue->ExecuteCommandLists(count, lists);
    if (FAILED(r->queue->Signal(r->fence, value)) || !waitFence(r, r->fence, value)) {
        throwJava(env, "Phase 0 D3D12 frame failed to complete.");
        return nullptr;
    }
    slot.fenceValue = r->lastSignaledValue = value;
    if (graphHasHistory(r)) r->graph.parity ^= 1u;
    r->slotIndex = (r->slotIndex + 1) % kFramesInFlight;
    // Staging allocations retired while loading the frozen scene are now safe to free.
    for (auto*& resource : r->pendingRetire) release(resource);
    r->pendingRetire.clear();
    for (const auto& allocation : r->pendingRetireAllocations)
        freeArenaRange(allocation.page, allocation.offset, allocation.bytes);
    r->pendingRetireAllocations.clear();

    UINT64 ticks[4];
    const D3D12_RANGE range = {0, sizeof(ticks)}, noWrite = {0, 0};
    void* mapped = nullptr;
    if (FAILED(r->phase0Times->Map(0, &range, &mapped))) {
        throwJava(env, "Phase 0 timestamp readback failed.");
        return nullptr;
    }
    std::memcpy(ticks, mapped, sizeof(ticks));
    r->phase0Times->Unmap(0, &noWrite);
    if (ticks[3] <= ticks[0] || !std::is_sorted(std::begin(ticks), std::end(ticks))) {
        throwJava(env, "Phase 0 received invalid GPU timestamps.");
        return nullptr;
    }
    double ms = 1000.0 / r->timestampFrequency;
    const double sample[] = {(ticks[3]-ticks[0])*ms, (ticks[1]-ticks[0])*ms,
            (ticks[2]-ticks[1])*ms, (ticks[3]-ticks[2])*ms, static_cast<double>(r->visible.size()),
            static_cast<double>(r->submittedCulledLayerIndices),
            static_cast<double>(r->visibleOpaqueDraws + r->visibleCutoutDraws)};
    auto result = env->NewDoubleArray(ARRAYSIZE(sample));
    if (result) env->SetDoubleArrayRegion(result, 0, ARRAYSIZE(sample), sample);
    return result;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_redondoguibi_direct_Phase0FrameNative_coveredDepthPixels(
        JNIEnv* env, jclass) {
    auto* r = g_interopRenderer;
    if (!r || !r->phase0Replay || !r->lastSignaledValue) {
        throwJava(env, "Render a phase 0 frame before checking its depth.");
        return -1;
    }
    // Untimed, after the synchronous frame. Read the actual D32 raster result, including row pitch.
    auto desc = r->depthTarget->GetDesc();
    D3D12_PLACED_SUBRESOURCE_FOOTPRINT footprint{};
    UINT64 size = 0;
    r->device->GetCopyableFootprints(&desc, 0, 1, 0, &footprint, nullptr, nullptr, &size);
    ID3D12Resource* readback = nullptr;
    if (!createBuffer(r, D3D12_HEAP_TYPE_READBACK, size, D3D12_RESOURCE_STATE_COPY_DEST, &readback)) {
        throwJava(env, "Could not allocate the replay depth readback.");
        return -1;
    }
    auto* list = r->finishList;
    auto& slot = r->slots[r->slotIndex];
    if (FAILED(slot.finish->Reset()) || FAILED(list->Reset(slot.finish, nullptr))) {
        release(readback);
        throwJava(env, "Could not begin replay depth readback.");
        return -1;
    }
    auto toCopy = transition(r->depthTarget, D3D12_RESOURCE_STATE_DEPTH_WRITE, D3D12_RESOURCE_STATE_COPY_SOURCE);
    auto restore = transition(r->depthTarget, D3D12_RESOURCE_STATE_COPY_SOURCE, D3D12_RESOURCE_STATE_DEPTH_WRITE);
    D3D12_TEXTURE_COPY_LOCATION source{}, dest{};
    source.pResource = r->depthTarget;
    source.Type = D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX;
    dest.pResource = readback;
    dest.Type = D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;
    dest.PlacedFootprint = footprint;
    list->ResourceBarrier(1, &toCopy);
    list->CopyTextureRegion(&dest, 0, 0, 0, &source, nullptr);
    list->ResourceBarrier(1, &restore);
    if (FAILED(list->Close())) {
        release(readback);
        throwJava(env, "Could not record replay depth readback.");
        return -1;
    }
    ID3D12CommandList* lists[] = {list};
    r->queue->ExecuteCommandLists(1, lists);
    const UINT64 value = r->lastSignaledValue + 1;
    if (FAILED(r->queue->Signal(r->fence, value)) || !waitFence(r, r->fence, value)) {
        r->pendingRetire.push_back(readback);
        throwJava(env, "Replay depth copy did not complete.");
        return -1;
    }
    slot.fenceValue = r->lastSignaledValue = value;
    const D3D12_RANGE range = {0, static_cast<SIZE_T>(size)}, noWrite = {0, 0};
    void* data = nullptr;
    if (FAILED(readback->Map(0, &range, &data))) {
        release(readback);
        throwJava(env, "Replay depth readback mapping failed.");
        return -1;
    }
    jlong covered = 0;
    for (UINT y = 0; y < r->height; ++y) {
        const auto* row = reinterpret_cast<const float*>(static_cast<const uint8_t*>(data)
                + footprint.Offset + static_cast<SIZE_T>(y) * footprint.Footprint.RowPitch);
        for (UINT x = 0; x < r->width; ++x) covered += row[x] >= 0.0f && row[x] < 1.0f;
    }
    readback->Unmap(0, &noWrite);
    release(readback);
    return covered;
}
