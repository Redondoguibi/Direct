#pragma once

#include <cstdint>

namespace direct {

// Bytes needed to store a BC7 mip level (16 bytes per 4x4 block).
uint64_t bc7LevelSize(uint32_t width, uint32_t height);

// Encodes a tightly packed RGBA8 level as BC7 mode 6 blocks, spreading block rows across threads.
void encodeBc7Level(const uint8_t* rgba, uint32_t width, uint32_t height, uint8_t* blocks,
        uint32_t maxThreads);

} // namespace direct
