package com.kmnexus.codexmeter.widget.overview

import com.kmnexus.codexmeter.widget.WidgetClickTarget
import com.kmnexus.codexmeter.widget.WidgetField
import com.kmnexus.codexmeter.widget.WidgetQuotaState
import com.kmnexus.codexmeter.widget.WidgetQuotaStatus
import com.kmnexus.codexmeter.widget.WidgetQuotaTone
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewRowTest {
    private val reset = Instant.parse("2026-11-17T10:40:00Z")

    private fun field(windowId: String, percent: Int?, tone: WidgetQuotaTone = WidgetQuotaTone.Success) =
        WidgetField(
            windowId = windowId,
            isBalance = false,
            percent = percent,
            balanceAmount = null,
            balanceCurrency = null,
            resetAt = reset,
            tone = tone,
            limitWindowSeconds = 18_000,
        )

    private fun state(
        status: WidgetQuotaStatus = WidgetQuotaStatus.Fresh,
        fields: List<WidgetField> = listOf(field("gemini_5h_window", 62), field("gemini_weekly_window", 88)),
        isUnconfigured: Boolean = false,
    ) = WidgetQuotaState(
        status = status,
        providerName = "Gemini",
        providerId = "gemini",
        localAccountId = "g1",
        accountName = "Gemini 主帳",
        tone = WidgetQuotaTone.Success,
        clickTarget = WidgetClickTarget.Home,
        fields = fields,
        isUnconfigured = isUnconfigured,
    )

    @Test
    fun `first two fields become main and secondary`() {
        val row = state().toOverviewRow()!!

        assertEquals("Gemini 主帳", row.accountName)
        assertEquals("gemini_5h_window", row.main!!.windowId)
        assertEquals(62, row.main!!.percent)
        assertEquals(reset.toEpochMilli(), row.main!!.resetAtEpochMillis)
        assertEquals("gemini_weekly_window", row.secondary!!.windowId)
        assertEquals(0.62f, row.main!!.progressFraction!!, 0.0001f)
    }

    @Test
    fun `account without data still gets a row so it is never silently hidden`() {
        val row = state(status = WidgetQuotaStatus.AuthRequired, fields = emptyList()).toOverviewRow()!!

        assertNull(row.main)
        assertTrue(row.needsAttention)
    }

    @Test
    fun `unconfigured state produces no row`() {
        assertNull(state(isUnconfigured = true).toOverviewRow())
    }

    @Test
    fun `balance and missing percent draw no progress bar`() {
        assertNull(field("x", null).let { state(fields = listOf(it)).toOverviewRow()!!.main!!.progressFraction })
        val balance = OverviewField("balance", true, null, "12.5", "USD", null, null, WidgetQuotaTone.Success)
        assertNull(balance.progressFraction)
    }

    @Test
    fun `rows survive the preferences round trip and bad data decodes to null`() {
        val rows = listOf(state().toOverviewRow()!!)

        assertEquals(rows, OverviewRowsCodec.decode(OverviewRowsCodec.encode(rows)))
        assertNull(OverviewRowsCodec.decode("not json"))
        assertNull(OverviewRowsCodec.decode(null))
    }

    @Test
    fun `row capacity follows widget height and font scale`() {
        // 列高 70dp（64dp 圓環＋6dp 間距）；約 270dp 高放得下 3 列。
        assertEquals(1, OverviewLayout.rowCapacity(60f))
        assertEquals(3, OverviewLayout.rowCapacity(270f))
        assertEquals(4, OverviewLayout.rowCapacity(350f))
        // 大字體：三行字比圓環高，列變高，至少一列。
        assertEquals(2, OverviewLayout.rowCapacity(270f, fontScale = 1.5f))
    }

    @Test
    fun `refreshing flag expires so the widget never gets stuck`() {
        val now = Instant.parse("2026-11-18T04:00:00Z")
        assertTrue(OverviewLayout.isRefreshing(now.minusSeconds(10).toEpochMilli(), now))
        assertFalse(OverviewLayout.isRefreshing(now.minusSeconds(600).toEpochMilli(), now))
        assertFalse(OverviewLayout.isRefreshing(null, now))
    }

    @Test
    fun `compact labels drop the quota suffix but keep generic names`() {
        assertEquals("5 小時", compactLabel("5 小時額度"))
        assertEquals("每月", compactLabel("每月额度"))
        assertEquals("7-day", compactLabel("7-day quota"))
        assertEquals("額度", compactLabel("額度"))
        assertEquals("Quota", compactLabel("Quota"))
    }

    @Test
    fun `same local day check uses the device zone`() {
        val taipei = ZoneId.of("Asia/Taipei")
        val now = Instant.parse("2026-11-17T09:00:00Z") // 台北 17:00
        assertTrue(isSameLocalDay(Instant.parse("2026-11-17T15:30:00Z"), now, taipei)) // 台北 23:30
        assertFalse(isSameLocalDay(Instant.parse("2026-11-17T16:30:00Z"), now, taipei)) // 台北隔天 00:30
    }
}
