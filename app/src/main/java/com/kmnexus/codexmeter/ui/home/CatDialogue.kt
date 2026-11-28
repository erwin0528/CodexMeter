package com.kmnexus.codexmeter.ui.home

import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.cat.CatMemory
import com.kmnexus.codexmeter.domain.cat.CatSnack
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.random.Random

/** 一句有代號的台詞：代號拿來記「最近講過」，避免一直重複。 */
data class CatLine(val id: String, val line: HomeMascotLine)

/**
 * 額度貓的本機對話庫（不連網、不用 AI 模型）。
 * 自己碎念時，從「心情台詞＋通用台詞＋時段台詞＋熟悉度解鎖台詞」裡挑一句，
 * 並排除最近講過的 8 句；全部都講過了才允許重複。
 */
object CatDialogue {
    private val moodLines: Map<HomeMascotMood, List<CatLine>> = mapOf(
        HomeMascotMood.Hungry to listOf(
            line("idle_hungry_1", R.string.home_cat_idle_hungry_1),
            line("idle_hungry_2", R.string.home_cat_idle_hungry_2),
        ),
        HomeMascotMood.Relaxed to listOf(
            line("idle_relaxed_1", R.string.home_cat_idle_relaxed_1),
            line("idle_relaxed_2", R.string.home_cat_idle_relaxed_2),
        ),
        HomeMascotMood.Stuffed to listOf(
            line("idle_stuffed_1", R.string.home_cat_idle_stuffed_1),
            line("idle_stuffed_2", R.string.home_cat_idle_stuffed_2),
        ),
        HomeMascotMood.AteAll to listOf(line("idle_ate_all_1", R.string.home_cat_idle_ate_all_1)),
    )

    private val anytimeLines = listOf(
        line("idle_any_1", R.string.home_cat_idle_any_1),
        line("idle_any_2", R.string.home_cat_idle_any_2),
        line("idle_any_3", R.string.home_cat_idle_any_3),
    )

    private val morningStart: LocalTime = LocalTime.of(MORNING_START_HOUR, 0)
    private val morningEnd: LocalTime = LocalTime.of(MORNING_END_HOUR, 0)

    /** 現在可以講的所有台詞（還沒排除最近講過的）。 */
    fun idleCandidates(memory: CatMemory, mood: HomeMascotMood, now: LocalDateTime): List<CatLine> = buildList {
        addAll(moodLines[mood].orEmpty())
        addAll(anytimeLines)
        val time = now.toLocalTime()
        if (!time.isBefore(morningStart) && time.isBefore(morningEnd)) {
            add(line("morning", R.string.home_cat_morning))
        }
        if (memory.level >= 1) {
            add(CatLine("level1", HomeMascotLine(R.string.home_cat_level1, listOf(memory.daysVisited))))
        }
        if (memory.level >= 2) add(line("level2", R.string.home_cat_level2))
        if (memory.level >= LEVEL_3) add(line("level3", R.string.home_cat_level3))
    }

    /** 自己碎念要講哪句：優先挑最近沒講過的。 */
    fun pickIdle(memory: CatMemory, mood: HomeMascotMood, now: LocalDateTime, random: Random): CatLine {
        val all = idleCandidates(memory, mood, now)
        val fresh = all.filterNot { it.id in memory.recentLineIds }
        return fresh.ifEmpty { all }.random(random)
    }

    /** 餵食台詞跟著點心變；1 分鐘內連餵 3 次就吃到打嗝。 */
    fun feedLine(snack: CatSnack, full: Boolean): CatLine = when {
        full -> line("full", R.string.home_cat_full)
        snack == CatSnack.DRIED_FISH -> line("feed_fish", R.string.home_cat_feed_fish)
        snack == CatSnack.CHURU -> line("feed_churu", R.string.home_cat_feed_churu)
        else -> line("feed_kibble", R.string.home_cat_feed_kibble)
    }

    fun snackNameRes(snack: CatSnack): Int = when (snack) {
        CatSnack.KIBBLE -> R.string.home_cat_snack_kibble
        CatSnack.DRIED_FISH -> R.string.home_cat_snack_fish
        CatSnack.CHURU -> R.string.home_cat_snack_churu
    }

    /** 最近幾次餵食的時間點（毫秒）裡，是否有 3 次落在 1 分鐘內。 */
    fun isFull(feedTimesMillis: List<Long>, nowMillis: Long): Boolean =
        feedTimesMillis.count { nowMillis - it in 0..FULL_WINDOW_MILLIS } >= FULL_FEEDS

    private fun line(id: String, resId: Int) = CatLine(id, HomeMascotLine(resId))

    private const val LEVEL_3 = 3
    private const val MORNING_START_HOUR = 6
    private const val MORNING_END_HOUR = 9
    private const val FULL_FEEDS = 3
    private const val FULL_WINDOW_MILLIS = 60_000L
}
