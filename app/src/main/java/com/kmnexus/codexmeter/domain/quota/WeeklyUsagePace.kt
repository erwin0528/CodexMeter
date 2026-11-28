package com.kmnexus.codexmeter.domain.quota

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 週額度預測的結果。
 *
 * - [runOutAt]：照目前速度，週額度會在什麼時候用完；撐得到重置或資料不夠時為 null。
 * - [willLastToReset]：照目前速度撐得到重置。
 * - [projectedLeftoverPercent]：撐得到重置時，預估重置當下還會剩幾 %（就是會被浪費掉的量）。
 * - [dailyTargetPercent]：把剩下的額度平均分到剩下的（上班）日，每天可以用幾 %。
 */
data class WeeklyPaceForecast(
    val expectedUsedPercent: Double,
    val actualUsedPercent: Double,
    val runOutAt: Instant?,
    val willLastToReset: Boolean,
    val projectedLeftoverPercent: Double?,
    val dailyTargetPercent: Double?,
)

/**
 * 週額度節奏預測。演算法移植自 CodexBar `UsagePace.swift`（MIT，steipete/CodexBar），
 * 改成 Kotlin 並做了三處調整：
 *
 * 1. 上班日用「星期集合」表示（預設週一到週六），取代 CodexBar 的「前 N 天」整數，
 *    避開它曾出現的上班日被覆蓋（#2176）與關閉上班日模式時正負號反轉（#1544）。
 *    集合是 7 天全開或空集合時，一律退回「整週平均」計算。
 * 2. 冷啟動保護：週期開始未滿 [COLD_START] 或用量低於 [COLD_START_MIN_USED] 時，
 *    樣本太少、外推會亂跳，所以不預測用完時間（每天可用多少照算）。
 * 3. 多算一個「預估重置時會剩多少」，直接對準「不要浪費」的目標。
 */
object WeeklyUsagePace {
    val DEFAULT_ACTIVE_DAYS: Set<DayOfWeek> = DayOfWeek.entries.toSet() - DayOfWeek.SUNDAY
    private const val DAYS_PER_WEEK = 7
    private val WEEK_SECONDS = Duration.ofDays(DAYS_PER_WEEK.toLong()).seconds
    private const val FULL = 100.0
    private const val MILLIS_PER_SECOND = 1000
    private const val COLD_START_HOURS = 6L
    private val COLD_START: Duration = Duration.ofHours(COLD_START_HOURS)
    private const val COLD_START_MIN_USED = 3.0

    @Suppress("ReturnCount") // 每個 return 都是一種「資料不足以預測」的情況，拆開比較好讀
    fun forecast(
        usedPercent: Int,
        resetAt: Instant,
        windowSeconds: Long,
        now: Instant,
        zone: ZoneId,
        activeDays: Set<DayOfWeek> = DEFAULT_ACTIVE_DAYS,
    ): WeeklyPaceForecast? {
        if (windowSeconds <= 0) return null
        val untilReset = Duration.between(now, resetAt).seconds
        if (untilReset <= 0 || untilReset > windowSeconds) return null
        val windowStart = resetAt.minusSeconds(windowSeconds)
        val actual = usedPercent.toDouble().coerceIn(0.0, FULL)
        val elapsedWall = windowSeconds - untilReset
        if (elapsedWall == 0L && actual > 0) return null

        val workdays = effectiveWorkdays(windowSeconds, activeDays)
        val progress = workdays?.let { workProgress(windowStart, resetAt, now, zone, it) }
            ?: WorkProgress(windowSeconds.toDouble(), elapsedWall.toDouble(), untilReset.toDouble())
        val expected = (progress.elapsed / progress.total * FULL).coerceIn(0.0, FULL)
        val dailyTarget = dailyTarget(FULL - actual, resetAt, now, zone, workdays)

        val coldStart = Duration.ofSeconds(elapsedWall) < COLD_START || actual < COLD_START_MIN_USED
        return when {
            actual >= FULL -> WeeklyPaceForecast(expected, actual, now, false, null, dailyTarget)
            coldStart || progress.elapsed <= 0 ->
                WeeklyPaceForecast(expected, actual, null, false, null, dailyTarget)
            else -> project(actual, expected, progress, now, resetAt, zone, workdays, dailyTarget)
        }
    }

    /** 照目前速度往後推：撐得到重置就算會剩多少，撐不到就算幾點用完。 */
    @Suppress("LongParameterList") // 都是同一次預測的中間值，包成物件反而難讀
    private fun project(
        actual: Double,
        expected: Double,
        progress: WorkProgress,
        now: Instant,
        resetAt: Instant,
        zone: ZoneId,
        workdays: Set<DayOfWeek>?,
        dailyTarget: Double?,
    ): WeeklyPaceForecast {
        val rate = actual / progress.elapsed // 每「有效秒」用掉幾 %
        val needed = (FULL - actual) / rate // 用完還需要幾個「有效秒」
        if (needed >= progress.remaining) {
            val leftover = (FULL - actual - rate * progress.remaining).coerceIn(0.0, FULL)
            return WeeklyPaceForecast(expected, actual, null, true, leftover, dailyTarget)
        }
        val runOutAt = if (workdays == null) {
            now.plusMillis((needed * MILLIS_PER_SECOND).toLong())
        } else {
            wallClockAfterWorkSeconds(now, resetAt, needed, zone, workdays)
        }
        return WeeklyPaceForecast(expected, actual, runOutAt, false, null, dailyTarget)
    }

    /** 剩下的額度 ÷ 剩下的（上班）日數，今天也算一天；至少除以 1。 */
    fun dailyTarget(
        remainingPercent: Double,
        resetAt: Instant,
        now: Instant,
        zone: ZoneId,
        activeDays: Set<DayOfWeek>?,
    ): Double? {
        if (remainingPercent < 0 || !resetAt.isAfter(now)) return null
        val today = now.atZone(zone).toLocalDate()
        val resetLocal = resetAt.atZone(zone)
        // 重置剛好在午夜，那天就不算（新的一碗從那天開始）
        val lastDay = if (resetLocal.toLocalTime() == java.time.LocalTime.MIDNIGHT) {
            resetLocal.toLocalDate().minusDays(1)
        } else {
            resetLocal.toLocalDate()
        }
        val days = generateSequence(today) { it.plusDays(1) }
            .takeWhile { !it.isAfter(lastDay) }
            .count { activeDays == null || it.dayOfWeek in activeDays }
        return remainingPercent / days.coerceAtLeast(1)
    }

    // 只有真正一週的視窗才套上班日；其他長度（例如月額度）或 7 天全開、空集合都照時間平均
    private fun effectiveWorkdays(windowSeconds: Long, activeDays: Set<DayOfWeek>): Set<DayOfWeek>? =
        activeDays.takeIf { windowSeconds == WEEK_SECONDS && it.size in 1 until DAYS_PER_WEEK }

    private data class WorkProgress(val total: Double, val elapsed: Double, val remaining: Double)

    /** 以當地午夜切成一天一天，只累計上班日的秒數。 */
    private fun workProgress(
        start: Instant,
        end: Instant,
        now: Instant,
        zone: ZoneId,
        workdays: Set<DayOfWeek>,
    ): WorkProgress? {
        var total = 0.0
        var elapsed = 0.0
        var remaining = 0.0
        daySlices(start, end, zone).forEach { (sliceStart, sliceEnd, date) ->
            if (date.dayOfWeek !in workdays) return@forEach
            total += secondsBetween(sliceStart, sliceEnd)
            if (now.isAfter(sliceStart)) elapsed += secondsBetween(sliceStart, minOf(now, sliceEnd))
            if (now.isBefore(sliceEnd)) remaining += secondsBetween(maxOf(now, sliceStart), sliceEnd)
        }
        return WorkProgress(total, elapsed, remaining).takeIf { total > 0 }
    }

    /** 從現在開始，消耗掉 [workSeconds] 個上班日秒數後是幾點；超過重置就回 null。 */
    private fun wallClockAfterWorkSeconds(
        now: Instant,
        resetAt: Instant,
        workSeconds: Double,
        zone: ZoneId,
        workdays: Set<DayOfWeek>,
    ): Instant? {
        var left = workSeconds
        for ((sliceStart, sliceEnd, date) in daySlices(now, resetAt, zone)) {
            if (date.dayOfWeek !in workdays) continue
            val available = secondsBetween(sliceStart, sliceEnd)
            if (left <= available) return sliceStart.plusMillis((left * MILLIS_PER_SECOND).toLong())
            left -= available
        }
        return null
    }

    private fun daySlices(
        start: Instant,
        end: Instant,
        zone: ZoneId,
    ): Sequence<Triple<Instant, Instant, LocalDate>> = sequence {
            var cursor = start
            while (cursor.isBefore(end)) {
                val date = cursor.atZone(zone).toLocalDate()
                val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant()
                val sliceEnd = minOf(nextMidnight, end)
                yield(Triple(cursor, sliceEnd, date))
                cursor = sliceEnd
            }
        }

    private fun secondsBetween(a: Instant, b: Instant): Double =
        Duration.between(a, b).toMillis() / MILLIS_PER_SECOND.toDouble()
}
