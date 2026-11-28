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
        weeklyResetInHours: Long? = null,
        updatedMinutesAgo: Long? = null,
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
            OverviewField(
                "gemini_weekly_window",
                false,
                it,
                null,
                null,
                weeklyResetInHours?.let { h -> now.plusSeconds(h * 3600).toEpochMilli() },
                604_800,
                WidgetQuotaTone.Success,
            )
        },
        updatedAtEpochMillis = updatedMinutesAgo?.let { now.minusSeconds(it * 60).toEpochMilli() },
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
            now,
        )

        assertEquals("resets soon", pick?.accountName)
    }

    @Test
    fun `recommend returns nothing when no account has room`() {
        assertNull(UseItAdvisor.recommend(listOf(row("a", remaining = 5, resetInMinutes = 30)), now))
    }

    @Test
    fun `weekly about to reset with plenty left is rescued first`() {
        val pick = UseItAdvisor.recommend(
            listOf(
                row("5h resets later", remaining = 80, resetInMinutes = 200, weekly = 30, weeklyResetInHours = 100),
                row("weekly resets in 1h", remaining = 50, resetInMinutes = 250, weekly = 60, weeklyResetInHours = 1),
            ),
            now,
        )
        assertEquals("weekly resets in 1h", pick?.accountName)
    }

    @Test
    fun `account most behind on weekly pace wins`() {
        // 兩個都剩 84 小時（一半時間）：A 週剩 90%（落後 40 點），B 週剩 50%（剛好）
        val pick = UseItAdvisor.recommend(
            listOf(
                row("on pace", remaining = 100, resetInMinutes = 180, weekly = 50, weeklyResetInHours = 84),
                row("behind", remaining = 100, resetInMinutes = 240, weekly = 90, weeklyResetInHours = 84),
            ),
            now,
        )
        assertEquals("behind", pick?.accountName)
    }

    @Test
    fun `close weekly lag falls back to earliest 5h reset`() {
        val pick = UseItAdvisor.recommend(
            listOf(
                row("lag 40", remaining = 100, resetInMinutes = 240, weekly = 90, weeklyResetInHours = 84),
                row(
                    "lag 35 resets sooner",
                    remaining = 100,
                    resetInMinutes = 150,
                    weekly = 85,
                    weeklyResetInHours = 84,
                ),
            ),
            now,
        )
        assertEquals("lag 35 resets sooner", pick?.accountName)
    }

    @Test
    fun `overusing account is only picked when nothing else`() {
        val overusing = row("overusing", remaining = 100, resetInMinutes = 150, weekly = 20, weeklyResetInHours = 140)
        val normal = row("normal", remaining = 40, resetInMinutes = 240, weekly = 50, weeklyResetInHours = 84)
        assertEquals("normal", UseItAdvisor.recommend(listOf(overusing, normal), now)?.accountName)
        assertEquals("overusing", UseItAdvisor.recommend(listOf(overusing), now)?.accountName)
    }

    @Test
    fun `stale data and nearly empty weekly are excluded`() {
        assertNull(UseItAdvisor.recommend(listOf(row("old", 90, 180, updatedMinutesAgo = 180)), now))
        // 5 小時滿的但週額度只剩 8%：有效剩餘 8%，不推薦
        assertNull(UseItAdvisor.recommend(listOf(row("weekly empty", 100, 180, weekly = 8)), now))
    }
}
