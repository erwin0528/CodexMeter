package com.kmnexus.codexmeter.domain.cat

import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CatMemoryTest {
    private val day1 = LocalDate.of(2026, 11, 28)

    @Test
    fun `same day visits count once and next day adds one`() {
        val once = CatMemory().visited(day1).visited(day1)
        assertEquals(1, once.daysVisited)
        assertEquals(2, once.visited(day1.plusDays(1)).daysVisited)
    }

    @Test
    fun `level grows by days or feeds and never by skipping days`() {
        assertEquals(0, CatMemory().level)
        assertEquals(1, CatMemory(daysVisited = 3).level)
        assertEquals(1, CatMemory(totalFeedCount = 10).level)
        assertEquals(2, CatMemory(daysVisited = 10).level)
        assertEquals(2, CatMemory(totalFeedCount = 30).level)
        assertEquals(3, CatMemory(totalFeedCount = 60).level)
        // 很久沒來：天數不會倒扣，等級照舊
        val back = CatMemory(daysVisited = 10, lastVisitDate = day1).visited(day1.plusDays(90))
        assertEquals(2, back.level)
    }

    @Test
    fun `recent lines keep last eight and move repeats to the end`() {
        var memory = CatMemory()
        repeat(10) { memory = memory.spoke("l$it") }
        assertEquals((2..9).map { "l$it" }, memory.recentLineIds)
        memory = memory.spoke("l2")
        assertEquals("l2", memory.recentLineIds.last())
        assertEquals(CatMemory.RECENT_LIMIT, memory.recentLineIds.size)
    }

    @Test
    fun `snack cycles through three and falls back to kibble`() {
        assertEquals(CatSnack.DRIED_FISH, CatMemory().switchedSnack().snack)
        assertEquals(CatSnack.KIBBLE, CatSnack.CHURU.next())
        assertEquals(CatSnack.KIBBLE, CatSnack.fromStorage("???"))
        assertEquals(CatSnack.CHURU, CatSnack.fromStorage("churu"))
    }

    @Test
    fun `in memory store applies updates`() = runBlocking {
        val store = InMemoryCatMemoryStore()
        store.update { it.fed().petted() }
        val memory = store.memory.first()
        assertEquals(1, memory.totalFeedCount)
        assertEquals(1, memory.totalPetCount)
    }
}
