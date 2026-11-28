package com.kmnexus.codexmeter.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import com.kmnexus.codexmeter.ui.motion.rememberCodexMeterAnimatorsEnabled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 這次開 App 期間的互動次數；用 rememberSaveable 保存，旋轉螢幕不歸零。 */
internal class MascotCounters(val pets: MutableIntState, val chins: MutableIntState, val treats: MutableIntState)

/**
 * 額度貓的互動狀態：現在講哪句台詞、做什麼動作、動畫播到哪。
 * 台詞講完 [REACTION_MILLIS] 後自動回到心情台詞。
 */
@Stable
internal class MascotInteraction(
    private val scope: CoroutineScope,
    private val haptics: HapticFeedback,
    private val animate: Boolean,
    private val counters: MascotCounters,
) {
    var reaction by mutableStateOf(HomeMascotReaction.None)
        private set
    var line by mutableStateOf<HomeMascotLine?>(null)
        private set

    val petCount: Int get() = counters.pets.intValue

    private val hopAnim = Animatable(0f)
    private val effectAnim = Animatable(0f)
    private val tiltAnim = Animatable(0f)

    /** 跳起來的高度 0～1。 */
    val hop: Float get() = hopAnim.value

    /** 愛心、聲波、飼料掉落的播放進度 0～1。 */
    val effect: Float get() = effectAnim.value

    /** 嚕下巴時頭歪的方向與幅度，-1～1。 */
    val tilt: Float get() = tiltAnim.value

    private var resetJob: Job? = null

    fun pet(lineFor: (count: Int) -> HomeMascotLine) {
        counters.pets.intValue += 1
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        play(HomeMascotReaction.Pet, lineFor(counters.pets.intValue)) {
            launch {
                hopAnim.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                hopAnim.animateTo(0f)
            }
            effectAnim.snapTo(0f)
            effectAnim.animateTo(1f, tween(EFFECT_MILLIS, easing = LinearEasing))
        }
    }

    fun treat(lineFor: (count: Int) -> HomeMascotLine) {
        counters.treats.intValue += 1
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        play(HomeMascotReaction.Treat, lineFor(counters.treats.intValue)) {
            effectAnim.snapTo(0f)
            effectAnim.animateTo(1f, tween(TREAT_MILLIS, easing = LinearEasing))
        }
    }

    fun purr(purrLine: HomeMascotLine) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        play(HomeMascotReaction.Purr, purrLine) {
            repeat(PURR_WAVES) {
                effectAnim.snapTo(0f)
                effectAnim.animateTo(1f, tween(EFFECT_MILLIS, easing = LinearEasing))
            }
        }
    }

    /** 只換台詞不做動作：點文字看碗、打招呼、切帳號、刷新回饋都走這裡。 */
    fun say(reaction: HomeMascotReaction, spoken: HomeMascotLine) {
        play(reaction, spoken) {}
    }

    /** 手指左右滑：頭跟著歪，最多 ±1。 */
    fun chinDrag(fraction: Float) {
        if (reaction != HomeMascotReaction.Chin) {
            reaction = HomeMascotReaction.Chin
            resetJob?.cancel()
        }
        scope.launch { tiltAnim.snapTo((tiltAnim.value + fraction).coerceIn(-1f, 1f)) }
    }

    /** 放手：頭用彈簧彈回來，講一句嚕下巴台詞。 */
    fun chinRelease(lineFor: (count: Int) -> HomeMascotLine) {
        counters.chins.intValue += 1
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        play(HomeMascotReaction.Chin, lineFor(counters.chins.intValue)) {}
        scope.launch { tiltAnim.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
    }

    private fun play(next: HomeMascotReaction, spoken: HomeMascotLine, animation: suspend CoroutineScope.() -> Unit) {
        reaction = next
        line = spoken
        resetJob?.cancel()
        resetJob = scope.launch {
            // 系統關掉動畫時只換台詞，不播動作
            if (animate) launch { animation() }
            delay(REACTION_MILLIS)
            reaction = HomeMascotReaction.None
            line = null
            effectAnim.snapTo(0f)
        }
    }

    private companion object {
        const val REACTION_MILLIS = 3_500L
        const val EFFECT_MILLIS = 900
        const val TREAT_MILLIS = 700
        const val PURR_WAVES = 3
    }
}

@Composable
internal fun rememberMascotInteraction(): MascotInteraction {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val animate = rememberCodexMeterAnimatorsEnabled()
    val pets = rememberSaveable { mutableIntStateOf(0) }
    val chins = rememberSaveable { mutableIntStateOf(0) }
    val treats = rememberSaveable { mutableIntStateOf(0) }
    return remember(animate) { MascotInteraction(scope, haptics, animate, MascotCounters(pets, chins, treats)) }
}

/**
 * 貓圖整塊只給貓用（Android 建議可點元件至少 48dp，不再把 72dp 切成兩半）：
 * 點＝摸頭、雙擊＝加菜、長按＝呼嚕、左右滑＝嚕下巴。看碗改成點右邊的文字。
 */
internal fun Modifier.mascotGestures(
    interaction: MascotInteraction,
    onPet: () -> Unit,
    onTreat: () -> Unit,
    onPurr: () -> Unit,
    onChinRelease: () -> Unit,
): Modifier = this
    .pointerInput(interaction) {
        detectTapGestures(
            onTap = { onPet() },
            onDoubleTap = { onTreat() },
            onLongPress = { onPurr() },
        )
    }
    .pointerInput(interaction) {
        detectHorizontalDragGestures(
            onDragEnd = { onChinRelease() },
            onDragCancel = { onChinRelease() },
        ) { change, dragAmount ->
            change.consume()
            interaction.chinDrag(dragAmount / size.width * CHIN_SENSITIVITY)
        }
    }

private const val CHIN_SENSITIVITY = 2f
