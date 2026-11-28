package com.kmnexus.codexmeter.providers.gemini

import com.kmnexus.codexmeter.data.secure.ProviderSessionEnvelope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiSessionWriteGuardTest {
    private fun envelope(cipher: String, nonce: String) = ProviderSessionEnvelope(
        providerId = "gemini",
        localAccountId = "acc-1",
        providerAccountId = null,
        schemaVersion = 1,
        payloadCiphertext = cipher.encodeToByteArray(),
        payloadNonce = nonce.encodeToByteArray(),
        createdAt = "2026-11-17T00:00:00Z",
        updatedAt = "2026-11-17T00:00:00Z",
    )

    @Test
    fun `unchanged session allows write back`() {
        // 內容相同但是不同物件：要比內容，不能比參考
        assertTrue(GeminiRefreshProvider.isStillCurrent(envelope("a", "n1"), envelope("a", "n1")))
    }

    @Test
    fun `re login during refresh blocks stale write back`() {
        assertFalse(GeminiRefreshProvider.isStillCurrent(envelope("a", "n1"), envelope("b", "n2")))
    }

    @Test
    fun `deleted account blocks write back`() {
        assertFalse(GeminiRefreshProvider.isStillCurrent(envelope("a", "n1"), null))
    }
}
