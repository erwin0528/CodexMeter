package com.kmnexus.codexmeter.widget.overview

import com.kmnexus.codexmeter.widget.WidgetQuotaStatus
import java.time.Duration
import java.time.Instant

/**
 * 用量節奏：拿「額度還剩幾 %」跟「這個週期的時間還剩幾 %」比。
 * - 額度剩得比時間多很多 → 用太慢，重置時會浪費（阿蝕的目標是用完，所以要催）。
 * - 額度剩得比時間少很多 → 用太快，撐不到重置。
 * 百分比一律是「剩餘」。
 */
enum class QuotaPace { UnderUsing, OnTrack, OverUsing, Depleted, Unknown }

object QuotaPaceCalculator {
    /** 差距超過這麼多個百分點才算偏離，避免在邊界一直跳色。 */
    const val TOLERANCE_PERCENT = 15

    /** 這個週期的時間還剩幾成（0～1）；缺重置時間或週期長度就算不出來。 */
    fun timeLeftFraction(field: OverviewField, now: Instant): Float? {
        val reset = field.resetAtEpochMillis?.let(Instant::ofEpochMilli) ?: return null
        val windowSeconds = field.limitWindowSeconds?.takeIf { it > 0 } ?: return null
        val left = Duration.between(now, reset).seconds.toFloat() / windowSeconds
        return left.coerceIn(0f, 1f)
    }

    fun pace(row: OverviewRow, now: Instant): QuotaPace {
        val main = row.main ?: return QuotaPace.Unknown
        if (row.status == WidgetQuotaStatus.AuthRequired || main.isBalance) return QuotaPace.Unknown
        val remaining = main.percent ?: return QuotaPace.Unknown
        if (remaining <= 0) return QuotaPace.Depleted
        val timeLeftPercent = timeLeftFraction(main, now)?.times(100) ?: return QuotaPace.Unknown
        val diff = remaining - timeLeftPercent
        return when {
            diff > TOLERANCE_PERCENT -> QuotaPace.UnderUsing
            diff < -TOLERANCE_PERCENT -> QuotaPace.OverUsing
            else -> QuotaPace.OnTrack
        }
    }
}
