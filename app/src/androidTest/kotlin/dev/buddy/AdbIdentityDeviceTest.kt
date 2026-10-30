package dev.buddy

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.buddy.adb.AdbIdentity
import java.security.PrivateKey
import javax.crypto.Cipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdbIdentityDeviceTest {
    @Test fun nonExportableIdentitySupportsConscryptTlsSigningCallback() {
        val identity = AdbIdentity()
        try {
            val (key, certificate) = identity.load()
            assertNull(key.encoded)
            // Conscrypt 2.8 delegates pre-encoded TLS RSA signatures through this exact
            // callback (BoringSSL RSA_NO_PADDING = 3). Test availability independently of Wi-Fi.
            val callback = Class.forName("org.conscrypt.CryptoUpcalls").getDeclaredMethod(
                "rsaSignDigestWithPrivateKey",
                PrivateKey::class.java,
                Int::class.javaPrimitiveType,
                ByteArray::class.java
            ).apply { isAccessible = true }
            val encodedFixture = ByteArray(256) { 1 }.apply { this[0] = 0 }
            val signature = callback.invoke(null, key, 3, encodedFixture) as ByteArray?
            assertNotNull("Keystore identity cannot perform the required TLS signing operation", signature)
            val recovered = Cipher.getInstance("RSA/ECB/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, certificate.publicKey)
                doFinal(requireNotNull(signature))
            }
            assertArrayEquals(encodedFixture, recovered)
        } finally {
            identity.forget()
        }
    }
}
