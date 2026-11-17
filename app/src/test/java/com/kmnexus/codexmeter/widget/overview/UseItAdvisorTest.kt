package com.kmnexus.codexmeter.widget.overview

import com.kmnexus.codexmeter.widget.WidgetQuotaStatus
import com.kmnexus.codexmeter.widget.WidgetQuotaTone
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UseItAdvisorTest {
    private val now = Instant.parse("2026-11-17T10:00:00Z")

    private fun row(
        name: String,
        remaining: Int?,
        resetInMinutes: Long?,
        weekly: Int? = 90,
        status: WidgetQuotaStatus = WidgetQuotaStatus.Fresh,
    ) = OverviewRow(
        providerId = "gemini",
        accountName = name,
        status = status,
        tone = WidgetQuotaTone.Success,
        main = OverviewField(
            windowId = "gemini_5h_window",
            isBalance = false,
            percent = remaining,
            balanceAmount = null,
            balanceCurrency = null,
            resetAtEpochMillis = resetInMinutes?.let { now.plusSeconds(it * 60).toEpochMilli() },
            limitWindowSeconds = 18_000,
            tone = WidgetQuotaTone.Success,
        ),
        secondary = weekly?.let {
            OverviewField("gemini_weekly_window", false, it, null, null, null, 604_800, WidgetQuotaTone.Success)
        },
    )

    @Test
    fun `lots left and resetting soon is flagged as about to be wasted`() {
        assertTrue(UseItAdvisor.isExpiringUnused(row("a", remaining = 70, resetInMinutes = 40), now))
    }

    @Test
    fun `not flagged when little is left, reset is far, or weekly is nearly gone`() {
        assertFalse(UseItAdvisor.isExpiringUnused(row("a", remaining = 10, resetInMinutes = 40), now))
        assertFalse(UseItAdvisor.isExpiringUnused(row("a", remaining = 70, resetInMinutes = 120), now))
        // 週額度只剩 10%：衝完 5 小時，接下來幾天就沒得用，不該催。
        assertFalse(UseItAdvisor.isExpiringUnused(row("a", remaining = 70, resetInMinutes = 40, weekly = 10), now))
        assertFalse(
            UseItAdvisor.isExpiringUnused(
                row("a", remaining = 70, resetInMinutes = 40, status = WidgetQuotaStatus.AuthRequired),
                now,
            ),
        )
    }

    @Test
    fun `recommend picks the account whose quota will be wasted first`() {
        val pick = UseItAdvisor.recommend(
            listOf(
                row("full but resets late", remaining = 100, resetInMinutes = 280),
                row("resets soon", remaining = 60, resetInMinutes = 30),
                row("weekly nearly gone", remaining = 90, resetInMinutes = 10, weekly = 5),
                row("almost empty", remaining = 5, resetInMinutes = 5),
            ),
        )

        assertEquals("resets soon", pick?.accountName)
    }

    @Test
    fun `recommend returns nothing when no account has room`() {
        assertNull(UseItAdvisor.recommend(listOf(row("a", remaining = 5, resetInMinutes = 30))))
    }
}
