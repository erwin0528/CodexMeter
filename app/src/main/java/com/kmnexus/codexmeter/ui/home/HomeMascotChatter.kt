package com.kmnexus.codexmeter.ui.home

import androidx.annotation.StringRes
import com.kmnexus.codexmeter.R
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 額度貓當下在做的動作。台詞另外存，動作只決定畫面怎麼動：
 * 摸頭跳一下、呼嚕瞇眼、嚕下巴歪頭、加菜丟飼料，其他（打招呼、報碗況）只換台詞。
 */
enum class HomeMascotReaction { None, Pet, Bowl, Purr, Chin, Treat, Talk }

/** 用來比較「上次看到」跟「這次看到」的碗：哪個帳號、叫什麼、剩幾 %。 */
data class MascotBowlSnapshot(val accountKey: String, val accountName: String, val remainingPercent: Int?)

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

    /** 剛用掉多少才算「有吃有差」、一次漲多少才算「補貨」。 */
    const val ATE_THRESHOLD = 5
    const val REFILL_THRESHOLD = 20

    private val afterWorkStart: LocalTime = LocalTime.parse("18:00")
    private val afterWorkEnd: LocalTime = LocalTime.parse("20:30")
    private val lateNightStart: LocalTime = LocalTime.parse("23:00")
    private val lateNightEnd: LocalTime = LocalTime.parse("05:00")

    /** 深夜（23:00～05:00）：貓會想睡，台詞也叫你去睡。 */
    fun isLateNight(now: LocalDateTime): Boolean {
        val time = now.toLocalTime()
        return !time.isBefore(lateNightStart) || time.isBefore(lateNightEnd)
    }

    /**
     * 打開 App 時的時段問候。深夜優先（就算週日也叫你睡），
     * 下班時段只算週一到週六，週日白天講放假。其他時間不打招呼，直接講碗況。
     */
    fun greetingLine(now: LocalDateTime): HomeMascotLine? {
        val time = now.toLocalTime()
        return when {
            isLateNight(now) -> HomeMascotLine(R.string.home_mascot_greet_late_night)
            now.dayOfWeek == DayOfWeek.SUNDAY -> HomeMascotLine(R.string.home_mascot_greet_sunday)
            !time.isBefore(afterWorkStart) && time.isBefore(afterWorkEnd) ->
                HomeMascotLine(R.string.home_mascot_greet_after_work)
            else -> null
        }
    }

    private val chinLines = listOf(
        R.string.home_mascot_chin_1,
        R.string.home_mascot_chin_2,
        R.string.home_mascot_chin_3,
    )

    /** 嚕下巴三句輪流講；count 從 1 開始。 */
    fun chinLine(count: Int): HomeMascotLine = HomeMascotLine(chinLines[(count - 1).mod(chinLines.size)])

    private val treatLines = listOf(
        R.string.home_mascot_treat_1,
        R.string.home_mascot_treat_2,
        R.string.home_mascot_treat_3,
    )

    /** 雙擊加菜：順便報碗裡剩多少，三句輪流。 */
    fun treatLine(count: Int, remainingPercent: Int?): HomeMascotLine {
        if (remainingPercent == null) return HomeMascotLine(R.string.home_mascot_treat_unknown)
        return HomeMascotLine(treatLines[(count - 1).mod(treatLines.size)], listOf(remainingPercent))
    }

    /**
     * 比較前後兩次的碗：換帳號就報新碗；同一個帳號剩餘掉了 5% 以上誇你有在用，
     * 漲了 20% 以上就是補貨了。變化太小不講話，免得每次刷新都吵。
     */
    fun changeLine(previous: MascotBowlSnapshot?, current: MascotBowlSnapshot): HomeMascotLine? {
        val now = current.remainingPercent
        val before = previous?.remainingPercent
        return when {
            previous == null -> null
            previous.accountKey != current.accountKey -> switchedLine(current)
            before == null || now == null -> null
            before - now >= ATE_THRESHOLD -> HomeMascotLine(R.string.home_mascot_ate_some, listOf(before - now))
            now - before >= REFILL_THRESHOLD -> HomeMascotLine(R.string.home_mascot_refilled)
            else -> null
        }
    }

    private fun switchedLine(current: MascotBowlSnapshot): HomeMascotLine =
        current.remainingPercent?.let { HomeMascotLine(R.string.home_mascot_switched, listOf(current.accountName, it)) }
            ?: HomeMascotLine(R.string.home_mascot_switched_unknown, listOf(current.accountName))
}
