package com.kmnexus.codexmeter.widget.overview

import com.kmnexus.codexmeter.widget.WidgetQuotaStatus
import com.kmnexus.codexmeter.widget.WidgetQuotaTone
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class QuotaPaceCalculatorTest {
    private val now = Instant.parse("2026-11-18T00:00:00Z")
    private val fiveHours = 5 * 3600

    private fun row(remaining: Int?, minutesToReset: Long?, status: WidgetQuotaStatus = WidgetQuotaStatus.Fresh) =
        OverviewRow(
            providerId = "gemini",
            accountName = "a",
            status = status,
            tone = WidgetQuotaTone.Success,
            main = OverviewField(
                windowId = "gemini_5h_window",
                isBalance = false,
                percent = remaining,
                balanceAmount = null,
                balanceCurrency = null,
                resetAtEpochMillis = minutesToReset?.let { now.plusSeconds(it * 60).toEpochMilli() },
                limitWindowSeconds = fiveHours,
                tone = WidgetQuotaTone.Success,
            ),
            secondary = null,
        )

    @Test
    fun `lots of quota left but little time left means using too slowly`() {
        // 剩 80% 額度、只剩 1 小時（20% 時間）：重置時會浪費一大堆。
        assertEquals(QuotaPace.UnderUsing, QuotaPaceCalculator.pace(row(80, 60), now))
    }

    @Test
    fun `quota and time draining together is on track`() {
        assertEquals(QuotaPace.OnTrack, QuotaPaceCalculator.pace(row(50, 150), now))
    }

    @Test
    fun `quota running out faster than time is too fast`() {
        // 剩 10% 額度、還有 4 小時：撐不到重置。
        assertEquals(QuotaPace.OverUsing, QuotaPaceCalculator.pace(row(10, 240), now))
    }

    @Test
    fun `empty quota is depleted and missing data is unknown`() {
        assertEquals(QuotaPace.Depleted, QuotaPaceCalculator.pace(row(0, 120), now))
        assertEquals(QuotaPace.Unknown, QuotaPaceCalculator.pace(row(null, 120), now))
        assertEquals(QuotaPace.Unknown, QuotaPaceCalculator.pace(row(50, null), now))
        assertEquals(
            QuotaPace.Unknown,
            QuotaPaceCalculator.pace(row(50, 120, status = WidgetQuotaStatus.AuthRequired), now),
        )
    }

    @Test
    fun `time left fraction is clamped to the window`() {
        assertEquals(0.5f, QuotaPaceCalculator.timeLeftFraction(row(50, 150).main!!, now)!!, 0.001f)
        assertEquals(0f, QuotaPaceCalculator.timeLeftFraction(row(50, -10).main!!, now)!!, 0.001f)
        assertEquals(1f, QuotaPaceCalculator.timeLeftFraction(row(50, 999).main!!, now)!!, 0.001f)
    }
}
