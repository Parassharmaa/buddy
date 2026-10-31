package dev.buddy

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.buddy.adb.AdbIdentity
import java.security.Signature
import javax.crypto.Cipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdbIdentityDeviceTest {
    @Test fun nonExportableIdentitySupportsPlatformTlsSignatures() {
        val identity = AdbIdentity()
        try {
            val (key, certificate) = identity.load()
            assertNull(key.encoded)
            val encodedFixture = ByteArray(256) { 1 }.apply { this[0] = 0 }
            val signature = Cipher.getInstance("RSA/ECB/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, key)
                doFinal(encodedFixture)
            }
            val recovered = Cipher.getInstance("RSA/ECB/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, certificate.publicKey)
                doFinal(signature)
            }
            assertArrayEquals(encodedFixture, recovered)
            val message = "Buddy platform TLS signature fixture".toByteArray()
            for (algorithm in listOf("SHA256withRSA", "SHA256withRSA/PSS")) {
                val signed = Signature.getInstance(algorithm).run {
                    initSign(key)
                    update(message)
                    sign()
                }
                assertTrue(
                    Signature.getInstance(algorithm).run {
                        initVerify(certificate.publicKey)
                        update(message)
                        verify(signed)
                    }
                )
            }
        } finally {
            identity.forget()
        }
    }
}
