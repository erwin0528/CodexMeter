package com.kmnexus.codexmeter.ui.home

import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.ui.components.LiquidGlassSurfaceRole
import com.kmnexus.codexmeter.ui.components.QmLiquidGlassSurface
import com.kmnexus.codexmeter.ui.motion.rememberCodexMeterAnimatorsEnabled
import com.kmnexus.codexmeter.ui.theme.CodexMeterSpacing
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme
import com.kmnexus.codexmeter.widget.overview.QuotaPace
import com.kmnexus.codexmeter.widget.overview.QuotaPaceCalculator
import java.time.Duration
import java.time.Instant

/**
 * 額度貓：額度＝碗裡的飼料，目標是重置前吃光光。
 * 心情直接沿用小工具的用量節奏（QuotaPaceCalculator），兩邊才不會一個說快去用、一個說剛好。
 */
enum class HomeMascotMood(
    @get:StringRes val lineResId: Int,
    /** 碗裡飼料的高度（0～1），純畫面用。 */
    val bowlFill: Float,
) {
    Hungry(R.string.home_mascot_hungry, 0.95f),
    Relaxed(R.string.home_mascot_relaxed, 0.55f),
    Stuffed(R.string.home_mascot_stuffed, 0.2f),
    AteAll(R.string.home_mascot_ate_all, 0f),
    Locked(R.string.home_mascot_locked, 0.6f),
    Waiting(R.string.home_mascot_waiting, 0.4f),
}

data class HomeMascotState(
    val mood: HomeMascotMood,
    val refillAt: Instant? = null,
    /** 點碗時要報的數字：主要視窗剩幾 %、什麼時候重置。 */
    val remainingPercent: Int? = null,
    val resetAt: Instant? = null,
)

object HomeMascotMoodResolver {
    fun resolve(status: HomeContentStatus, cards: List<HomeQuotaCardUi>, now: Instant): HomeMascotState {
        if (status == HomeContentStatus.AuthRequired || status == HomeContentStatus.Expired) {
            return HomeMascotState(HomeMascotMood.Locked)
        }
        val percentCards = cards.filter { it.percent != null }
        val card = percentCards.firstOrNull { it.isPrimary } ?: percentCards.firstOrNull()
            ?: return HomeMascotState(HomeMascotMood.Waiting)
        val remaining = card.percent ?: return HomeMascotState(HomeMascotMood.Waiting)
        if (remaining <= 0 || card.status == HomeQuotaStatus.Exhausted) {
            return HomeMascotState(
                HomeMascotMood.AteAll,
                refillAt = card.resetAt,
                remainingPercent = 0,
                resetAt = card.resetAt,
            )
        }
        val timeLeft = card.timeLeftFraction(now)
            // 沒重置時間又是滿的＝還沒開始計時（Gemini 沒用之前就這樣），碗是滿的
            ?: return HomeMascotState(
                if (remaining >= 100) HomeMascotMood.Hungry else HomeMascotMood.Waiting,
                remainingPercent = remaining,
            )
        val mood = when (QuotaPaceCalculator.pace(remaining, timeLeft)) {
            QuotaPace.UnderUsing -> HomeMascotMood.Hungry
            QuotaPace.OverUsing -> HomeMascotMood.Stuffed
            QuotaPace.Depleted -> HomeMascotMood.AteAll
            QuotaPace.OnTrack, QuotaPace.Unknown -> HomeMascotMood.Relaxed
        }
        return HomeMascotState(
            mood,
            refillAt = card.resetAt.takeIf { mood == HomeMascotMood.AteAll },
            remainingPercent = remaining,
            resetAt = card.resetAt,
        )
    }

    private fun HomeQuotaCardUi.timeLeftFraction(now: Instant): Float? {
        val reset = resetAt ?: return null
        val windowSeconds = limitWindowSeconds?.takeIf { it > 0 } ?: return null
        return (Duration.between(now, reset).seconds.toFloat() / windowSeconds).coerceIn(0f, 1f)
    }
}

@Composable
internal fun HomeMascotCard(state: HomeMascotState) {
    val interaction = rememberMascotInteraction(state.mood)
    val moodLine = if (state.mood == HomeMascotMood.AteAll && state.refillAt != null) {
        stringResource(R.string.home_mascot_ate_all_refill, formattedInstant(state.refillAt))
    } else {
        stringResource(state.mood.lineResId)
    }
    val reactionLine = when (interaction.reaction) {
        HomeMascotReaction.Pet -> HomeMascotChatter.petLine(state.mood, interaction.petCount)
        HomeMascotReaction.Bowl -> HomeMascotChatter.bowlLine(state, state.resetAt?.let { formattedInstant(it) })
        HomeMascotReaction.Purr -> HomeMascotLine(R.string.home_mascot_purr)
        HomeMascotReaction.None -> null
    }
    val line = reactionLine?.let { reactionText(it) } ?: moodLine
    QmLiquidGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        role = LiquidGlassSurfaceRole.Card,
        cornerRadius = 28.dp,
        contentPadding = PaddingValues(horizontal = CodexMeterSpacing.lg, vertical = CodexMeterSpacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
        ) {
            QuotaCat(
                mood = state.mood,
                interaction = interaction,
                modifier = Modifier
                    .size(72.dp)
                    .semantics { contentDescription = line }
                    .mascotGestures(interaction),
            )
            Column(verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.xs)) {
                Text(
                    text = stringResource(R.string.home_mascot_name),
                    style = MaterialTheme.typography.labelSmall,
                    color = CodexMeterTheme.colors.secondary,
                    maxLines = 1,
                )
                Text(
                    text = line,
                    style = MaterialTheme.typography.titleSmall,
                    color = CodexMeterTheme.colors.primary,
                    maxLines = 2,
                )
                Text(
                    text = stringResource(R.string.home_mascot_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = CodexMeterTheme.colors.secondary.copy(alpha = 0.7f),
                    maxLines = 2,
                )
            }
        }
    }
}

/** 動畫參數：關掉系統動畫時全部停在中間值。 */
private class CatMotion(val tail: State<Float>, val blink: State<Float>, val bob: State<Float>)

@Composable
private fun rememberCatMotion(enabled: Boolean): CatMotion {
    val transition = rememberInfiniteTransition(label = "quota-cat")
    val tail = transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "tail",
    )
    // 大部分時間睜眼，每 4 秒眨一下
    val blink = transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 4000
                1f at 3700
                0.1f at 3820
                1f at 3950
            },
        ),
        label = "blink",
    )
    // 餓的時候拍碗、吃太快時氣泡脹縮，共用同一個節拍（3 秒一次，不急躁）
    val bob = transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 3000
                0f at 0
                1f at 250
                0f at 500
                1f at 750
                0f at 1000
            },
        ),
        label = "bob",
    )
    return remember(enabled, tail, blink, bob) {
        if (enabled) {
            CatMotion(tail, blink, bob)
        } else {
            val still = object : State<Float> { override val value = 0f }
            val open = object : State<Float> { override val value = 1f }
            CatMotion(still, open, still)
        }
    }
}

/** 原創線條貓，坐在飼料碗左邊。座標以 64×64 為底再縮放。 */
@Composable
private fun QuotaCat(mood: HomeMascotMood, interaction: MascotInteraction, modifier: Modifier = Modifier) {
    val colors = CodexMeterTheme.colors
    val ink = colors.primary
    val bodyFill = colors.surface.copy(alpha = 0.55f)
    val foodColor = when (mood) {
        HomeMascotMood.Hungry -> colors.accent
        HomeMascotMood.Relaxed -> colors.success
        HomeMascotMood.Stuffed -> colors.warning
        HomeMascotMood.AteAll -> colors.danger
        HomeMascotMood.Locked, HomeMascotMood.Waiting -> colors.secondary
    }
    val motion = rememberCatMotion(rememberCodexMeterAnimatorsEnabled())

    Canvas(modifier = modifier) {
        val unit = size.minDimension / 64f
        scale(unit, unit, pivot = Offset.Zero) {
            val stroke = Stroke(width = 1.8f, cap = StrokeCap.Round)
            val purring = interaction.reaction == HomeMascotReaction.Purr
            // 摸頭時整隻貓往上跳一下；呼嚕時尾巴甩得比較大
            translate(top = -5f * interaction.hop.value) {
                drawTail(ink, stroke, swing = motion.tail.value * if (purring) 1.8f else 1f)
                drawBody(ink, bodyFill, stroke)
                drawHead(
                    // 呼嚕時一律瞇眼
                    if (purring) HomeMascotMood.Relaxed else mood,
                    ink,
                    bodyFill,
                    stroke,
                    blink = motion.blink.value,
                )
            }
            if (interaction.reaction == HomeMascotReaction.Pet) {
                drawHeart(colors.danger, rise = interaction.effect.value)
            }
            if (purring) drawPurrWaves(ink, spread = interaction.effect.value)
            drawBowl(mood, ink, foodColor, stroke)
            when (mood) {
                HomeMascotMood.Hungry -> drawPaw(ink, bodyFill, stroke, reach = motion.bob.value)
                HomeMascotMood.Stuffed -> drawBubble(ink, grow = motion.bob.value)
                HomeMascotMood.AteAll -> drawSparkles(foodColor, twinkle = motion.tail.value)
                else -> Unit
            }
        }
    }
}

private fun DrawScope.drawTail(ink: Color, stroke: Stroke, swing: Float) {
    rotate(degrees = swing * 12f, pivot = Offset(26f, 56f)) {
        val tail = Path().apply {
            moveTo(26f, 56f)
            cubicTo(36f, 58f, 38f, 48f, 33f, 44f)
        }
        drawPath(tail, ink, style = stroke)
    }
}

private fun DrawScope.drawBody(ink: Color, fill: Color, stroke: Stroke) {
    val body = Path().apply {
        moveTo(9f, 58f)
        cubicTo(7f, 46f, 11f, 34f, 17f, 33f)
        lineTo(23f, 33f)
        cubicTo(29f, 34f, 32f, 46f, 30f, 58f)
        close()
    }
    drawPath(body, fill)
    drawPath(body, ink, style = stroke)
}

private fun DrawScope.drawHead(mood: HomeMascotMood, ink: Color, fill: Color, stroke: Stroke, blink: Float) {
    // 登入失效時歪頭
    val tilt = if (mood == HomeMascotMood.Locked) -12f else 0f
    rotate(degrees = tilt, pivot = Offset(20f, 24f)) {
        val head = Path().apply {
            moveTo(10f, 30f)
            cubicTo(8f, 24f, 9f, 18f, 11f, 14f) // 左耳外側
            lineTo(15f, 18f)
            cubicTo(18f, 17f, 22f, 17f, 25f, 18f)
            lineTo(29f, 14f) // 右耳尖
            cubicTo(31f, 18f, 32f, 24f, 30f, 30f)
            cubicTo(27f, 34f, 13f, 34f, 10f, 30f)
            close()
        }
        drawPath(head, fill)
        drawPath(head, ink, style = stroke)
        val happy = mood == HomeMascotMood.Relaxed || mood == HomeMascotMood.AteAll
        if (happy) {
            // 瞇眼：向上彎的弧線
            drawArc(ink, 180f, 180f, false, Offset(14f, 23f), Size(4f, 3f), style = stroke)
            drawArc(ink, 180f, 180f, false, Offset(22f, 23f), Size(4f, 3f), style = stroke)
        } else {
            val eyeHeight = 3f * blink.coerceIn(0.1f, 1f)
            drawOval(ink, Offset(15f, 25.5f - eyeHeight / 2), Size(2.4f, eyeHeight))
            drawOval(ink, Offset(22.6f, 25.5f - eyeHeight / 2), Size(2.4f, eyeHeight))
        }
        // 小鼻子
        drawCircle(ink, radius = 0.8f, center = Offset(20f, 28.5f))
    }
    if (mood == HomeMascotMood.Locked) drawQuestionMark(ink, stroke)
}

private fun DrawScope.drawBowl(mood: HomeMascotMood, ink: Color, food: Color, stroke: Stroke) {
    val bowl = Path().apply {
        moveTo(36f, 50f)
        lineTo(62f, 50f)
        cubicTo(61f, 57f, 57f, 60f, 49f, 60f)
        cubicTo(41f, 60f, 37f, 57f, 36f, 50f)
        close()
    }
    if (mood.bowlFill > 0f) {
        // 飼料從碗口往上堆，最高堆出碗口 4 單位
        val top = 50f - 4f * mood.bowlFill
        val pile = Path().apply {
            moveTo(38f, 50f)
            cubicTo(41f, top, 57f, top, 60f, 50f)
            close()
        }
        drawPath(pile, food.copy(alpha = 0.85f))
    }
    clipPath(bowl) { drawRect(food.copy(alpha = 0.18f), Offset(36f, 50f), Size(26f, 10f)) }
    drawPath(bowl, ink, style = stroke)
    if (mood == HomeMascotMood.Locked) {
        // 透明蓋子
        drawArc(ink.copy(alpha = 0.6f), 180f, 180f, false, Offset(35f, 41f), Size(28f, 16f), style = stroke)
    }
}

private fun DrawScope.drawPaw(ink: Color, fill: Color, stroke: Stroke, reach: Float) {
    val center = Offset(31f + 4f * reach, 50f - 2f * reach)
    drawCircle(fill, radius = 2.6f, center = center)
    drawCircle(ink, radius = 2.6f, center = center, style = stroke)
}

private fun DrawScope.drawBubble(ink: Color, grow: Float) {
    drawCircle(ink.copy(alpha = 0.5f), radius = 2.5f + 1.2f * grow, center = Offset(36f, 12f), style = Stroke(1.2f))
    drawCircle(ink.copy(alpha = 0.4f), radius = 1.2f, center = Offset(32f, 18f), style = Stroke(1.2f))
}

private fun DrawScope.drawSparkles(color: Color, twinkle: Float) {
    val alpha = 0.45f + 0.35f * ((twinkle + 1f) / 2f)
    listOf(Offset(38f, 14f), Offset(46f, 22f), Offset(54f, 12f)).forEach { c ->
        drawLine(color.copy(alpha = alpha), Offset(c.x - 2f, c.y), Offset(c.x + 2f, c.y), 1.4f, StrokeCap.Round)
        drawLine(color.copy(alpha = alpha), Offset(c.x, c.y - 2f), Offset(c.x, c.y + 2f), 1.4f, StrokeCap.Round)
    }
}

private fun DrawScope.drawQuestionMark(ink: Color, stroke: Stroke) {
    drawArc(ink, 180f, 250f, false, Offset(33f, 8f), Size(6f, 6f), style = stroke)
    drawLine(ink, Offset(36.5f, 14f), Offset(36f, 16f), 1.8f, StrokeCap.Round)
    drawCircle(ink, radius = 0.9f, center = Offset(36f, 19f))
}

/** 摸頭的小愛心：從頭頂往上飄並淡出。rise 0→1。 */
private fun DrawScope.drawHeart(color: Color, rise: Float) {
    if (rise <= 0f || rise >= 1f) return
    val y = 12f - 10f * rise
    val heart = Path().apply {
        moveTo(33f, y + 3f)
        cubicTo(29f, y, 30f, y - 3f, 33f, y - 1.2f)
        cubicTo(36f, y - 3f, 37f, y, 33f, y + 3f)
        close()
    }
    drawPath(heart, color.copy(alpha = 1f - rise))
}

/** 呼嚕的聲波：嘴巴旁邊往外擴的兩道弧線。spread 0→1。 */
private fun DrawScope.drawPurrWaves(ink: Color, spread: Float) {
    if (spread <= 0f || spread >= 1f) return
    val alpha = PURR_WAVE_ALPHA * (1f - spread)
    listOf(0f, PURR_WAVE_LAG).forEach { lag ->
        val r = 3f + PURR_WAVE_GROWTH * ((spread + lag) % 1f)
        drawArc(
            ink.copy(alpha = alpha),
            startAngle = -40f,
            sweepAngle = 80f,
            useCenter = false,
            topLeft = Offset(30f - r, 26f - r),
            size = Size(2 * r, 2 * r),
            style = Stroke(1.2f, cap = StrokeCap.Round),
        )
    }
}

private const val PURR_WAVE_ALPHA = 0.6f
private const val PURR_WAVE_LAG = 0.5f
private const val PURR_WAVE_GROWTH = 6f

/** 台詞最多帶兩個參數（剩餘 %、補貨時間），分開呼叫避免展開陣列。 */
@Composable
private fun reactionText(line: HomeMascotLine): String = when (line.args.size) {
    0 -> stringResource(line.resId)
    1 -> stringResource(line.resId, line.args[0])
    else -> stringResource(line.resId, line.args[0], line.args[1])
}
