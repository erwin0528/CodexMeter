package com.kmnexus.codexmeter.widget.overview

import com.kmnexus.codexmeter.widget.WidgetQuotaStatus
import com.kmnexus.codexmeter.widget.WidgetQuotaTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiPoolTest {
    private fun row(
        provider: String,
        percent: Int?,
        reset: Long?,
        status: WidgetQuotaStatus = WidgetQuotaStatus.Fresh,
    ) =
        OverviewRow(
            providerId = provider,
            accountName = provider,
            status = status,
            tone = WidgetQuotaTone.Success,
            main = OverviewField("w", false, percent, null, null, reset, 18_000, WidgetQuotaTone.Success),
            secondary = null,
        )

    @Test
    fun `two gemini accounts are summed with the earliest refill`() {
        val pool = geminiPool(listOf(row("gemini", 100, 2_000), row("gemini", 90, 1_000), row("claude", 50, 500)))!!
        assertEquals(190, pool.totalPercent)
        assertEquals(2, pool.accountCount)
        assertEquals(1_000L, pool.nextRefillEpochMillis)
    }

    @Test
    fun `single or signed out gemini gives no pool`() {
        assertNull(geminiPool(listOf(row("gemini", 100, 1_000), row("claude", 80, 1_000))))
        assertNull(
            geminiPool(listOf(row("gemini", 100, 1_000), row("gemini", 80, 1_000, WidgetQuotaStatus.AuthRequired))),
        )
    }

    @Test
    fun `short window codes`() {
        assertEquals("7d", shortWindowCode(604_800))
        assertEquals("5h", shortWindowCode(18_000))
        assertNull(shortWindowCode(2_592_000))
    }

    @Test
    fun `summary line only when every row fits with spare room`() {
        // 標題 50＋內距 20＋3 列 × 70 = 280dp
        assertTrue(OverviewLayout.hasRoomForSummary(310f, rowCount = 3))
        assertFalse(OverviewLayout.hasRoomForSummary(290f, rowCount = 3))
    }
}
