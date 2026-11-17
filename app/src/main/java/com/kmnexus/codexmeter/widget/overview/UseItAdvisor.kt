package com.kmnexus.codexmeter.widget.overview

import com.kmnexus.codexmeter.widget.WidgetQuotaStatus
import java.time.Duration
import java.time.Instant

/**
 * 「把額度用完」的建議邏輯（阿蝕的用法：額度重置時沒用完就是浪費）。
 *
 * 前提：第一個額度是短週期（5 小時），第二個是長週期（每週）。
 * 週額度才是真正的上限，所以週額度低於 [WEEKLY_RESERVE_PERCENT] 時，不催你衝短週期。
 * 百分比一律是「剩餘」。
 */
object UseItAdvisor {
    /** 距離重置多久內算「快作廢」。 */
    val EXPIRING_WITHIN: Duration = Duration.ofMinutes(60)

    /** 剩餘至少多少才值得提醒（剩一點點就不吵你）。 */
    const val WASTE_MIN_REMAINING_PERCENT = 30

    /** 週額度低於這個就要省著用，不建議衝。 */
    const val WEEKLY_RESERVE_PERCENT = 20

    /** 建議清單門檻：至少剩這麼多才推薦。 */
    private const val RECOMMEND_MIN_REMAINING_PERCENT = 20

    /** 這個帳號的短週期額度快重置了，而且還剩很多。 */
    fun isExpiringUnused(row: OverviewRow, now: Instant): Boolean {
        val main = row.main ?: return false
        if (row.status != WidgetQuotaStatus.Fresh || main.isBalance) return false
        val remaining = main.percent ?: return false
        val reset = main.resetAtEpochMillis?.let(Instant::ofEpochMilli) ?: return false
        val untilReset = Duration.between(now, reset)
        return remaining >= WASTE_MIN_REMAINING_PERCENT &&
            !untilReset.isNegative &&
            untilReset <= EXPIRING_WITHIN &&
            weeklyHasRoom(row)
    }

    /**
     * 現在最該用哪個帳號：在「還有額度、週額度也夠」的帳號裡，挑短週期最早重置的
     * （最快會被浪費的先用）；同時間重置就挑剩比較多的。
     */
    fun recommend(rows: List<OverviewRow>): OverviewRow? =
        rows
            .filter { row ->
                val main = row.main
                row.status == WidgetQuotaStatus.Fresh &&
                    main != null && !main.isBalance &&
                    (main.percent ?: 0) >= RECOMMEND_MIN_REMAINING_PERCENT &&
                    weeklyHasRoom(row)
            }
            .minWithOrNull(
                compareBy<OverviewRow> { it.main?.resetAtEpochMillis ?: Long.MAX_VALUE }
                    .thenByDescending { it.main?.percent ?: 0 },
            )

    private fun weeklyHasRoom(row: OverviewRow): Boolean {
        val weekly = row.secondary ?: return true
        if (weekly.isBalance) return true
        return (weekly.percent ?: return true) >= WEEKLY_RESERVE_PERCENT
    }
}
