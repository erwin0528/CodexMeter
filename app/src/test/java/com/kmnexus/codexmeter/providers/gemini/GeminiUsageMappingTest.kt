package com.kmnexus.codexmeter.providers.gemini

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.providers.gemini.dto.GeminiUsageDecoder
import com.kmnexus.codexmeter.providers.gemini.mapper.GeminiUsageMapper
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiUsageMappingTest {
    private val now = Instant.parse("2026-11-17T05:00:00Z")

    // 回應格式取自 CodexBar GeminiWebPluginTests 的 fixture（數值為假資料）。
    private val proBody = """
        )]}'
        [{"data":{"viewer":{"rpcs":{"getUsageInfo":{"subscriptionTier":"TIER1","pools":[
          {"poolType":"GXU_FIVE_HOURLY","percentageUsed":0.24,
           "gxu":{"windowRefreshesAt":"2026-10-15T05:43:53.558200Z"}},
          {"poolType":"GXU_WEEKLY","percentageUsed":0.01786011,
           "gxu":{"windowRefreshesAt":"2026-10-21T04:43:53.558273Z"}}
        ]}}}}}]
    """.trimIndent()

    @Test
    fun `pro account maps five-hour and weekly pools`() {
        val decoded = GeminiUsageDecoder.decode(proBody)
        assertTrue(decoded is GeminiUsageDecoder.Decoded.Usage)

        val snapshot = map(decoded as GeminiUsageDecoder.Decoded.Usage)

        assertEquals("Google AI Pro", snapshot.planType)
        assertEquals(listOf("gemini_5h_window", "gemini_weekly_window"), snapshot.windows.map { it.windowId.value })
        val fiveHour = snapshot.windows[0]
        assertEquals(24, fiveHour.usedPercent)
        assertEquals(5 * 3600, fiveHour.limitWindowSeconds)
        assertEquals(Instant.parse("2026-10-15T05:43:53.558200Z"), fiveHour.resetAt)
        assertTrue(fiveHour.isPrimaryCandidate)
        val weekly = snapshot.windows[1]
        assertEquals(1, weekly.usedPercent)
        assertEquals(7 * 24 * 3600, weekly.limitWindowSeconds)
        assertEquals(QuotaWindowAvailability.Available, weekly.availability)
    }

    @Test
    fun `missing percentage and bad reset stay unavailable instead of guessed`() {
        val body = """
            [{"data":{"viewer":{"rpcs":{"getUsageInfo":{"subscriptionTier":"UNRECOGNIZED","pools":[
              {"poolType":"GXU_FIVE_HOURLY","percentageUsed":null},
              {"poolType":"GXU_WEEKLY","percentageUsed":0,"gxu":{"windowRefreshesAt":"unknown"}}
            ]}}}}}]
        """.trimIndent()

        val snapshot = map(GeminiUsageDecoder.decode(body) as GeminiUsageDecoder.Decoded.Usage)

        assertNull(snapshot.planType)
        assertEquals(QuotaWindowAvailability.Missing, snapshot.windows[0].availability)
        assertNull(snapshot.windows[0].usedPercent)
        // 回報 0% 就是 0%，不能被當成缺值。
        assertEquals(0, snapshot.windows[1].usedPercent)
        assertNull(snapshot.windows[1].resetAt)
    }

    @Test
    fun `fully used pool is depleted`() {
        val body = """
            [{"data":{"viewer":{"rpcs":{"getUsageInfo":{"pools":[
              {"poolType":"GXU_FIVE_HOURLY","percentageUsed":1.0}
            ]}}}}}]
        """.trimIndent()

        val snapshot = map(GeminiUsageDecoder.decode(body) as GeminiUsageDecoder.Decoded.Usage)

        assertEquals(100, snapshot.windows.single().usedPercent)
        assertEquals(QuotaWindowAvailability.Depleted, snapshot.windows.single().availability)
    }

    @Test
    fun `null viewer means signed out`() {
        assertEquals(
            GeminiUsageDecoder.Decoded.SignedOut,
            GeminiUsageDecoder.decode("""[{"data":{"viewer":null}}]"""),
        )
    }

    @Test
    fun `html login page means signed out`() {
        assertEquals(
            GeminiUsageDecoder.Decoded.SignedOut,
            GeminiUsageDecoder.decode("<!doctype html><html>Sign in</html>"),
        )
    }

    @Test
    fun `unexpected shape is reported as changed response`() {
        assertEquals(GeminiUsageDecoder.Decoded.Invalid, GeminiUsageDecoder.decode("""{"oops":1}"""))
        assertEquals(GeminiUsageDecoder.Decoded.Invalid, GeminiUsageDecoder.decode("""[{"data":{}}]"""))
        assertEquals(
            GeminiUsageDecoder.Decoded.Invalid,
            GeminiUsageDecoder.decode("""[{"data":{"viewer":{"rpcs":{}}}}]"""),
        )
    }

    private fun map(decoded: GeminiUsageDecoder.Decoded.Usage) = GeminiUsageMapper.map(
        usage = decoded.usage,
        localAccountId = LocalAccountId("gemini-1"),
        providerAccountId = null,
        fetchedAt = now,
        source = QuotaSnapshotSource.ManualRefresh,
    )
}
