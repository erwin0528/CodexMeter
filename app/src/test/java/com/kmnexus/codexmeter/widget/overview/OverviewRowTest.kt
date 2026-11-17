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
    fun `grid follows widget size`() {
        // 4×2 預設大小（約 190dp 高、寬過 250dp）要放得下 2 排 × 2 欄 = 4 個帳號。
        assertEquals(2, OverviewLayout.columns(320f))
        assertEquals(1, OverviewLayout.columns(200f))
        assertEquals(2, OverviewLayout.gridRows(190f))
        assertEquals(1, OverviewLayout.gridRows(60f))
        // 大字體：格子變高，同樣高度放得比較少，但至少一排。
        assertEquals(1, OverviewLayout.gridRows(190f, fontScale = 1.5f))
        assertEquals(4, OverviewLayout.gridRows(350f))
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
