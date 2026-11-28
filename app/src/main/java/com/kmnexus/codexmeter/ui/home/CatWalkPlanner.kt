package com.kmnexus.codexmeter.ui.home

import kotlin.math.abs
import kotlin.random.Random

/**
 * 額度貓散步的節奏，抽成純函式方便用固定亂數測試。
 * 座標單位跟貓的畫布一樣（64 單位＝舞台高度），x=0 是坐在碗旁邊的「家」。
 *
 * 規則參考桌寵的「行為權重表」：發呆一陣子 → 擲骰決定回碗邊或去別處 → 走過去 → 再發呆。
 * 安靜優先：每次發呆 6～14 秒，不會一直跑來跑去。
 */
object CatWalkPlanner {
    data class Step(val idleMillis: Long, val targetX: Float, val walkMillis: Long)

    private const val MIN_IDLE_MILLIS = 6_000L
    private const val MAX_IDLE_MILLIS = 14_000L

    /** 回碗邊（家）的機率。 */
    private const val GO_HOME_CHANCE = 0.4f

    /** 每次至少走這麼遠，不然看起來像在原地抖。 */
    const val MIN_DISTANCE = 20f

    /** 走路速度：每秒幾個畫布單位（約 30dp）。 */
    private const val UNITS_PER_SECOND = 25f
    private const val MIN_WALK_MILLIS = 1_500L
    private const val MAX_WALK_MILLIS = 6_000L

    fun next(random: Random, currentX: Float, maxX: Float): Step {
        val idle = random.nextLong(MIN_IDLE_MILLIS, MAX_IDLE_MILLIS + 1)
        val target = pickTarget(random, currentX, maxX.coerceAtLeast(0f))
        return Step(idle, target, walkMillis(currentX, target))
    }

    fun walkMillis(fromX: Float, toX: Float): Long =
        (abs(toX - fromX) / UNITS_PER_SECOND * MILLIS_PER_SECOND).toLong().coerceIn(MIN_WALK_MILLIS, MAX_WALK_MILLIS)

    private fun pickTarget(random: Random, currentX: Float, maxX: Float): Float {
        // 舞台太窄或貓本來就不在家：有機率先回碗邊
        val goHome = maxX < MIN_DISTANCE || (currentX >= MIN_DISTANCE && random.nextFloat() < GO_HOME_CHANCE)
        // 在可走範圍裡挑一個離現在夠遠的點；挑不到就往另一端走
        val wander = generateSequence { random.nextFloat() * maxX }
            .take(MAX_TRIES)
            .firstOrNull { abs(it - currentX) >= MIN_DISTANCE }
            ?: if (currentX > maxX / 2) 0f else maxX
        return if (goHome) 0f else wander
    }

    private const val MAX_TRIES = 5
    private const val MILLIS_PER_SECOND = 1000f
}
