package com.kmnexus.codexmeter.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 貓的位置與走路。位置用 Animatable，只在畫布繪製時讀取，不會讓整個首頁重組。
 * 只有「可以走」（特效全開、不是深夜、App 在前景）時才跑散步迴圈；
 * App 退到背景（不是 RESUMED）迴圈立刻停，回來再重新開始，不留任何計時器。
 */
@Stable
internal class CatWalker(private val scope: CoroutineScope) {
    private val position = Animatable(0f)
    var facingLeft by mutableStateOf(false)
        private set

    val x: Float get() = position.value

    /** 被叫回碗邊（例如加菜）：小跑步回家。 */
    fun goHome() = walkTo(0f)

    fun walkTo(target: Float) {
        scope.launch { move(target, CatWalkPlanner.walkMillis(position.value, target) / RUN_SPEEDUP) }
    }

    suspend fun move(target: Float, millis: Long) {
        if (target == position.value) return
        facingLeft = target < position.value
        try {
            position.animateTo(target, tween(millis.toInt(), easing = LinearEasing))
        } catch (interrupted: CancellationException) {
            // 被別的移動（例如點碗叫回家）打斷：停在當下位置就好，不算錯誤。
            // 外層協程真的被取消時要照常往上丟，散步迴圈才會停。
            scope.coroutineContext[kotlinx.coroutines.Job]?.takeIf { !it.isActive }?.let { throw interrupted }
        }
    }

    suspend fun snapHome() = position.snapTo(0f)

    private companion object {
        const val RUN_SPEEDUP = 2
    }
}

@Composable
internal fun rememberCatWalker(canWalk: Boolean, maxX: Float): CatWalker {
    val scope = rememberCoroutineScope()
    val walker = remember { CatWalker(scope) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = canWalk && lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && maxX > 0f
    LaunchedEffect(active, maxX) {
        if (!active) {
            // 不能走（深夜、特效調低）時回到碗邊坐好；只是退到背景就停在原地
            if (!canWalk) walker.snapHome()
            return@LaunchedEffect
        }
        val random = Random.Default
        while (true) {
            val step = CatWalkPlanner.next(random, walker.x, maxX)
            delay(step.idleMillis)
            walker.move(step.targetX, step.walkMillis)
        }
    }
    return walker
}

/** 舞台的手勢：依點的位置分給貓、碗或空地；左右滑一律算嚕下巴。 */
@Suppress("LongParameterList") // 每個參數是一種手勢的回呼
internal fun Modifier.catStageGestures(
    interaction: MascotInteraction,
    hitTest: PointerInputScope.(Float) -> StageTarget,
    onPet: () -> Unit,
    onFeed: () -> Unit,
    onPurr: () -> Unit,
    onCallTo: (Float) -> Unit,
    onChinRelease: () -> Unit,
): Modifier = this
    .pointerInput(interaction) {
        detectTapGestures(
            onTap = { offset ->
                when (val target = hitTest(offset.x)) {
                    StageTarget.Cat -> onPet()
                    StageTarget.Bowl -> onFeed()
                    is StageTarget.Floor -> onCallTo(target.unitX)
                }
            },
            onDoubleTap = { offset -> if (hitTest(offset.x) == StageTarget.Cat) onFeed() },
            onLongPress = { offset -> if (hitTest(offset.x) == StageTarget.Cat) onPurr() },
        )
    }
    .pointerInput(interaction) {
        detectHorizontalDragGestures(
            onDragEnd = { onChinRelease() },
            onDragCancel = { onChinRelease() },
        ) { change, dragAmount ->
            change.consume()
            interaction.chinDrag(dragAmount / size.width * CHIN_DRAG_SENSITIVITY)
        }
    }

// 舞台比原本的貓圖寬很多，靈敏度放大才會跟原本手感差不多
private const val CHIN_DRAG_SENSITIVITY = 8f
