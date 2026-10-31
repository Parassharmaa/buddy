#include <openssl/curve25519.h>
#include <openssl/mem.h>
#include <array>
#include <cstdio>
#include <cstdlib>

#define CHECK(condition) do { if (!(condition)) { std::fprintf(stderr, "SPAKE2 test failed at line %d\n", __LINE__); std::abort(); } } while (0)

static void exchange(bool wrong_password) {
    const uint8_t client[] = "adb pair client";
    const uint8_t server[] = "adb pair server";
    const uint8_t password[] = "synthetic-test-only";
    const uint8_t wrong[] = "different-test-password";
    bssl::UniquePtr<SPAKE2_CTX> alice(SPAKE2_CTX_new(spake2_role_alice, client, sizeof(client), server, sizeof(server)));
    bssl::UniquePtr<SPAKE2_CTX> bob(SPAKE2_CTX_new(spake2_role_bob, server, sizeof(server), client, sizeof(client)));
    CHECK(alice && bob);
    std::array<uint8_t, SPAKE2_MAX_MSG_SIZE> a{}, b{};
    size_t a_len = 0, b_len = 0;
    CHECK(SPAKE2_generate_msg(alice.get(), a.data(), &a_len, a.size(), password, sizeof(password)) == 1);
    CHECK(SPAKE2_generate_msg(bob.get(), b.data(), &b_len, b.size(), wrong_password ? wrong : password, wrong_password ? sizeof(wrong) : sizeof(password)) == 1);
    CHECK(a_len == 32 && b_len == 32);
    CHECK(a != b);
    CHECK(SPAKE2_generate_msg(alice.get(), a.data(), &a_len, a.size(), password, sizeof(password)) == 0);
    std::array<uint8_t, SPAKE2_MAX_KEY_SIZE> ak{}, bk{};
    size_t ak_len = 0, bk_len = 0;
    CHECK(SPAKE2_process_msg(alice.get(), ak.data(), &ak_len, ak.size(), b.data(), b_len) == 1);
    CHECK(SPAKE2_process_msg(bob.get(), bk.data(), &bk_len, bk.size(), a.data(), a_len) == 1);
    CHECK(ak_len == 64 && bk_len == 64);
    CHECK((CRYPTO_memcmp(ak.data(), bk.data(), ak_len) == 0) != wrong_password);
    CHECK(SPAKE2_process_msg(alice.get(), ak.data(), &ak_len, ak.size(), b.data(), b_len) == 0);
    OPENSSL_cleanse(ak.data(), ak.size()); OPENSSL_cleanse(bk.data(), bk.size());
}

int main() {
    for (int i = 0; i < 32; ++i) { exchange(false); exchange(true); }
    for (size_t length : {0U, 1U, 31U, 33U, 64U}) {
        const uint8_t name[] = "test";
        bssl::UniquePtr<SPAKE2_CTX> ctx(SPAKE2_CTX_new(spake2_role_alice, name, sizeof(name), name, sizeof(name)));
        std::array<uint8_t, 64> data{};
        size_t size = 0;
        CHECK(SPAKE2_generate_msg(ctx.get(), data.data(), &size, 32, name, sizeof(name)) == 1);
        CHECK(SPAKE2_process_msg(ctx.get(), data.data(), &size, data.size(), data.data(), length) == 0);
    }
    std::puts("SPAKE2: matching passwords, mismatch, state reuse and truncated peers passed");
}
