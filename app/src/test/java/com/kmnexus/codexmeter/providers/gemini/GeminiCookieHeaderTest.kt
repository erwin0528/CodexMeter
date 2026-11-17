package com.kmnexus.codexmeter.providers.gemini

import com.kmnexus.codexmeter.providers.gemini.session.GeminiCookieHeader
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiCookieHeaderTest {
    @Test
    fun `rotated cookie replaces the stored value and keeps the rest in order`() {
        val merged = GeminiCookieHeader.merge(
            header = "SID=a; __Secure-1PSID=b; __Secure-1PSIDTS=old",
            setCookieHeaders = listOf("__Secure-1PSIDTS=new; Domain=.google.com; Path=/; Secure; HttpOnly"),
        )

        assertEquals("SID=a; __Secure-1PSID=b; __Secure-1PSIDTS=new", merged)
    }

    @Test
    fun `new cookie is appended`() {
        val merged = GeminiCookieHeader.merge(
            header = "SID=a",
            setCookieHeaders = listOf("NID=n; Path=/"),
        )

        assertEquals("SID=a; NID=n", merged)
    }

    @Test
    fun `expired or emptied cookie is removed`() {
        val merged = GeminiCookieHeader.merge(
            header = "SID=a; OLD=x; GONE=y",
            setCookieHeaders = listOf("OLD=x; Max-Age=0; Path=/", "GONE=; Path=/"),
        )

        assertEquals("SID=a", merged)
    }

    @Test
    fun `no set-cookie leaves header unchanged`() {
        assertEquals("SID=a; NID=n", GeminiCookieHeader.merge("SID=a; NID=n", emptyList()))
    }

    @Test
    fun `cookie value containing equals sign is preserved`() {
        val merged = GeminiCookieHeader.merge(
            header = "SID=a",
            setCookieHeaders = listOf("__Secure-1PSIDTS=sidts-abc==; Path=/"),
        )

        assertEquals("SID=a; __Secure-1PSIDTS=sidts-abc==", merged)
    }

    @Test
    fun `signed-in check needs the long-lived session cookie`() {
        assertEquals(true, GeminiCookieHeader.hasSignedInSession("NID=n; __Secure-1PSID=b"))
        assertEquals(false, GeminiCookieHeader.hasSignedInSession("NID=n; AEC=x"))
    }
}
