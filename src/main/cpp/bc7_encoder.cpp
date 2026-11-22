#include "bc7_encoder.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <system_error>
#include <thread>
#include <vector>

namespace direct {
namespace {

constexpr int kWeights[16] = {0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64};
// Alpha pesa mais para preservar as bordas de cutout (alpha 0/255).
constexpr float kChannelWeight[4] = {1.0f, 1.0f, 1.0f, 4.0f};

struct Endpoint {
    uint8_t quantized[4]; // 7 bits por canal
    uint8_t pbit;
    int value[4];         // valor reconstruído de 8 bits
};

Endpoint quantizeEndpoint(const float* color) {
    Endpoint best = {};
    float bestError = 1e30f;
    for (uint8_t p = 0; p < 2; ++p) {
        Endpoint candidate = {};
        candidate.pbit = p;
        float error = 0.0f;
        for (int c = 0; c < 4; ++c) {
            const int q = std::clamp(static_cast<int>(std::lround((color[c] - p) * 0.5f)), 0, 127);
            candidate.quantized[c] = static_cast<uint8_t>(q);
            candidate.value[c] = (q << 1) | p;
            const float d = candidate.value[c] - color[c];
            error += d * d * kChannelWeight[c];
        }
        if (error < bestError) {
            bestError = error;
            best = candidate;
        }
    }
    return best;
}

struct BitWriter {
    uint8_t* out;
    unsigned bit;
    void put(uint32_t value, unsigned count) {
        for (unsigned i = 0; i < count; ++i, ++bit) {
            if ((value >> i) & 1u) {
                out[bit >> 3] |= static_cast<uint8_t>(1u << (bit & 7));
            }
        }
    }
};

float encodeWithEndpoints(const uint8_t (*pixels)[4], const float* low, const float* high, uint8_t* out) {
    Endpoint e[2] = {quantizeEndpoint(low), quantizeEndpoint(high)};
    int palette[16][4];
    for (int i = 0; i < 16; ++i) {
        for (int c = 0; c < 4; ++c) {
            palette[i][c] = ((64 - kWeights[i]) * e[0].value[c] + kWeights[i] * e[1].value[c] + 32) >> 6;
        }
    }
    uint8_t indices[16];
    float total = 0.0f;
    for (int p = 0; p < 16; ++p) {
        float bestError = 1e30f;
        for (int i = 0; i < 16; ++i) {
            float error = 0.0f;
            for (int c = 0; c < 4; ++c) {
                const float d = static_cast<float>(palette[i][c] - pixels[p][c]);
                error += d * d * kChannelWeight[c];
            }
            if (error < bestError) {
                bestError = error;
                indices[p] = static_cast<uint8_t>(i);
            }
        }
        total += bestError;
    }
    if (indices[0] & 8) { // o índice âncora tem só 3 bits
        std::swap(e[0], e[1]);
        for (uint8_t& index : indices) {
            index = static_cast<uint8_t>(15 - index);
        }
    }
    std::memset(out, 0, 16);
    BitWriter writer = {out, 0};
    writer.put(1u << 6, 7);
    for (int c = 0; c < 4; ++c) {
        writer.put(e[0].quantized[c], 7);
        writer.put(e[1].quantized[c], 7);
    }
    writer.put(e[0].pbit, 1);
    writer.put(e[1].pbit, 1);
    writer.put(indices[0], 3);
    for (int p = 1; p < 16; ++p) {
        writer.put(indices[p], 4);
    }
    return total;
}

void encodeBlock(const uint8_t (*pixels)[4], uint8_t* out) {
    float mean[4] = {};
    float low[4] = {255, 255, 255, 255};
    float high[4] = {0, 0, 0, 0};
    for (int p = 0; p < 16; ++p) {
        for (int c = 0; c < 4; ++c) {
            mean[c] += pixels[p][c] / 16.0f;
            low[c] = std::min(low[c], static_cast<float>(pixels[p][c]));
            high[c] = std::max(high[c], static_cast<float>(pixels[p][c]));
        }
    }
    float covariance[4][4] = {};
    for (int p = 0; p < 16; ++p) {
        float d[4];
        for (int c = 0; c < 4; ++c) {
            d[c] = pixels[p][c] - mean[c];
        }
        for (int r = 0; r < 4; ++r) {
            for (int c = 0; c < 4; ++c) {
                covariance[r][c] += d[r] * d[c];
            }
        }
    }
    float axis[4] = {high[0] - low[0], high[1] - low[1], high[2] - low[2], high[3] - low[3]};
    for (int iteration = 0; iteration < 8; ++iteration) {
        float next[4] = {};
        for (int r = 0; r < 4; ++r) {
            for (int c = 0; c < 4; ++c) {
                next[r] += covariance[r][c] * axis[c];
            }
        }
        const float length = std::sqrt(next[0] * next[0] + next[1] * next[1] + next[2] * next[2] + next[3] * next[3]);
        if (length < 1e-6f) {
            break;
        }
        for (int c = 0; c < 4; ++c) {
            axis[c] = next[c] / length;
        }
    }
    float minT = 0.0f;
    float maxT = 0.0f;
    for (int p = 0; p < 16; ++p) {
        float t = 0.0f;
        for (int c = 0; c < 4; ++c) {
            t += (pixels[p][c] - mean[c]) * axis[c];
        }
        minT = std::min(minT, t);
        maxT = std::max(maxT, t);
    }
    float pcaLow[4];
    float pcaHigh[4];
    for (int c = 0; c < 4; ++c) {
        pcaLow[c] = std::clamp(mean[c] + axis[c] * minT, 0.0f, 255.0f);
        pcaHigh[c] = std::clamp(mean[c] + axis[c] * maxT, 0.0f, 255.0f);
    }
    uint8_t candidate[16];
    const float pcaError = encodeWithEndpoints(pixels, pcaLow, pcaHigh, out);
    if (encodeWithEndpoints(pixels, low, high, candidate) < pcaError) {
        std::memcpy(out, candidate, 16);
    }
}

void encodeRows(const uint8_t* rgba, uint32_t width, uint32_t height, uint8_t* blocks,
        uint32_t firstRow, uint32_t rowStep) {
    const uint32_t blocksWide = (width + 3) / 4;
    const uint32_t blocksHigh = (height + 3) / 4;
    uint8_t pixels[16][4];
    for (uint32_t by = firstRow; by < blocksHigh; by += rowStep) {
        for (uint32_t bx = 0; bx < blocksWide; ++bx) {
            for (uint32_t p = 0; p < 16; ++p) {
                const uint32_t x = std::min(bx * 4 + p % 4, width - 1);
                const uint32_t y = std::min(by * 4 + p / 4, height - 1);
                std::memcpy(pixels[p], rgba + (static_cast<uint64_t>(y) * width + x) * 4, 4);
            }
            encodeBlock(pixels, blocks + (static_cast<uint64_t>(by) * blocksWide + bx) * 16);
        }
    }
}

} // namespace

uint64_t bc7LevelSize(uint32_t width, uint32_t height) {
    return static_cast<uint64_t>((width + 3) / 4) * ((height + 3) / 4) * 16;
}

void encodeBc7Level(const uint8_t* rgba, uint32_t width, uint32_t height, uint8_t* blocks,
        uint32_t maxThreads) {
    const uint32_t blocksHigh = (height + 3) / 4;
    const uint32_t hardwareThreads = std::max(1u, std::thread::hardware_concurrency());
    const uint32_t availableThreads = std::min(hardwareThreads, std::max(1u, blocksHigh));
    const uint32_t threadCount = std::clamp(maxThreads, 1u, availableThreads);
    std::vector<std::thread> threads;
    uint32_t started = 1;
    try {
        for (; started < threadCount; ++started) {
            threads.emplace_back(encodeRows, rgba, width, height, blocks, started, threadCount);
        }
    } catch (const std::system_error&) {
        // Sem threads extras: as linhas restantes são codificadas abaixo.
    }
    encodeRows(rgba, width, height, blocks, 0, threadCount);
    for (auto& thread : threads) {
        thread.join();
    }
    for (uint32_t row = started; row < threadCount; ++row) {
        encodeRows(rgba, width, height, blocks, row, threadCount);
    }
}

} // namespace direct
