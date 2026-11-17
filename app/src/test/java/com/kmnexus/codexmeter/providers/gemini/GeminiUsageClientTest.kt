package com.kmnexus.codexmeter.providers.gemini

import com.kmnexus.codexmeter.domain.refresh.QuotaError
import com.kmnexus.codexmeter.providers.gemini.network.GeminiUsageClient
import com.kmnexus.codexmeter.providers.gemini.session.GeminiSessionPayload
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiUsageClientTest {
    private val server = MockWebServer()
    private val now = Instant.parse("2026-11-17T05:00:00Z")

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun client() = GeminiUsageClient(
        httpClient = GeminiUsageClient.noRedirectHttpClient(),
        clock = Clock.fixed(now, ZoneOffset.UTC),
        geminiBaseUrl = server.url("/").toString(),
        accountsBaseUrl = server.url("/").toString(),
    )

    private val usageBody = """
        )]}'
        [{"data":{"viewer":{"rpcs":{"getUsageInfo":{"subscriptionTier":"TIER1","pools":[
          {"poolType":"GXU_FIVE_HOURLY","percentageUsed":0.5}
        ]}}}}}]
    """.trimIndent()

    @Test
    fun `stale session rotates cookies first and sends the rotated value`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200)
                .addHeader("Set-Cookie", "__Secure-1PSIDTS=new; Domain=.google.com; Path=/; Secure")
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body(usageBody).build())

        val result = client().fetchUsage(GeminiSessionPayload("__Secure-1PSID=p; __Secure-1PSIDTS=old"))

        val rotate = server.takeRequest()
        assertEquals("POST", rotate.method)
        assertEquals("/RotateCookies", rotate.url.encodedPath)
        assertEquals("__Secure-1PSID=p; __Secure-1PSIDTS=old", rotate.headers["Cookie"])
        val usage = server.takeRequest()
        assertEquals("GET", usage.method)
        assertTrue(usage.url.encodedPath.endsWith("/GEMINI_WEB_GRAPHQL/executeQuery"))
        assertEquals("__Secure-1PSID=p; __Secure-1PSIDTS=new", usage.headers["Cookie"])

        val success = result as GeminiUsageClient.Result.Success
        assertEquals("__Secure-1PSID=p; __Secure-1PSIDTS=new", success.session.cookieHeader)
        assertEquals(now.toString(), success.session.rotatedAt)
        assertEquals(0.5, success.usage.pools!!.single().percentageUsed!!, 0.0)
    }

    @Test
    fun `recently rotated session skips rotation`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(usageBody).build())

        val session = GeminiSessionPayload("__Secure-1PSID=p", rotatedAt = now.minusSeconds(60).toString())
        val result = client().fetchUsage(session)

        assertTrue(result is GeminiUsageClient.Result.Success)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `failed rotation still tries the usage query with existing cookies`() = runTest {
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(MockResponse.Builder().code(200).body(usageBody).build())

        val result = client().fetchUsage(GeminiSessionPayload("__Secure-1PSID=p"))

        val success = result as GeminiUsageClient.Result.Success
        assertEquals(null, success.session.rotatedAt)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `http 401 requires login`() = runTest {
        assertAuthRequired(MockResponse.Builder().code(401).build())
    }

    @Test
    fun `redirect is never followed so cookies cannot leak to the redirect target`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(302)
                .addHeader("Location", server.url("/elsewhere").toString())
                .build(),
        )

        val result = client().fetchUsage(recentSession())

        assertTrue((result as GeminiUsageClient.Result.Failure).error is QuotaError.AuthRequired)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `signed out viewer requires login`() = runTest {
        assertAuthRequired(MockResponse.Builder().code(200).body("""[{"data":{"viewer":null}}]""").build())
    }

    @Test
    fun `server error is retryable network failure`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).build())

        val result = client().fetchUsage(recentSession())

        val error = (result as GeminiUsageClient.Result.Failure).error
        assertTrue(error is QuotaError.Network)
        assertTrue(error.retryable)
    }

    @Test
    fun `changed response shape is reported without guessing`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""[{"data":{}}]""").build())

        val result = client().fetchUsage(recentSession())

        val error = (result as GeminiUsageClient.Result.Failure).error
        assertEquals("gemini_response_changed", error.diagnosticsDigest)
    }

    private suspend fun assertAuthRequired(response: MockResponse) {
        server.enqueue(response)
        val result = client().fetchUsage(recentSession())
        assertTrue((result as GeminiUsageClient.Result.Failure).error is QuotaError.AuthRequired)
    }

    private fun recentSession() =
        GeminiSessionPayload("__Secure-1PSID=p", rotatedAt = now.minusSeconds(60).toString())
}
