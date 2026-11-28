package com.kmnexus.codexmeter.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.cat.CatMemory
import com.kmnexus.codexmeter.domain.cat.CatMemoryStore
import com.kmnexus.codexmeter.domain.cat.CatSnack
import java.time.LocalDateTime
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 舞台上會改到記憶的互動：餵食、摸頭、換點心。 */
internal class CatActions(
    val snack: CatSnack,
    val feed: () -> Unit,
    val pet: () -> Unit,
    val switchSnack: () -> Unit,
)

@Composable
internal fun rememberCatActions(
    interaction: MascotInteraction,
    store: CatMemoryStore,
    memory: CatMemory,
    state: HomeMascotState,
    scope: CoroutineScope,
): CatActions {
    // 最近幾次餵食的時間，用來判斷「1 分鐘內連餵 3 次就吃到打嗝」
    val feedTimes = remember { mutableStateListOf<Long>() }
    val current by rememberUpdatedState(memory)
    val mood by rememberUpdatedState(state.mood)
    return CatActions(
        snack = memory.snack,
        feed = {
            val now = System.currentTimeMillis()
            feedTimes += now
            while (feedTimes.size > FEED_HISTORY) feedTimes.removeAt(0)
            val line = CatDialogue.feedLine(current.snack, CatDialogue.isFull(feedTimes, now))
            interaction.treat { line.line }
            scope.launch { store.update { it.fed().spoke(line.id) } }
        },
        pet = {
            interaction.pet { count -> HomeMascotChatter.petLine(mood, count) }
            scope.launch { store.update { it.petted() } }
        },
        switchSnack = {
            val next = current.snack.next()
            val name = HomeMascotLine(CatDialogue.snackNameRes(next))
            interaction.say(HomeMascotReaction.Talk, HomeMascotLine(R.string.home_cat_snack_switched, listOf(name)))
            scope.launch { store.update { it.switchedSnack() } }
        },
    )
}

/**
 * 貓自己碎念：沒人理牠的時候，每 25～40 秒輕輕講一句，最近講過的 8 句內不重複。
 * 只在 App 前景時才計時，退到背景就停，不額外耗電。
 */
@Suppress("FunctionNaming") // Compose 元件慣例用大寫開頭
@Composable
internal fun CatIdleChatter(interaction: MascotInteraction, store: CatMemoryStore, mood: HomeMascotMood) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val currentMood by rememberUpdatedState(mood)
    LaunchedEffect(active, store) {
        if (!active) return@LaunchedEffect
        val random = Random.Default
        while (true) {
            delay(random.nextLong(MIN_CHATTER_MILLIS, MAX_CHATTER_MILLIS))
            if (interaction.reaction != HomeMascotReaction.None) continue
            var picked: CatLine? = null
            store.update { memory ->
                val line = CatDialogue.pickIdle(memory, currentMood, LocalDateTime.now(), random)
                picked = line
                memory.spoke(line.id)
            }
            picked?.let { interaction.say(HomeMascotReaction.Talk, it.line) }
        }
    }
}

private const val FEED_HISTORY = 5
private const val MIN_CHATTER_MILLIS = 25_000L
private const val MAX_CHATTER_MILLIS = 40_000L
