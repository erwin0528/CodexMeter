package com.kmnexus.codexmeter.ui.home

import com.kmnexus.codexmeter.R
import java.time.Instant
import org.junit.Assert.assertEquals
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
}
