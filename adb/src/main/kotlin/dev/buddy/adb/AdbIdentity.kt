package dev.buddy.adb

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.security.auth.x500.X500Principal

class AdbIdentity {
    private val store by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    @Synchronized
    fun load(): Pair<PrivateKey, X509Certificate> {
        if (!store.containsAlias(ALIAS)) {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA,
                "AndroidKeyStore"
            )
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(
                        KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                        KeyProperties.SIGNATURE_PADDING_RSA_PSS
                    )
                    .setCertificateSubject(X500Principal("CN=Buddy"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .build()
            )
            generator.generateKeyPair()
        }
        return (store.getKey(ALIAS, null) as PrivateKey) to
            (store.getCertificate(ALIAS) as X509Certificate)
    }

    @Synchronized
    fun forget() {
        store.deleteEntry(ALIAS)
    }

    fun publicKeyWire(): ByteArray {
        val key = load().second.publicKey as RSAPublicKey
        val modulus = key.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(modulus.mod(r32).modInverse(r32)).toInt()
        fun littleEndian(value: BigInteger): ByteArray = value.toByteArray().reversedArray().copyOf(256)
        val encoded = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(64).putInt(n0inv)
            .put(littleEndian(modulus))
            .put(littleEndian(BigInteger.ONE.shiftLeft(4096).mod(modulus)))
            .putInt(key.publicExponent.toInt()).array()
        return (Base64.getEncoder().encodeToString(encoded) + " Buddy@android\u0000").toByteArray()
    }

    companion object {
        private const val ALIAS = "buddy.adb.identity.v1"
    }
}
