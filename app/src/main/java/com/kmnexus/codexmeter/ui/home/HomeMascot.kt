package com.kmnexus.codexmeter.ui.home

import kotlin.math.sin
import kotlin.math.abs
import com.kmnexus.codexmeter.domain.theme.CatEffectsLevel
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import java.time.LocalDateTime

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

/**
 * 額度貓卡片。bowl 是「現在看的是哪個帳號的碗」，用來偵測切帳號與刷新前後的變化；
 * now 由外面傳入方便測試，預設用手機目前時間判斷時段。
 */
@Suppress("FunctionNaming") // Compose 元件慣例用大寫開頭
@Composable
internal fun HomeMascotCard(
    state: HomeMascotState,
    bowl: MascotBowlSnapshot? = null,
    now: LocalDateTime = LocalDateTime.now(),
) {
    val level = LocalCatEffects.current.respecting(rememberCodexMeterAnimatorsEnabled())
    if (level == CatEffectsLevel.HIDDEN) return
    val interaction = rememberMascotInteraction(effectsAllowAnimation = level.interactionAnimations)
    val moodLine = if (state.mood == HomeMascotMood.AteAll && state.refillAt != null) {
        stringResource(R.string.home_mascot_ate_all_refill, formattedInstant(state.refillAt))
    } else {
        stringResource(state.mood.lineResId)
    }
    val bowlLine = HomeMascotChatter.bowlLine(state, state.resetAt?.let { formattedInstant(it) })
    MascotAutoTalk(interaction, bowl, now)
    val line = interaction.line?.let { reactionText(it) } ?: moodLine
    val sleepy = HomeMascotChatter.isLateNight(now)
    QmLiquidGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        role = LiquidGlassSurfaceRole.Card,
        cornerRadius = 28.dp,
        contentPadding = PaddingValues(horizontal = CodexMeterSpacing.lg, vertical = CodexMeterSpacing.sm),
    ) {
        // 整張卡片高度固定：台詞列、舞台、提示列都鎖高度，台詞長短不會把下面的額度卡推來推去
        Column(modifier = Modifier.fillMaxWidth()) {
            MascotLineRow(line = line, onClick = { interaction.say(HomeMascotReaction.Bowl, bowlLine) })
            CatStage(
                state = state,
                interaction = interaction,
                level = level,
                sleepy = sleepy,
                line = line,
            )
            Text(
                text = stringResource(R.string.home_mascot_hint),
                style = MaterialTheme.typography.labelSmall,
                color = CodexMeterTheme.colors.secondary.copy(alpha = 0.7f),
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().height(HINT_ROW_HEIGHT),
            )
        }
    }
}

/** 單行台詞列：固定 28dp 高、只准一行，太長就用「…」收尾；整列可點，報碗況。 */
@Suppress("FunctionNaming") // Compose 元件慣例用大寫開頭
@Composable
private fun MascotLineRow(line: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(LINE_ROW_HEIGHT)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = line,
            style = MaterialTheme.typography.titleSmall,
            color = CodexMeterTheme.colors.primary,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/**
 * 貓的舞台：碗固定在左邊，貓可以在整條舞台上散步（特效「全開」才會走）。
 * 點貓＝摸頭、雙擊貓＝加菜、長按貓＝呼嚕、點碗＝加菜並叫貓回來吃、點空地＝叫貓走過去、左右滑＝嚕下巴。
 */
@Suppress("FunctionNaming") // Compose 元件慣例用大寫開頭
@Composable
private fun CatStage(
    state: HomeMascotState,
    interaction: MascotInteraction,
    level: CatEffectsLevel,
    sleepy: Boolean,
    line: String,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(STAGE_HEIGHT)) {
        val unitDp = STAGE_HEIGHT / CANVAS_UNITS
        val maxX = (maxWidth / unitDp - CAT_FOOTPRINT_UNITS).coerceAtLeast(0f)
        val walker = rememberCatWalker(canWalk = level.walks && !sleepy, maxX = maxX)
        val feed = {
            interaction.treat { count -> HomeMascotChatter.treatLine(count, state.remainingPercent) }
            walker.goHome()
        }
        QuotaCat(
            mood = state.mood,
            interaction = interaction,
            sleepy = sleepy,
            idleAnimations = level.idleAnimations,
            walkX = { walker.x },
            facingLeft = walker.facingLeft,
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = line }
                .catStageGestures(
                    interaction = interaction,
                    hitTest = { offsetPx -> stageTarget(offsetPx / (size.height / CANVAS_UNITS), walker.x) },
                    onPet = { interaction.pet { count -> HomeMascotChatter.petLine(state.mood, count) } },
                    onFeed = feed,
                    onPurr = { interaction.purr(HomeMascotLine(R.string.home_mascot_purr)) },
                    onCallTo = { unitX -> walker.walkTo((unitX - CAT_CENTER_UNITS).coerceIn(0f, maxX)) },
                    onChinRelease = { interaction.chinRelease(HomeMascotChatter::chinLine) },
                ),
        )
    }
}

/** 自己開口的時機：第一次出現時打招呼；之後碗變了（切帳號、刷新）就講一句。 */
@Suppress("FunctionNaming") // Compose 元件慣例用大寫開頭
@Composable
private fun MascotAutoTalk(interaction: MascotInteraction, bowl: MascotBowlSnapshot?, now: LocalDateTime) {
    var greeted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!greeted) {
            greeted = true
            HomeMascotChatter.greetingLine(now)?.let { interaction.say(HomeMascotReaction.Talk, it) }
        }
    }
    var lastBowl by remember { mutableStateOf<MascotBowlSnapshot?>(null) }
    LaunchedEffect(bowl) {
        if (bowl == null) return@LaunchedEffect
        HomeMascotChatter.changeLine(lastBowl, bowl)?.let { interaction.say(HomeMascotReaction.Talk, it) }
        lastBowl = bowl
    }
}

/** 動畫參數：關掉系統動畫時全部停在中間值。 */
private class CatMotion(val tail: State<Float>, val blink: State<Float>, val bob: State<Float>)

@Composable
private fun rememberCatMotion(enabled: Boolean): CatMotion {
    // 系統關掉動畫時根本不建立無限動畫，免得背景一直重算浪費電
    if (!enabled) return remember { stillCatMotion() }
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
    return remember(tail, blink, bob) { CatMotion(tail, blink, bob) }
}

private fun stillCatMotion(): CatMotion {
    val still = object : State<Float> { override val value = 0f }
    val open = object : State<Float> { override val value = 1f }
    return CatMotion(still, open, still)
}

/** 原創線條貓，坐在飼料碗左邊。座標以 64×64 為底再縮放。 */
// 繪製參數都是同一隻貓的狀態，拆成物件反而難讀；畫的分支是各種心情與互動
@Suppress("FunctionNaming", "LongParameterList", "CyclomaticComplexMethod")
@Composable
private fun QuotaCat(
    mood: HomeMascotMood,
    interaction: MascotInteraction,
    sleepy: Boolean,
    idleAnimations: Boolean,
    walkX: () -> Float,
    facingLeft: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = CodexMeterTheme.colors
    val ink = colors.primary
    val bodyFill = colors.surface.copy(alpha = 0.55f)
    val targetFoodColor = when (mood) {
        HomeMascotMood.Hungry -> colors.accent
        HomeMascotMood.Relaxed -> colors.success
        HomeMascotMood.Stuffed -> colors.warning
        HomeMascotMood.AteAll -> colors.danger
        HomeMascotMood.Locked, HomeMascotMood.Waiting -> colors.secondary
    }
    // 切帳號或刷新時，碗裡的飼料高度和顏色慢慢流過去，不要一下跳
    val foodColor by animateColorAsState(targetFoodColor, label = "food-color")
    val bowlFill by animateFloatAsState(mood.bowlFill, label = "bowl-fill")
    val motion = rememberCatMotion(rememberCodexMeterAnimatorsEnabled() && idleAnimations)

    Canvas(modifier = modifier) {
        val unit = size.minDimension / 64f
        scale(unit, unit, pivot = Offset.Zero) {
            val stroke = Stroke(width = 1.8f, cap = StrokeCap.Round)
            val reaction = interaction.reaction
            val purring = reaction == HomeMascotReaction.Purr
            val idleAsleep = sleepy && reaction == HomeMascotReaction.None
            // 走路位置只在畫的階段讀，不會讓整個畫面重組；走路時身體跟著步伐上下晃
            val x = walkX()
            val walkBob = abs(sin(x * WALK_BOB_FREQUENCY)) * WALK_BOB_HEIGHT
            translate(left = x) {
                // 往左走就把貓左右翻過來
                scale(if (facingLeft) -1f else 1f, 1f, pivot = Offset(CAT_CENTER_UNITS, 0f)) {
                    translate(top = -HOP_HEIGHT * interaction.hop - walkBob) {
                        drawTail(ink, stroke, swing = motion.tail.value * if (purring) PURR_TAIL_SWING else 1f)
                        drawBody(ink, bodyFill, stroke)
                        drawHead(
                            mood,
                            ink,
                            bodyFill,
                            stroke,
                            blink = if (idleAsleep) 0f else motion.blink.value,
                            // 呼嚕、嚕下巴時瞇眼
                            squint = purring || reaction == HomeMascotReaction.Chin,
                            extraTilt = interaction.tilt * CHIN_TILT_DEGREES,
                        )
                    }
                }
                drawReactionOverlay(reaction, interaction.effect, ink, colors.danger)
                if (idleAsleep) drawSleepyZ(ink, drift = (motion.tail.value + 1f) / 2f)
            }
            drawBowl(mood, bowlFill, ink, foodColor, stroke)
            if (reaction == HomeMascotReaction.Treat) drawTreat(foodColor, ink, fall = interaction.effect)
            // 拍碗、冒泡、星星這些心情動作都是坐在碗邊才做的；貓走開時先不畫
            if (x < AT_HOME_UNITS) drawMoodDecoration(mood, motion, ink, bodyFill, foodColor)
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

private fun DrawScope.drawHead(
    mood: HomeMascotMood,
    ink: Color,
    fill: Color,
    stroke: Stroke,
    blink: Float,
    squint: Boolean = false,
    extraTilt: Float = 0f,
) {
    // 登入失效時歪頭；嚕下巴時跟著手指再歪
    val tilt = (if (mood == HomeMascotMood.Locked) -12f else 0f) + extraTilt
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
        val happy = squint || mood == HomeMascotMood.Relaxed || mood == HomeMascotMood.AteAll
        if (happy) {
            // 瞇眼：向上彎的弧線
            drawArc(ink, 180f, 180f, false, Offset(14f, 23f), Size(4f, 3f), style = stroke)
            drawArc(ink, 180f, 180f, false, Offset(22f, 23f), Size(4f, 3f), style = stroke)
        } else {
            // blink 0 = 睡著閉眼（只剩一條線）
            val eyeHeight = 3f * blink.coerceIn(0.1f, 1f)
            drawOval(ink, Offset(15f, 25.5f - eyeHeight / 2), Size(2.4f, eyeHeight))
            drawOval(ink, Offset(22.6f, 25.5f - eyeHeight / 2), Size(2.4f, eyeHeight))
        }
        // 小鼻子
        drawCircle(ink, radius = 0.8f, center = Offset(20f, 28.5f))
    }
    if (mood == HomeMascotMood.Locked) drawQuestionMark(ink, stroke)
}

private fun DrawScope.drawBowl(mood: HomeMascotMood, fill: Float, ink: Color, food: Color, stroke: Stroke) {
    val bowl = Path().apply {
        moveTo(36f, 50f)
        lineTo(62f, 50f)
        cubicTo(61f, 57f, 57f, 60f, 49f, 60f)
        cubicTo(41f, 60f, 37f, 57f, 36f, 50f)
        close()
    }
    if (fill > 0f) {
        // 飼料從碗口往上堆，最高堆出碗口 4 單位
        val top = 50f - 4f * fill
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

private const val HOP_HEIGHT = 5f
private const val PURR_TAIL_SWING = 1.8f
private const val CHIN_TILT_DEGREES = 10f
private const val TREAT_START_X = 58f
private const val TREAT_START_Y = 30f
private const val TREAT_END_X = 49f
private const val TREAT_END_Y = 47f
private const val TREAT_ARC = 8f

private val pawStroke = Stroke(width = 1.8f, cap = StrokeCap.Round)

/** 各心情的小動作：餓了拍碗、吃太快冒泡泡、吃光光閃星星。 */
private fun DrawScope.drawMoodDecoration(
    mood: HomeMascotMood,
    motion: CatMotion,
    ink: Color,
    bodyFill: Color,
    food: Color,
) {
    when (mood) {
        HomeMascotMood.Hungry -> drawPaw(ink, bodyFill, pawStroke, reach = motion.bob.value)
        HomeMascotMood.Stuffed -> drawBubble(ink, grow = motion.bob.value)
        HomeMascotMood.AteAll -> drawSparkles(food, twinkle = motion.tail.value)
        else -> Unit
    }
}

/** 互動時疊在貓身上的效果：摸頭飄愛心、呼嚕冒聲波。 */
private fun DrawScope.drawReactionOverlay(
    reaction: HomeMascotReaction,
    progress: Float,
    ink: Color,
    heart: Color,
) {
    when (reaction) {
        HomeMascotReaction.Pet -> drawHeart(heart, rise = progress)
        HomeMascotReaction.Purr -> drawPurrWaves(ink, spread = progress)
        else -> Unit
    }
}

/** 深夜閒著時頭上飄的 zZz，drift 0→1 慢慢往上。座標是畫布上的位置，不抽常數。 */
@Suppress("MagicNumber")
private fun DrawScope.drawSleepyZ(ink: Color, drift: Float) {
    val color = ink.copy(alpha = 0.5f)
    listOf(Triple(31f, 14f, 2.4f), Triple(36f, 9f, 3.2f)).forEach { (x, y0, w) ->
        val y = y0 - 2f * drift
        val z = Path().apply {
            moveTo(x, y)
            lineTo(x + w, y)
            lineTo(x, y + w)
            lineTo(x + w, y + w)
        }
        drawPath(z, color, style = Stroke(1.1f, cap = StrokeCap.Round))
    }
}

/** 雙擊加菜：一顆飼料從右上拋物線掉進碗，掉完冒兩顆小星。fall 0→1。座標是畫布上的位置，不抽常數。 */
@Suppress("MagicNumber")
private fun DrawScope.drawTreat(food: Color, ink: Color, fall: Float) {
    if (fall <= 0f || fall >= 1f) return
    val x = TREAT_START_X + (TREAT_END_X - TREAT_START_X) * fall
    val y = TREAT_START_Y + (TREAT_END_Y - TREAT_START_Y) * fall - TREAT_ARC * fall * (1f - fall) * 4f
    drawCircle(food, radius = 1.8f, center = Offset(x, y))
    drawCircle(ink, radius = 1.8f, center = Offset(x, y), style = Stroke(1f))
    if (fall > 0.8f) {
        val a = (fall - 0.8f) / 0.2f
        listOf(Offset(43f, 42f), Offset(55f, 41f)).forEach { c ->
            drawLine(food.copy(alpha = a), Offset(c.x - 1.5f, c.y), Offset(c.x + 1.5f, c.y), 1.2f, StrokeCap.Round)
            drawLine(food.copy(alpha = a), Offset(c.x, c.y - 1.5f), Offset(c.x, c.y + 1.5f), 1.2f, StrokeCap.Round)
        }
    }
}

/** 點在舞台哪裡：貓身上、碗、或空地（附上點的位置，單位是畫布單位）。 */
internal sealed interface StageTarget {
    data object Cat : StageTarget
    data object Bowl : StageTarget
    data class Floor(val unitX: Float) : StageTarget
}

/** 碗固定在畫布 34～64 單位；貓身體大約在自己位置往右 4～38 單位。貓擋在碗前面時優先算點到貓。 */
internal fun stageTarget(unitX: Float, catX: Float): StageTarget = when {
    unitX in (catX + CAT_HIT_LEFT)..(catX + CAT_HIT_RIGHT) -> StageTarget.Cat
    unitX in BOWL_HIT_LEFT..BOWL_HIT_RIGHT -> StageTarget.Bowl
    else -> StageTarget.Floor(unitX)
}

private val LINE_ROW_HEIGHT = 28.dp
private val STAGE_HEIGHT = 82.dp
private val HINT_ROW_HEIGHT = 20.dp
private const val CANVAS_UNITS = 64f
private const val CAT_FOOTPRINT_UNITS = 40f
private const val CAT_CENTER_UNITS = 20f
private const val CAT_HIT_LEFT = 2f
private const val CAT_HIT_RIGHT = 38f
private const val BOWL_HIT_LEFT = 34f
private const val BOWL_HIT_RIGHT = 64f
private const val AT_HOME_UNITS = 2f
private const val WALK_BOB_FREQUENCY = 0.6f
private const val WALK_BOB_HEIGHT = 1.5f
