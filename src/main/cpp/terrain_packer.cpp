// Native port of TerrainBufferPacker.packVertices20: converts one vanilla BLOCK-format layer into
// Direct's 20-byte V2 vertices on the chunk-meshing worker threads. One JNI call per layer covers
// thousands of vertices, so the crossing is amortized. Output must be byte-identical to the Java
// implementation (verified by NativeTerrainPackerTest), hence the Java-exact rounding, NaN and
// signed-zero rules below and the disabled floating-point contraction.
#include <jni.h>

#include <cmath>
#include <cstdint>
#include <cstring>

#pragma fp_contract(off)
#pragma float_control(precise, on)

namespace {

constexpr int kOutputStride = 20;

float readFloat(const uint8_t* at) { float value; std::memcpy(&value, at, sizeof(value)); return value; }
int16_t readShort(const uint8_t* at) { int16_t value; std::memcpy(&value, at, sizeof(value)); return value; }
void writeShort(uint8_t* at, uint16_t value) { std::memcpy(at, &value, sizeof(value)); }

// java.lang.Math.round(float): closest int, ties toward +infinity, NaN -> 0, saturating.
// x + 0.5 is exact in double for every float that can round inside the int range.
// floor() is a library call under plain SSE2, so floor is done as truncation plus a correction.
int32_t javaRound(float value) {
    const double shifted = static_cast<double>(value) + 0.5;
    if (!(shifted > -2147483648.0 && shifted < 2147483648.0)) { // also catches NaN
        if (shifted != shifted) return 0;
        return shifted < 0.0 ? INT32_MIN : INT32_MAX;
    }
    int32_t truncated = static_cast<int32_t>(shifted); // toward zero
    if (static_cast<double>(truncated) > shifted) --truncated; // floor for negative non-integers
    return truncated;
}

// java.lang.Math.min(float, float): NaN-propagating, -0.0 < +0.0.
float javaMin(float a, float b) {
    if (std::isnan(a)) return a;
    if (a == 0.0f && b == 0.0f && std::signbit(b)) return b;
    return a <= b ? a : b;
}

uint16_t packPosition(float value) {
    const int32_t packed = javaRound((value - -16.0f) * 1024.0f);
    return static_cast<uint16_t>(packed < 0 ? 0 : packed > 0xFFFF ? 0xFFFF : packed);
}

// Math.round(Math.max(0f, Math.min(1f, value)) * 0xFFFF); NaN stays NaN and rounds to 0.
uint16_t packUnorm16(float value) {
    if (!std::isnan(value)) {
        if (value > 1.0f) value = 1.0f;
        if (value < 0.0f || (value == 0.0f && std::signbit(value))) value = 0.0f;
    }
    return static_cast<uint16_t>(javaRound(value * 65535.0f));
}

int clampLight(int16_t value) { return value < 0 ? 0 : value > 255 ? 255 : value; }

void packLayer(uint8_t* out, const uint8_t* in, int count, int stride, int position, int color, int uv,
        int light, int normal, const int8_t* materials) {
    float minY = 0.0f;
    bool waterTop = false;
    for (int i = 0; i < count; ++i) {
        const uint8_t* vertex = in + static_cast<size_t>(i) * stride;
        const int8_t quadMaterial = materials[i / 4];
        if (i % 4 == 0) {
            minY = INFINITY;
            if (quadMaterial == 5 || quadMaterial == 8) {
                const int end = i + 4 < count ? i + 4 : count;
                for (int v = i; v < end; ++v) minY = javaMin(minY, readFloat(in + static_cast<size_t>(v) * stride + position + 4));
            }
            waterTop = false;
            if (quadMaterial == 5 && i + 2 < count) {
                const uint8_t* a = vertex + position;
                const uint8_t* b = a + stride;
                const uint8_t* c = b + stride;
                const float ax = readFloat(b) - readFloat(a), ay = readFloat(b + 4) - readFloat(a + 4), az = readFloat(b + 8) - readFloat(a + 8);
                const float bx = readFloat(c) - readFloat(a), by = readFloat(c + 4) - readFloat(a + 4), bz = readFloat(c + 8) - readFloat(a + 8);
                const float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
                waterTop = ny > 0.0f && ny * ny > (nx * nx + ny * ny + nz * nz) * 0.5f;
            }
        }
        writeShort(out + 0, packPosition(readFloat(vertex + position)));
        writeShort(out + 2, packPosition(readFloat(vertex + position + 4)));
        writeShort(out + 4, packPosition(readFloat(vertex + position + 8)));
        writeShort(out + 6, static_cast<uint16_t>(clampLight(readShort(vertex + light)) | clampLight(readShort(vertex + light + 2)) << 8));
        std::memcpy(out + 8, vertex + color, 4);
        writeShort(out + 12, packUnorm16(readFloat(vertex + uv)));
        writeShort(out + 14, packUnorm16(readFloat(vertex + uv + 4)));
        int8_t material = quadMaterial;
        const bool elevated = (material == 5 || material == 8) && readFloat(vertex + position + 4) > minY + 0.05f;
        if ((material == 8 && elevated) || (material == 5 && (waterTop || elevated))) material = static_cast<int8_t>(material | 0x80);
        out[16] = vertex[normal];
        out[17] = vertex[normal + 1];
        out[18] = vertex[normal + 2];
        out[19] = static_cast<uint8_t>(material);
        out += kOutputStride;
    }
}

void throwIllegalArgument(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalArgumentException");
    if (type != nullptr) env->ThrowNew(type, message);
}

} // namespace

/** Java validated the ranges; they are checked again against the real capacities here. */
extern "C" JNIEXPORT void JNICALL
Java_com_redondoguibi_direct_NativeDx12_packTerrainVertices20(JNIEnv* env, jclass, jobject destination,
        jint destinationOffset, jobject source, jint sourceOffset, jint count, jint stride, jint position,
        jint color, jint uv, jint light, jint normal, jbyteArray materials) {
    auto* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(destination));
    const auto* in = static_cast<const uint8_t*>(env->GetDirectBufferAddress(source));
    const jlong outCapacity = env->GetDirectBufferCapacity(destination);
    const jlong inCapacity = env->GetDirectBufferCapacity(source);
    if (out == nullptr || in == nullptr || materials == nullptr || count < 0 || stride < 1
            || destinationOffset < 0 || sourceOffset < 0 || position < 0 || color < 0 || uv < 0 || light < 0 || normal < 0
            || static_cast<jlong>(position) + 12 > stride || static_cast<jlong>(color) + 4 > stride
            || static_cast<jlong>(uv) + 8 > stride || static_cast<jlong>(light) + 4 > stride || static_cast<jlong>(normal) + 3 > stride
            || sourceOffset + static_cast<jlong>(count) * stride > inCapacity
            || destinationOffset + static_cast<jlong>(count) * kOutputStride > outCapacity
            || env->GetArrayLength(materials) < (static_cast<jlong>(count) + 3) / 4) {
        throwIllegalArgument(env, "Invalid native V2 vertex range");
        return;
    }
    if (count == 0) return;
    // Short, allocation-free critical section: no JNI calls or blocking inside.
    auto* quadMaterials = static_cast<const int8_t*>(env->GetPrimitiveArrayCritical(materials, nullptr));
    if (quadMaterials == nullptr) return; // OutOfMemoryError pending
    packLayer(out + destinationOffset, in + sourceOffset, count, stride, position, color, uv, light, normal, quadMaterials);
    env->ReleasePrimitiveArrayCritical(materials, const_cast<int8_t*>(quadMaterials), JNI_ABORT);
}
