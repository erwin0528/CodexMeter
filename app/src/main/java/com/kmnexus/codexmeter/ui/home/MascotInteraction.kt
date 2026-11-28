package com.kmnexus.codexmeter.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
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

/**
 * 額度貓的互動狀態。台詞講完 [REACTION_MILLIS] 後自動回到心情台詞，
 * 摸頭次數存在 rememberSaveable，旋轉螢幕不會歸零，關掉 App 才重算。
 */
@Stable
internal class MascotInteraction(
    private val scope: CoroutineScope,
    private val haptics: HapticFeedback,
    private val animate: Boolean,
    petCountState: androidx.compose.runtime.MutableIntState,
) {
    var reaction by mutableStateOf(HomeMascotReaction.None)
        private set
    private var petCountHolder = petCountState
    val petCount: Int get() = petCountHolder.intValue

    private val hopAnim = Animatable(0f)
    private val effectAnim = Animatable(0f)

    /** 跳起來的高度 0～1。 */
    val hop: State<Float> get() = hopAnim.asState()

    /** 愛心、聲波的播放進度 0～1。 */
    val effect: State<Float> get() = effectAnim.asState()

    private var resetJob: Job? = null

    fun pet() {
        petCountHolder.intValue += 1
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        play(HomeMascotReaction.Pet) {
            launch {
                hopAnim.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                hopAnim.animateTo(0f)
            }
            effectAnim.snapTo(0f)
            effectAnim.animateTo(1f, tween(EFFECT_MILLIS, easing = LinearEasing))
        }
    }

    fun checkBowl() {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        play(HomeMascotReaction.Bowl) {}
    }

    fun purr() {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        play(HomeMascotReaction.Purr) {
            repeat(PURR_WAVES) {
                effectAnim.snapTo(0f)
                effectAnim.animateTo(1f, tween(EFFECT_MILLIS, easing = LinearEasing))
            }
        }
    }

    private fun play(next: HomeMascotReaction, animation: suspend CoroutineScope.() -> Unit) {
        reaction = next
        resetJob?.cancel()
        resetJob = scope.launch {
            // 系統關掉動畫時只換台詞，不播動作
            if (animate) launch { animation() }
            delay(REACTION_MILLIS)
            reaction = HomeMascotReaction.None
            effectAnim.snapTo(0f)
        }
    }

    private companion object {
        const val REACTION_MILLIS = 3_500L
        const val EFFECT_MILLIS = 900
        const val PURR_WAVES = 3
    }
}

@Composable
internal fun rememberMascotInteraction(mood: HomeMascotMood): MascotInteraction {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val animate = rememberCodexMeterAnimatorsEnabled()
    val petCount = rememberSaveable { mutableIntStateOf(0) }
    // 心情變了（例如刷新後從「快過期」變「吃光光」）就換一組新的互動狀態，台詞才不會卡在舊心情
    return remember(mood, animate) { MascotInteraction(scope, haptics, animate, petCount) }
}

/** 點左半邊＝摸頭，點右半邊（碗的位置）＝看剩多少，長按＝呼嚕。 */
internal fun Modifier.mascotGestures(interaction: MascotInteraction): Modifier = pointerInput(interaction) {
    detectTapGestures(
        onTap = { offset -> if (offset.x > size.width * BOWL_SPLIT) interaction.checkBowl() else interaction.pet() },
        onLongPress = { interaction.purr() },
    )
}

private const val BOWL_SPLIT = 0.55f
