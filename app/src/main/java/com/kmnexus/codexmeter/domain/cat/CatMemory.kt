package com.kmnexus.codexmeter.domain.cat

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** 三種點心，長按碗輪流換。 */
enum class CatSnack(val storageValue: String) {
    KIBBLE("kibble"),
    DRIED_FISH("dried_fish"),
    CHURU("churu"),
    ;

    fun next(): CatSnack = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromStorage(value: String?): CatSnack = entries.firstOrNull { it.storageValue == value } ?: KIBBLE
    }
}

/**
 * 額度貓的本機記憶，只存在手機裡、不上傳。
 * 只進不退、零懲罰：數字只會變多，沒來看牠也不會扣任何東西。
 */
data class CatMemory(
    val totalFeedCount: Int = 0,
    val totalPetCount: Int = 0,
    val daysVisited: Int = 0,
    val lastVisitDate: LocalDate? = null,
    /** 最近講過的台詞代號，最新的在最後；用來避免一直講同一句。 */
    val recentLineIds: List<String> = emptyList(),
    val snack: CatSnack = CatSnack.KIBBLE,
) {
    /** 熟悉程度 0～3：開 App 天數或餵食次數達標就升級，不會降。 */
    val level: Int
        get() = when {
            totalFeedCount >= LEVEL3_FEEDS -> MAX_LEVEL
            daysVisited >= LEVEL2_DAYS || totalFeedCount >= LEVEL2_FEEDS -> 2
            daysVisited >= LEVEL1_DAYS || totalFeedCount >= LEVEL1_FEEDS -> 1
            else -> 0
        }

    /** 今天第一次打開：天數加一。同一天開很多次只算一次。 */
    fun visited(today: LocalDate): CatMemory =
        if (lastVisitDate == today) this else copy(daysVisited = daysVisited + 1, lastVisitDate = today)

    fun fed(): CatMemory = copy(totalFeedCount = totalFeedCount + 1)

    fun petted(): CatMemory = copy(totalPetCount = totalPetCount + 1)

    fun spoke(lineId: String): CatMemory =
        copy(recentLineIds = (recentLineIds - lineId + lineId).takeLast(RECENT_LIMIT))

    fun switchedSnack(): CatMemory = copy(snack = snack.next())

    companion object {
        const val RECENT_LIMIT = 8
        const val MAX_LEVEL = 3
        private const val LEVEL1_DAYS = 3
        private const val LEVEL1_FEEDS = 10
        private const val LEVEL2_DAYS = 10
        private const val LEVEL2_FEEDS = 30
        private const val LEVEL3_FEEDS = 60
    }
}

/** 讀寫額度貓記憶。畫面層只透過這個介面，不直接碰 DataStore。 */
interface CatMemoryStore {
    val memory: Flow<CatMemory>
    suspend fun update(transform: (CatMemory) -> CatMemory)
}

/** 預覽、測試、或還沒接上正式存檔時用：只存在記憶體，關 App 就沒了。 */
class InMemoryCatMemoryStore(initial: CatMemory = CatMemory()) : CatMemoryStore {
    private val state = MutableStateFlow(initial)
    override val memory: Flow<CatMemory> = state

    override suspend fun update(transform: (CatMemory) -> CatMemory) = state.update(transform)
}
