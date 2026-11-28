package com.kmnexus.codexmeter.ui.home

import com.kmnexus.codexmeter.domain.cat.CatMemory
import com.kmnexus.codexmeter.domain.cat.CatSnack
import java.io.File
import java.time.LocalDateTime
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatDialogueTest {
    private val afternoon = LocalDateTime.of(2026, 11, 28, 15, 0)

    @Test
    fun `idle pick avoids recently spoken lines`() {
        val all = CatDialogue.idleCandidates(CatMemory(), HomeMascotMood.Hungry, afternoon)
        val keep = all.first()
        val memory = CatMemory(recentLineIds = all.drop(1).map { it.id })
        repeat(20) {
            assertEquals(keep.id, CatDialogue.pickIdle(memory, HomeMascotMood.Hungry, afternoon, Random(it)).id)
        }
    }

    @Test
    fun `idle pick still talks when everything was spoken`() {
        val all = CatDialogue.idleCandidates(CatMemory(), HomeMascotMood.Relaxed, afternoon)
        val memory = CatMemory(recentLineIds = all.map { it.id })
        val picked = CatDialogue.pickIdle(memory, HomeMascotMood.Relaxed, afternoon, Random(1))
        assertTrue(picked.id in all.map { it.id })
    }

    @Test
    fun `morning and level lines unlock only when due`() {
        val morning = afternoon.withHour(7)
        fun hasMorning(time: LocalDateTime) =
            CatDialogue.idleCandidates(CatMemory(), HomeMascotMood.Relaxed, time).any { it.id == "morning" }
        assertFalse(hasMorning(afternoon))
        assertTrue(hasMorning(morning))
        val ids = CatDialogue.idleCandidates(CatMemory(totalFeedCount = 60), HomeMascotMood.Relaxed, afternoon)
            .map { it.id }
        assertTrue(ids.containsAll(listOf("level1", "level2", "level3")))
    }

    @Test
    fun `three feeds within a minute means full`() {
        assertFalse(CatDialogue.isFull(listOf(0L, 30_000L), 30_000L))
        assertTrue(CatDialogue.isFull(listOf(0L, 30_000L, 59_000L), 59_000L))
        assertFalse(CatDialogue.isFull(listOf(0L, 30_000L, 61_000L), 61_000L))
        assertEquals("full", CatDialogue.feedLine(CatSnack.CHURU, full = true).id)
        assertEquals("feed_fish", CatDialogue.feedLine(CatSnack.DRIED_FISH, full = false).id)
    }

    @Test
    fun `zh-TW cat lines fit on one line`() {
        // 台詞列只有一行，太長會被截掉；格式參數算 3 個字的空間
        val xml = File("src/main/res/values-zh-rTW/strings.xml").readText()
        val lines = Regex("""<string name="home_cat_[a-z0-9_]+">([^<]+)</string>""").findAll(xml)
            .map { it.groupValues[1].replace(Regex("""%\d\$[sd]"""), "000") }
            .toList()
        assertTrue(lines.isNotEmpty())
        lines.forEach { assertTrue("太長：$it", it.length <= MAX_LINE_CHARS) }
    }

    private companion object {
        const val MAX_LINE_CHARS = 14
    }
}
