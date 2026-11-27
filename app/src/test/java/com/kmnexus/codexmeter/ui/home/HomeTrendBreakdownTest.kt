package com.kmnexus.codexmeter.ui.home

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeTrendBreakdownTest {
    private val taipei = ZoneId.of("Asia/Taipei")

    private fun point(utc: String, value: Double) = HomeTrendPointUi(Instant.parse(utc), value)

    @Test
    fun `同一個小時的用量加總並由新到舊排`() {
        val rows = HomeTrendBreakdown.byHour(
            listOf(
                point("2026-11-27T12:10:00Z", 5.0), // 台北 20:10
                point("2026-11-27T12:50:00Z", 7.0), // 台北 20:50
                point("2026-11-27T13:20:00Z", 3.0), // 台北 21:20
            ),
            taipei,
        )
        assertEquals(listOf(LocalTime.of(21, 0), LocalTime.of(20, 0)), rows.map { it.hourStart.toLocalTime() })
        assertEquals(listOf(3.0, 12.0), rows.map { it.value })
    }

    @Test
    fun `沒用量的小時不列出來`() {
        val rows = HomeTrendBreakdown.byHour(
            listOf(point("2026-11-27T12:10:00Z", 0.0), point("2026-11-27T14:10:00Z", 4.0)),
            taipei,
        )
        assertEquals(1, rows.size)
        assertEquals(LocalTime.of(22, 0), rows.single().hourStart.toLocalTime())
    }

    @Test
    fun `總量與最兇的小時`() {
        val points = listOf(
            point("2026-11-27T12:10:00Z", 5.0),
            point("2026-11-27T13:20:00Z", 9.0),
        )
        assertEquals(14.0, HomeTrendBreakdown.total(points), 0.0001)
        assertEquals(LocalTime.of(21, 0), HomeTrendBreakdown.byHour(points, taipei).maxBy { it.value }.hourStart.toLocalTime())
    }
}
