package com.kmnexus.codexmeter.ui.home

import com.kmnexus.codexmeter.R
import java.time.Instant
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMascotChatterTest {
    @Test
    fun `petting alternates two lines for the current mood`() {
        assertEquals(R.string.home_mascot_pet_hungry_1, HomeMascotChatter.petLine(HomeMascotMood.Hungry, 1).resId)
        assertEquals(R.string.home_mascot_pet_hungry_2, HomeMascotChatter.petLine(HomeMascotMood.Hungry, 2).resId)
        assertEquals(R.string.home_mascot_pet_hungry_1, HomeMascotChatter.petLine(HomeMascotMood.Hungry, 3).resId)
    }

    @Test
    fun `every mood has its own pet lines`() {
        val firstLines = HomeMascotMood.entries.map { HomeMascotChatter.petLine(it, 1).resId }
        assertEquals(HomeMascotMood.entries.size, firstLines.toSet().size)
    }

    @Test
    fun `too much petting tells the user to go use quota`() {
        val line = HomeMascotChatter.petLine(HomeMascotMood.Relaxed, HomeMascotChatter.PET_ENOUGH_COUNT)
        assertEquals(R.string.home_mascot_pet_enough, line.resId)
    }

    @Test
    fun `bowl reports remaining percent and refill time`() {
        val state = HomeMascotState(HomeMascotMood.Hungry, remainingPercent = 72, resetAt = Instant.EPOCH)
        assertEquals(
            HomeMascotLine(R.string.home_mascot_bowl_reset, listOf(72, "21:00")),
            HomeMascotChatter.bowlLine(state, "21:00"),
        )
        assertEquals(HomeMascotLine(R.string.home_mascot_bowl, listOf(72)), HomeMascotChatter.bowlLine(state, null))
    }

    @Test
    fun `empty or unknown bowl says nothing to check`() {
        val empty = HomeMascotState(HomeMascotMood.AteAll, remainingPercent = 0)
        val unknown = HomeMascotState(HomeMascotMood.Waiting)
        assertEquals(R.string.home_mascot_bowl_empty, HomeMascotChatter.bowlLine(empty, "21:00").resId)
        assertEquals(R.string.home_mascot_bowl_empty, HomeMascotChatter.bowlLine(unknown, null).resId)
    }

    // 2026-11-28 是週六、2026-11-29 是週日
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute)

    @Test
    fun `saturday evening greets after work`() {
        assertEquals(R.string.home_mascot_greet_after_work, HomeMascotChatter.greetingLine(at(28, 18, 30))?.resId)
        assertNull(HomeMascotChatter.greetingLine(at(28, 20, 30)))
    }

    @Test
    fun `late night wins over sunday and tells user to sleep`() {
        assertEquals(R.string.home_mascot_greet_late_night, HomeMascotChatter.greetingLine(at(29, 0, 30))?.resId)
        assertEquals(R.string.home_mascot_greet_late_night, HomeMascotChatter.greetingLine(at(28, 23, 0))?.resId)
        assertTrue(HomeMascotChatter.isLateNight(at(28, 4, 59)))
        assertFalse(HomeMascotChatter.isLateNight(at(28, 5, 0)))
    }

    @Test
    fun `sunday daytime greets day off and weekday afternoon stays quiet`() {
        assertEquals(R.string.home_mascot_greet_sunday, HomeMascotChatter.greetingLine(at(29, 14))?.resId)
        assertNull(HomeMascotChatter.greetingLine(at(28, 14)))
    }

    @Test
    fun `chin and treat lines rotate`() {
        assertEquals(R.string.home_mascot_chin_1, HomeMascotChatter.chinLine(1).resId)
        assertEquals(R.string.home_mascot_chin_1, HomeMascotChatter.chinLine(4).resId)
        assertEquals(HomeMascotLine(R.string.home_mascot_treat_2, listOf(40)), HomeMascotChatter.treatLine(2, 40))
        assertEquals(R.string.home_mascot_treat_unknown, HomeMascotChatter.treatLine(1, null).resId)
    }

    @Test
    fun `switching account announces the new bowl`() {
        val before = MascotBowlSnapshot("g1", "G1", 30)
        val after = MascotBowlSnapshot("g2", "G2", 80)
        assertEquals(
            HomeMascotLine(R.string.home_mascot_switched, listOf("G2", 80)),
            HomeMascotChatter.changeLine(before, after),
        )
    }

    @Test
    fun `refresh feedback only for real changes on same account`() {
        val base = MascotBowlSnapshot("g1", "G1", 60)
        assertEquals(
            HomeMascotLine(R.string.home_mascot_ate_some, listOf(12)),
            HomeMascotChatter.changeLine(base, base.copy(remainingPercent = 48)),
        )
        val refilled = HomeMascotChatter.changeLine(base.copy(remainingPercent = 5), base)
        assertEquals(R.string.home_mascot_refilled, refilled?.resId)
        assertNull(HomeMascotChatter.changeLine(base, base.copy(remainingPercent = 57)))
        assertNull(HomeMascotChatter.changeLine(null, base))
    }
}
