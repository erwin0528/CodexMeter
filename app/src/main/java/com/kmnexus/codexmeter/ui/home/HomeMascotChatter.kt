package com.kmnexus.codexmeter.ui.home

import androidx.annotation.StringRes
import com.kmnexus.codexmeter.R

/** 跟額度貓互動的三種方式：點貓摸頭、點碗看剩多少、長按聽呼嚕。 */
enum class HomeMascotReaction { None, Pet, Bowl, Purr }

/** 一句台詞：字串資源加上要填進去的參數（剩餘 %、補貨時間）。 */
data class HomeMascotLine(@get:StringRes val resId: Int, val args: List<Any> = emptyList())

/**
 * 額度貓的台詞規則，抽成純函式方便測試。
 * 摸頭的台詞跟著心情走（餓的時候會叫你去用額度），同一次心情裡輪流講兩句，摸太多次就趕人去用額度。
 */
object HomeMascotChatter {
    /** 今天摸到第幾下開始趕人。 */
    const val PET_ENOUGH_COUNT = 10

    private val petLines: Map<HomeMascotMood, Pair<Int, Int>> = mapOf(
        HomeMascotMood.Hungry to (R.string.home_mascot_pet_hungry_1 to R.string.home_mascot_pet_hungry_2),
        HomeMascotMood.Relaxed to (R.string.home_mascot_pet_relaxed_1 to R.string.home_mascot_pet_relaxed_2),
        HomeMascotMood.Stuffed to (R.string.home_mascot_pet_stuffed_1 to R.string.home_mascot_pet_stuffed_2),
        HomeMascotMood.AteAll to (R.string.home_mascot_pet_ate_all_1 to R.string.home_mascot_pet_ate_all_2),
        HomeMascotMood.Locked to (R.string.home_mascot_pet_locked_1 to R.string.home_mascot_pet_locked_2),
        HomeMascotMood.Waiting to (R.string.home_mascot_pet_waiting_1 to R.string.home_mascot_pet_waiting_2),
    )

    /** petCount 從 1 開始算，代表這是第幾下摸頭。 */
    fun petLine(mood: HomeMascotMood, petCount: Int): HomeMascotLine {
        if (petCount >= PET_ENOUGH_COUNT) return HomeMascotLine(R.string.home_mascot_pet_enough)
        val (first, second) = petLines.getValue(mood)
        return HomeMascotLine(if (petCount % 2 == 1) first else second)
    }

    /** 點碗：直接報剩多少，有重置時間就順便報補貨時間。formattedReset 由畫面層格式化好再傳進來。 */
    fun bowlLine(state: HomeMascotState, formattedReset: String?): HomeMascotLine {
        val remaining = state.remainingPercent
        return when {
            remaining == null || remaining <= 0 -> HomeMascotLine(R.string.home_mascot_bowl_empty)
            formattedReset != null -> HomeMascotLine(R.string.home_mascot_bowl_reset, listOf(remaining, formattedReset))
            else -> HomeMascotLine(R.string.home_mascot_bowl, listOf(remaining))
        }
    }
}
