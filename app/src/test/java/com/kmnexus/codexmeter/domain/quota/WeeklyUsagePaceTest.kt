package com.kmnexus.codexmeter.domain.quota

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyUsagePaceTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val week = 7L * 24 * 3600
    private val allDays = DayOfWeek.entries.toSet()

    // 週期：11/23（一）08:00 開始，11/30（一）08:00 重置
    private val resetAt = at("2026-11-30T08:00")

    private fun at(local: String): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    private fun forecast(used: Int, now: String, days: Set<DayOfWeek> = allDays) =
        WeeklyUsagePace.forecast(used, resetAt, week, at(now), zone, days)

    @Test
    fun `exactly on pace lasts to reset with nothing left`() {
        val result = forecast(used = 50, now = "2026-11-26T20:00")!!
        assertEquals(50.0, result.expectedUsedPercent, 0.01)
        assertTrue(result.willLastToReset)
        assertEquals(0.0, result.projectedLeftoverPercent!!, 0.5)
    }

    @Test
    fun `using too fast predicts a run out time before reset`() {
        // 過了 2 天（2/7）用了 60%，每天約 30%，剩 40% 約再 1.33 天用完
        val result = forecast(used = 60, now = "2026-11-25T08:00")!!
        assertFalse(result.willLastToReset)
        val expected = at("2026-11-25T08:00").plusSeconds((40.0 / 60.0 * 2 * 86400).toLong())
        assertEquals(expected.epochSecond.toDouble(), result.runOutAt!!.epochSecond.toDouble(), 60.0)
    }

    @Test
    fun `using too slow predicts the leftover that will be wasted`() {
        // 過了一半只用 10%，照速度到重置還會再用 10%，剩 80% 浪費
        val result = forecast(used = 10, now = "2026-11-26T20:00")!!
        assertTrue(result.willLastToReset)
        assertEquals(80.0, result.projectedLeftoverPercent!!, 0.5)
        assertNull(result.runOutAt)
    }

    @Test
    fun `cold start does not extrapolate run out time`() {
        val early = forecast(used = 40, now = "2026-11-23T12:00")!!
        assertNull(early.runOutAt)
        assertFalse(early.willLastToReset)
        val tinyUse = forecast(used = 2, now = "2026-11-26T20:00")!!
        assertNull(tinyUse.runOutAt)
        assertNull(tinyUse.projectedLeftoverPercent)
    }

    @Test
    fun `fully used reports run out now`() {
        val now = "2026-11-26T20:00"
        assertEquals(at(now), forecast(used = 100, now = now)!!.runOutAt)
    }

    @Test
    fun `workday mode pauses the clock on sunday`() {
        val days = WeeklyUsagePace.DEFAULT_ACTIVE_DAYS
        val satNight = forecast(used = 40, now = "2026-11-29T00:00", days = days)!!
        val sunNight = forecast(used = 40, now = "2026-11-29T23:59", days = days)!!
        // 週日不上班，時間進度不該前進
        assertEquals(satNight.expectedUsedPercent, sunNight.expectedUsedPercent, 0.1)
    }

    @Test
    fun `workday and full week modes agree on direction (CodexBar 1544)`() {
        // 用得比節奏慢：兩種模式都該是「撐得到、會剩」，不能一個說會剩一個說會用完
        val slowAll = forecast(used = 20, now = "2026-11-27T08:00")!!
        val slowWork = forecast(used = 20, now = "2026-11-27T08:00", days = WeeklyUsagePace.DEFAULT_ACTIVE_DAYS)!!
        assertTrue(slowAll.willLastToReset)
        assertTrue(slowWork.willLastToReset)
        // 用得比節奏快：兩種模式都該預測會提早用完
        val fastAll = forecast(used = 90, now = "2026-11-27T08:00")!!
        val fastWork = forecast(used = 90, now = "2026-11-27T08:00", days = WeeklyUsagePace.DEFAULT_ACTIVE_DAYS)!!
        assertNotNull(fastAll.runOutAt)
        assertNotNull(fastWork.runOutAt)
    }

    @Test
    fun `daily target spreads remaining over remaining active days`() {
        val now = at("2026-11-26T20:00") // 週四
        // 週四、五、六、一 共 4 個上班日
        assertEquals(
            15.0,
            WeeklyUsagePace.dailyTarget(60.0, resetAt, now, zone, WeeklyUsagePace.DEFAULT_ACTIVE_DAYS)!!,
            0.01,
        )
        // 整週模式多算週日：5 天
        assertEquals(12.0, WeeklyUsagePace.dailyTarget(60.0, resetAt, now, zone, null)!!, 0.01)
    }

    @Test
    fun `midnight reset does not count the reset day`() {
        val midnight = at("2026-11-30T00:00")
        val now = at("2026-11-28T10:00") // 週六：只剩週六、週日
        assertEquals(30.0, WeeklyUsagePace.dailyTarget(60.0, midnight, now, zone, null)!!, 0.01)
    }

    @Test
    fun `invalid windows return null`() {
        assertNull(WeeklyUsagePace.forecast(10, resetAt, week, resetAt.plusSeconds(1), zone))
        assertNull(WeeklyUsagePace.forecast(10, resetAt, week, resetAt.minusSeconds(week + 60), zone))
    }
}
