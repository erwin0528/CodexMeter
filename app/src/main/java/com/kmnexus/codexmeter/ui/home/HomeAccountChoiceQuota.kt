package com.kmnexus.codexmeter.ui.home

import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaWindowDisplayKind
import com.kmnexus.codexmeter.widget.overview.QuotaPace
import com.kmnexus.codexmeter.widget.overview.QuotaPaceCalculator
import java.time.Duration
import java.time.Instant

/**
 * 首頁三帳號比較列要的兩個數字：主要額度剩幾 %、節奏顏色。
 * 主要額度＝provider 標成主要候選的第一個百分比視窗（Gemini、Claude 都是 5 小時），沒有就取第一個百分比視窗。
 */
data class HomeAccountChoiceQuota(val remainingPercent: Int?, val pace: QuotaPace) {
    companion object {
        private val NONE = HomeAccountChoiceQuota(null, QuotaPace.Unknown)

        fun from(snapshot: QuotaSnapshot?, now: Instant = Instant.now()): HomeAccountChoiceQuota {
            val percentWindows = snapshot?.windows.orEmpty()
                .filter { it.displayKind == QuotaWindowDisplayKind.Percent && it.displayPercent != null }
            val window = percentWindows.firstOrNull { it.isPrimaryCandidate } ?: percentWindows.firstOrNull()
            val remaining = window?.displayPercent ?: return NONE
            val reset = window.resetAt
            val seconds = window.limitWindowSeconds?.takeIf { it > 0 }
            val pace = when {
                remaining <= 0 -> QuotaPace.Depleted
                reset == null || seconds == null -> QuotaPace.Unknown
                else -> QuotaPaceCalculator.pace(
                    remaining,
                    (Duration.between(now, reset).seconds.toFloat() / seconds).coerceIn(0f, 1f),
                )
            }
            return HomeAccountChoiceQuota(remaining, pace)
        }
    }
}
