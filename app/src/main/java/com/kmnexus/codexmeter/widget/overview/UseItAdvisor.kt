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

    /** 推薦門檻：有效剩餘（5 小時與週額度取小的）至少要這麼多。低於 10% 大概只夠問兩三句。 */
    private const val RECOMMEND_MIN_EFFECTIVE_PERCENT = 10

    /** 多久內會重置的額度算「快作廢、要搶救」。 */
    private val RESCUE_WITHIN: Duration = Duration.ofHours(2)

    /** 資料超過這麼久沒更新就不推薦，免得拿舊數字催人。 */
    private val STALE_AFTER: Duration = Duration.ofHours(2)

    /** 週進度落後差距在這之內算「差不多」，改比 5 小時誰先重置。 */
    private const val LAG_TIE_POINTS = 10.0

    /** 週額度用得比節奏快超過這麼多，就排到後面（除非沒得選）。 */
    private const val OVERUSING_LAG = -15.0

    /**
     * 現在最該用哪個帳號，三層規則：
     * 1. 搶救：有額度（5 小時或每週）2 小時內就要重置，而且還剩不少，先用剩最多的那個。
     * 2. 週進度：比較「週剩餘 % − 週剩餘時間 %」，越大代表越落後、越可能浪費，就越優先；
     *    差距 10 點內看 5 小時誰先重置；週額度用太快（落後值低於 -15）的排最後。
     * 3. 平手維持原本的帳號順序，畫面才不會一直跳來跳去。
     * 有效剩餘一律取 5 小時與週額度的較小值：週額度快沒了，5 小時再滿也用不到。
     */
    fun recommend(rows: List<OverviewRow>, now: Instant = Instant.now()): OverviewRow? {
        val candidates = rows.filter { it.isRecommendable(now) }
        val rescue = candidates.filter { it.needsRescue(now) }.maxByOrNull { it.effectivePercent() }
        val (normal, overusing) = candidates.partition { it.weeklyLag(now) >= OVERUSING_LAG }
        return rescue ?: pickByLag(normal.ifEmpty { overusing }, now)
    }

    private fun pickByLag(rows: List<OverviewRow>, now: Instant): OverviewRow? {
        val best = rows.maxOfOrNull { it.weeklyLag(now) } ?: return null
        // 落後值跟最落後的差不到 10 點的都算同一級，裡面挑 5 小時最早重置的；sortedBy 是穩定排序，平手保留原順序
        return rows.filter { best - it.weeklyLag(now) <= LAG_TIE_POINTS }
            .sortedBy { it.main?.resetAtEpochMillis ?: Long.MAX_VALUE }
            .firstOrNull()
    }

    private fun OverviewRow.isRecommendable(now: Instant): Boolean {
        val stale = updatedAtEpochMillis?.let { Duration.between(Instant.ofEpochMilli(it), now) > STALE_AFTER } ?: false
        return status == WidgetQuotaStatus.Fresh &&
            main?.isBalance == false &&
            !stale &&
            effectivePercent() >= RECOMMEND_MIN_EFFECTIVE_PERCENT
    }

    private fun OverviewRow.effectivePercent(): Int {
        val short = main?.percent ?: 0
        val weekly = secondary?.takeUnless { it.isBalance }?.percent ?: return short
        return minOf(short, weekly)
    }

    private fun OverviewRow.needsRescue(now: Instant): Boolean =
        listOfNotNull(main, secondary).any { field ->
            val reset = field.resetAtEpochMillis?.let(Instant::ofEpochMilli) ?: return@any false
            val until = Duration.between(now, reset)
            !until.isNegative && until <= RESCUE_WITHIN
        }

    /** 週剩餘 % 減掉週剩餘時間 %。正值＝用得比節奏慢（會浪費），負值＝用太快。沒週資料當 0。 */
    private fun OverviewRow.weeklyLag(now: Instant): Double {
        val weekly = secondary?.takeUnless { it.isBalance }
        val remaining = weekly?.percent
        val reset = weekly?.resetAtEpochMillis?.let(Instant::ofEpochMilli)
        val window = weekly?.limitWindowSeconds?.takeIf { it > 0 }
        if (remaining == null || reset == null || window == null) return 0.0
        val timeLeft = (Duration.between(now, reset).seconds.toDouble() / window * PERCENT).coerceIn(0.0, PERCENT)
        return remaining - timeLeft
    }

    private const val PERCENT = 100.0

    private fun weeklyHasRoom(row: OverviewRow): Boolean {
        val weekly = row.secondary ?: return true
        if (weekly.isBalance) return true
        return (weekly.percent ?: return true) >= WEEKLY_RESERVE_PERCENT
    }
}
