#include <jni.h>
#include <openssl/curve25519.h>
#include <openssl/mem.h>
#include <array>
#include <cstdint>

extern "C" JNIEXPORT jlong JNICALL
Java_dev_buddy_pairing_NativeSpake2_create(JNIEnv *, jobject, jboolean client) {
    const uint8_t alice[] = "adb pair client";
    const uint8_t bob[] = "adb pair server";
    auto *ctx = SPAKE2_CTX_new(client ? spake2_role_alice : spake2_role_bob,
        client ? alice : bob, client ? sizeof(alice) : sizeof(bob),
        client ? bob : alice, client ? sizeof(bob) : sizeof(alice));
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_buddy_pairing_NativeSpake2_generate(JNIEnv *env, jobject, jlong handle, jbyteArray password) {
    if (handle == 0 || password == nullptr) return nullptr;
    const jsize length = env->GetArrayLength(password);
    if (length < 1 || length > 1024) return nullptr;
    std::array<uint8_t, 1024> secret{};
    env->GetByteArrayRegion(password, 0, length, reinterpret_cast<jbyte *>(secret.data()));
    if (env->ExceptionCheck()) return nullptr;
    std::array<uint8_t, SPAKE2_MAX_MSG_SIZE> message{};
    size_t size = 0;
    const int ok = SPAKE2_generate_msg(reinterpret_cast<SPAKE2_CTX *>(handle), message.data(), &size,
        message.size(), secret.data(), static_cast<size_t>(length));
    OPENSSL_cleanse(secret.data(), secret.size());
    if (ok != 1 || size != SPAKE2_MAX_MSG_SIZE) return nullptr;
    auto result = env->NewByteArray(static_cast<jsize>(size));
    if (result != nullptr) env->SetByteArrayRegion(result, 0, static_cast<jsize>(size), reinterpret_cast<jbyte *>(message.data()));
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_buddy_pairing_NativeSpake2_process__J_3B(JNIEnv *env, jobject, jlong handle, jbyteArray peer) {
    if (handle == 0 || peer == nullptr || env->GetArrayLength(peer) != SPAKE2_MAX_MSG_SIZE) return nullptr;
    std::array<uint8_t, SPAKE2_MAX_MSG_SIZE> input{};
    env->GetByteArrayRegion(peer, 0, input.size(), reinterpret_cast<jbyte *>(input.data()));
    if (env->ExceptionCheck()) return nullptr;
    std::array<uint8_t, SPAKE2_MAX_KEY_SIZE> key{};
    size_t size = 0;
    const int ok = SPAKE2_process_msg(reinterpret_cast<SPAKE2_CTX *>(handle), key.data(), &size,
        key.size(), input.data(), input.size());
    jbyteArray result = nullptr;
    if (ok == 1 && size == SPAKE2_MAX_KEY_SIZE) {
        result = env->NewByteArray(static_cast<jsize>(size));
        if (result != nullptr) env->SetByteArrayRegion(result, 0, static_cast<jsize>(size), reinterpret_cast<jbyte *>(key.data()));
    }
    OPENSSL_cleanse(key.data(), key.size());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_buddy_pairing_NativeSpake2_destroy(JNIEnv *, jobject, jlong handle) {
    SPAKE2_CTX_free(reinterpret_cast<SPAKE2_CTX *>(handle));
}
