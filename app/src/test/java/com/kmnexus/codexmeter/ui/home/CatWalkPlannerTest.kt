package com.kmnexus.codexmeter.ui.home

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatWalkPlannerTest {
    @Test
    fun `targets stay on the stage and are far enough to look like walking`() {
        val random = Random(42)
        var x = 0f
        repeat(500) {
            val step = CatWalkPlanner.next(random, x, maxX = 200f)
            assertTrue(step.targetX in 0f..200f)
            assertTrue(step.idleMillis in 6_000L..14_000L)
            assertTrue(step.walkMillis in 1_500L..6_000L)
            // 回家（x=0）或走夠遠
            assertTrue(step.targetX == 0f || abs(step.targetX - x) >= CatWalkPlanner.MIN_DISTANCE)
            x = step.targetX
        }
    }

    @Test
    fun `cat goes home sometimes but not always`() {
        val random = Random(7)
        var homeVisits = 0
        var x = 150f
        repeat(300) {
            val step = CatWalkPlanner.next(random, x, maxX = 200f)
            if (step.targetX == 0f) homeVisits++
            x = step.targetX
        }
        assertTrue(homeVisits in 30..250)
    }

    @Test
    fun `narrow stage keeps the cat by the bowl`() {
        val step = CatWalkPlanner.next(Random(1), currentX = 0f, maxX = 10f)
        assertEquals(0f, step.targetX)
    }

    @Test
    fun `walk time scales with distance within limits`() {
        assertEquals(4_000L, CatWalkPlanner.walkMillis(0f, 100f))
        assertEquals(1_500L, CatWalkPlanner.walkMillis(0f, 5f))
        assertEquals(6_000L, CatWalkPlanner.walkMillis(0f, 1_000f))
    }
}

class StageTargetTest {
    @Test
    fun `taps are routed to cat, bowl or floor`() {
        // 貓在家（x=0）：貓身 2～38、碗 34～64，重疊處算貓
        org.junit.Assert.assertEquals(StageTarget.Cat, stageTarget(20f, catX = 0f))
        org.junit.Assert.assertEquals(StageTarget.Cat, stageTarget(36f, catX = 0f))
        org.junit.Assert.assertEquals(StageTarget.Bowl, stageTarget(50f, catX = 0f))
        org.junit.Assert.assertEquals(StageTarget.Floor(150f), stageTarget(150f, catX = 0f))
        // 貓走到右邊：點碗就是碗，點貓跟著貓的位置走
        org.junit.Assert.assertEquals(StageTarget.Bowl, stageTarget(50f, catX = 120f))
        org.junit.Assert.assertEquals(StageTarget.Cat, stageTarget(140f, catX = 120f))
    }
}
