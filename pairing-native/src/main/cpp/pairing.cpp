#include <jni.h>
#include <openssl/curve25519.h>
#include <openssl/mem.h>
#include <array>
#include <cstdint>
#include <limits>
#include <mutex>
#include <new>
#include <unordered_map>
#include <utility>

namespace {
enum class Phase { CREATED, GENERATED, CONSUMED, FAILED };
struct Context {
    bssl::UniquePtr<SPAKE2_CTX> value;
    Phase phase = Phase::CREATED;
    explicit Context(SPAKE2_CTX *context) : value(context) {}
};

// Tokens never expose pointers or get reused. Every use stays under the registry lock.
std::mutex registry_lock;
std::unordered_map<jlong, Context> contexts;
jlong next_token = 1;
constexpr size_t MAX_CONTEXTS = 128;

template<size_t N> struct Secret {
    std::array<uint8_t, N> bytes{};
    ~Secret() { OPENSSL_cleanse(bytes.data(), bytes.size()); }
};

void fail(JNIEnv *env, const char *type, const char *message) {
    if (env->ExceptionCheck()) return;
    jclass exception = env->FindClass(type);
    if (exception != nullptr) {
        env->ThrowNew(exception, message);
        env->DeleteLocalRef(exception);
    }
}

template<typename Result, typename Operation>
Result guarded(JNIEnv *env, Result fallback, Operation operation) noexcept {
    try {
        return operation();
    } catch (const std::bad_alloc &) {
        fail(env, "java/lang/OutOfMemoryError", "Pairing allocation failed");
    } catch (...) {
        fail(env, "java/lang/IllegalStateException", "Pairing operation failed");
    }
    return fallback;
}

Context *find(JNIEnv *env, jlong token, Phase phase) {
    const auto found = contexts.find(token);
    if (found == contexts.end() || found->second.phase != phase) {
        fail(env, "java/lang/IllegalStateException", "Pairing context is unavailable or already used");
        return nullptr;
    }
    return &found->second;
}

jbyteArray copy(JNIEnv *env, const uint8_t *bytes, size_t size) {
    auto result = env->NewByteArray(static_cast<jsize>(size));
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte *>(bytes));
    }
    return result;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_buddy_pairing_NativeSpake2_create(JNIEnv *env, jobject, jboolean client) {
    return guarded<jlong>(env, 0, [&]() -> jlong {
        std::lock_guard<std::mutex> lock(registry_lock);
        if (contexts.size() >= MAX_CONTEXTS || next_token == std::numeric_limits<jlong>::max()) {
            fail(env, "java/lang/IllegalStateException", "Pairing context limit reached");
            return 0;
        }
        const uint8_t alice[] = "adb pair client";
        const uint8_t bob[] = "adb pair server";
        Context context(SPAKE2_CTX_new(client ? spake2_role_alice : spake2_role_bob,
            client ? alice : bob, client ? sizeof(alice) : sizeof(bob),
            client ? bob : alice, client ? sizeof(bob) : sizeof(alice)));
        if (!context.value) {
            fail(env, "java/lang/IllegalStateException", "Pairing context unavailable");
            return 0;
        }
        const jlong token = next_token++;
        contexts.emplace(token, std::move(context));
        return token;
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_buddy_pairing_NativeSpake2_generate(JNIEnv *env, jobject, jlong token, jbyteArray password) {
    return guarded<jbyteArray>(env, nullptr, [&]() -> jbyteArray {
        std::lock_guard<std::mutex> lock(registry_lock);
        auto *context = find(env, token, Phase::CREATED);
        if (context == nullptr) return nullptr;
        const jsize length = password == nullptr ? 0 : env->GetArrayLength(password);
        if (length < 1 || length > 1024) {
            fail(env, "java/lang/IllegalArgumentException", "Invalid pairing secret size");
            return nullptr;
        }
        Secret<1024> secret;
        env->GetByteArrayRegion(password, 0, length, reinterpret_cast<jbyte *>(secret.bytes.data()));
        if (env->ExceptionCheck()) return nullptr;
        std::array<uint8_t, SPAKE2_MAX_MSG_SIZE> message{};
        size_t size = 0;
        context->phase = Phase::FAILED;
        const int ok = SPAKE2_generate_msg(context->value.get(), message.data(), &size,
            message.size(), secret.bytes.data(), static_cast<size_t>(length));
        if (ok != 1 || size != SPAKE2_MAX_MSG_SIZE) {
            fail(env, "java/lang/IllegalStateException", "Pairing message failed");
            return nullptr;
        }
        context->phase = Phase::GENERATED;
        return copy(env, message.data(), size);
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_buddy_pairing_NativeSpake2_process__J_3B(JNIEnv *env, jobject, jlong token, jbyteArray peer) {
    return guarded<jbyteArray>(env, nullptr, [&]() -> jbyteArray {
        std::lock_guard<std::mutex> lock(registry_lock);
        auto *context = find(env, token, Phase::GENERATED);
        if (context == nullptr) return nullptr;
        if (peer == nullptr || env->GetArrayLength(peer) != SPAKE2_MAX_MSG_SIZE) {
            fail(env, "java/lang/IllegalArgumentException", "Invalid pairing peer size");
            return nullptr;
        }
        std::array<uint8_t, SPAKE2_MAX_MSG_SIZE> input{};
        env->GetByteArrayRegion(peer, 0, input.size(), reinterpret_cast<jbyte *>(input.data()));
        if (env->ExceptionCheck()) return nullptr;
        Secret<SPAKE2_MAX_KEY_SIZE> key;
        size_t size = 0;
        context->phase = Phase::CONSUMED;
        const int ok = SPAKE2_process_msg(context->value.get(), key.bytes.data(), &size,
            key.bytes.size(), input.data(), input.size());
        if (ok != 1 || size != SPAKE2_MAX_KEY_SIZE) {
            fail(env, "java/lang/IllegalStateException", "Pairing exchange failed");
            return nullptr;
        }
        return copy(env, key.bytes.data(), size);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_dev_buddy_pairing_NativeSpake2_destroy(JNIEnv *env, jobject, jlong token) {
    guarded<int>(env, 0, [&]() {
        std::lock_guard<std::mutex> lock(registry_lock);
        contexts.erase(token);
        return 0;
    });
}
