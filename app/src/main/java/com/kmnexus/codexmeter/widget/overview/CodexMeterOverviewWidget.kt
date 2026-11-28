package com.kmnexus.codexmeter.widget.overview

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.background
import androidx.glance.appwidget.cornerRadius
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.kmnexus.codexmeter.CodexMeterApp
import com.kmnexus.codexmeter.MainActivity
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.ui.navigation.CodexMeterLaunchDestination
import com.kmnexus.codexmeter.ui.navigation.CodexMeterRoute
import com.kmnexus.codexmeter.ui.quota.formatProviderBalance
import com.kmnexus.codexmeter.ui.quota.quotaWindowLabelRes
import com.kmnexus.codexmeter.widget.WidgetQuotaStatus
import com.kmnexus.codexmeter.widget.WidgetQuotaTone
import com.kmnexus.codexmeter.widget.percentProvider
import com.kmnexus.codexmeter.widget.statusAccentProvider
import com.kmnexus.codexmeter.widget.statusLabelResId
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 「AI 額度總覽」小工具：條列，一列一個帳號，左邊雙圓環。
 * 外圈＝5 小時剩餘、內圈＝7 天剩餘、刻度＝週期時間還剩多少；顏色代表用量節奏（見 QuotaPace）。
 * 右上角重新整理鈕會排一次立即刷新；點其他地方開 App 首頁。
 */
class CodexMeterOverviewWidget : GlanceAppWidget() {
    // Exact：依實際高度決定放幾列（見 OverviewLayout）。
    override val sizeMode: SizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 第一次放到桌面時還沒有快照，先載一次，不用等下次背景刷新。
        val hasSnapshot = runCatching {
            androidx.glance.appwidget.state.getAppWidgetState(context, stateDefinition, id)[ROWS_KEY] != null
        }.getOrDefault(false)
        if (!hasSnapshot) writeSnapshot(context, id)

        provideContent {
            val prefs = currentState<Preferences>()
            val rows = OverviewRowsCodec.decode(prefs[ROWS_KEY]).orEmpty()
            val now = Instant.now()
            val refreshing = OverviewLayout.isRefreshing(prefs[REFRESHING_SINCE_KEY], now)
            OverviewContent(context, rows, LocalSize.current.height.value, now, refreshing)
        }
    }

    companion object {
        internal val ROWS_KEY = stringPreferencesKey("overview_rows_json")

        /** 按下重新整理的時間；有新資料寫進來就清掉（見 writeSnapshot）。 */
        internal val REFRESHING_SINCE_KEY = androidx.datastore.preferences.core.longPreferencesKey("overview_refreshing_since")

        /** 從資料庫讀所有帳號的最新狀態，寫進這個小工具的狀態。只存額度數字，不含任何憑證。 */
        suspend fun writeSnapshot(context: Context, id: GlanceId) {
            val loader = (context.applicationContext as? CodexMeterApp)?.widgetOverviewLoader ?: return
            val rows = loader.loadOverview().mapNotNull { it.toOverviewRow() }
            updateAppWidgetState(context, id) { prefs ->
                prefs[ROWS_KEY] = OverviewRowsCodec.encode(rows)
                prefs.remove(REFRESHING_SINCE_KEY)
            }
        }
    }
}

@Composable
private fun OverviewContent(
    context: Context,
    rows: List<OverviewRow>,
    heightDp: Float,
    now: Instant,
    refreshing: Boolean,
) {
    val visible = rows.take(OverviewLayout.rowCapacity(heightDp, context.resources.configuration.fontScale))
    val hidden = rows.size - visible.size
    Box(
        modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity(launchIntent(context))),
    ) {
        Image(
            provider = ImageProvider(R.drawable.widget_glass_xml),
            contentDescription = null,
            modifier = GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Column(modifier = GlanceModifier.fillMaxSize().padding(OverviewLayout.PADDING_DP.dp)) {
            Header(context, rows, hidden, now, refreshing)
            if (rows.isEmpty()) {
                Text(
                    text = context.getString(R.string.overview_widget_empty),
                    style = TextStyle(color = MutedText, fontSize = 12.sp),
                    modifier = GlanceModifier.padding(top = 6.dp),
                )
            }
            visible.forEach { row ->
                Box(modifier = GlanceModifier.fillMaxWidth().padding(top = OverviewLayout.ROW_GAP_DP.dp)) {
                    AccountRow(context, row, now)
                }
            }
            // 所有帳號都放得下、底下還有空：補一條 Gemini 合計，不讓下面空一大塊
            val pool = geminiPool(rows)
            if (pool != null && hidden == 0 &&
                OverviewLayout.hasRoomForSummary(heightDp, visible.size, context.resources.configuration.fontScale)
            ) {
                PoolSummary(context, pool, now)
            }
        }
    }
}

@Composable
private fun Header(context: Context, rows: List<OverviewRow>, hiddenCount: Int, now: Instant, refreshing: Boolean) {
    // 不寫死高度：大字體時推薦那行會被裁掉。
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        TitleRow(context, rows, hiddenCount, now, refreshing)
        // 第二行：「現在最該用哪個」，給想把額度用完的人一眼決定。
        val pick = UseItAdvisor.recommend(rows, now)
        if (pick != null) {
            val reset = pick.main?.resetAtEpochMillis?.let { formatReset(context, Instant.ofEpochMilli(it), now) }
            // 膠囊底＋高明度字：放在花俏桌布上也一眼讀得到「現在用哪個」
            Box(
                modifier = GlanceModifier
                    .padding(top = 2.dp)
                    .background(RecommendPillBackground)
                    .cornerRadius(10.dp)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = if (reset != null) {
                        context.getString(R.string.overview_widget_recommend_with_reset_format, pick.accountName, reset)
                    } else {
                        context.getString(R.string.overview_widget_recommend_format, pick.accountName)
                    },
                    style = TextStyle(color = RecommendPillText, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
            }
        }
    }
}

@Suppress("FunctionNaming") // Compose 元件慣例用大寫開頭
@Composable
private fun PoolSummary(context: Context, pool: GeminiPool, now: Instant) {
    val refill = pool.nextRefillEpochMillis?.let { formatReset(context, Instant.ofEpochMilli(it), now) }
    Text(
        text = if (refill != null) {
            context.getString(R.string.overview_widget_pool_with_refill_format, pool.totalPercent, refill)
        } else {
            context.getString(R.string.overview_widget_pool_format, pool.totalPercent)
        },
        style = TextStyle(color = MutedText, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        maxLines = 1,
        modifier = GlanceModifier.padding(top = OverviewLayout.ROW_GAP_DP.dp),
    )
}

@Composable
private fun TitleRow(context: Context, rows: List<OverviewRow>, hiddenCount: Int, now: Instant, refreshing: Boolean) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = context.getString(R.string.overview_widget_title),
            style = TextStyle(color = InkText, fontSize = 13.sp, fontWeight = FontWeight.Bold),
        )
        if (hiddenCount > 0) {
            // 放不下的帳號數量；拉高小工具就會顯示。
            Text(
                text = context.getString(R.string.overview_widget_more_format, hiddenCount),
                style = TextStyle(color = MutedText, fontSize = 11.sp),
                modifier = GlanceModifier.padding(start = 6.dp),
            )
        }
        Spacer(modifier = GlanceModifier.defaultWeight())
        // 按下重新整理後先顯示「更新中…」，不然資料沒變時看起來像按了沒反應。
        if (refreshing) {
            Text(
                text = context.getString(R.string.overview_widget_refreshing),
                style = TextStyle(color = WidgetQuotaTone.Neutral.statusAccentProvider(), fontSize = 10.sp),
                maxLines = 1,
            )
        }
        // 平常顯示最舊的那筆更新時間：只要有一個帳號的資料舊了，使用者就該知道。
        if (!refreshing) rows.mapNotNull { it.updatedAtEpochMillis }.minOrNull()?.let { oldest ->
            Text(
                text = context.getString(R.string.overview_widget_updated_format, formatReset(context, Instant.ofEpochMilli(oldest), now)),
                style = TextStyle(color = MutedText, fontSize = 10.sp),
                maxLines = 1,
            )
        }
        // 觸控範圍放大到 32dp，手指好按。
        Box(
            modifier = GlanceModifier.width(32.dp).height(24.dp)
                .clickable(actionRunCallback<OverviewRefreshAction>()),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_action_refresh),
                contentDescription = context.getString(R.string.overview_widget_refresh),
                modifier = GlanceModifier.width(18.dp).height(18.dp),
                colorFilter = ColorFilter.tint(MutedText),
            )
        }
    }
}

@Composable
private fun AccountRow(context: Context, row: OverviewRow, now: Instant) {
    val pace = QuotaPaceCalculator.pace(row, now)
    val main = row.main
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(
            provider = ImageProvider(ringBitmap(context, row, pace, now)),
            contentDescription = listOfNotNull(row.accountName, main?.valueText(context)).joinToString(" "),
            modifier = GlanceModifier.width(OverviewLayout.RING_DP.dp).height(OverviewLayout.RING_DP.dp),
        )
        Spacer(modifier = GlanceModifier.width(12.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            // 第一行：名稱＋大字百分比＋節奏；第二行：重置時間與週額度。兩行都只准一行，字放大也不會被切掉。
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = row.accountName,
                    style = TextStyle(color = InkText, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    modifier = GlanceModifier.padding(end = 6.dp, bottom = 3.dp),
                )
                if (row.needsAttention || main == null) {
                    // 需要重新登入或沒資料：直接寫狀態，比一個「--」清楚。
                    val tone = if (row.needsAttention) WidgetQuotaTone.Danger else WidgetQuotaTone.Neutral
                    Text(
                        text = context.getString(statusLabelFor(row)),
                        style = TextStyle(color = tone.statusAccentProvider(), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        modifier = GlanceModifier.padding(bottom = 3.dp),
                    )
                } else {
                    Text(
                        text = main.valueText(context),
                        style = TextStyle(color = paceText(pace, main.tone), fontSize = 20.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                    paceLabelRes(pace)?.let { label ->
                        Text(
                            text = context.getString(label),
                            style = TextStyle(
                                color = paceText(pace, main.tone),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            modifier = GlanceModifier.padding(start = 4.dp, bottom = 3.dp),
                            maxLines = 1,
                        )
                    }
                }
            }
            if (main != null && !row.needsAttention) {
                Text(
                    text = cellDetail(context, row, now),
                    style = TextStyle(color = MutedText, fontSize = 12.sp),
                    maxLines = 1,
                )
            }
        }
    }
}

/** 第二行：「18:26 重置 · 7d 89%」；資料可能過期時補狀態，避免把舊數字當現在。 */
private fun cellDetail(context: Context, row: OverviewRow, now: Instant): String {
    val main = row.main ?: return ""
    val parts = buildList {
        main.resetAtEpochMillis?.let {
            add(context.getString(R.string.widget_reset_at_compact_format, formatReset(context, Instant.ofEpochMilli(it), now)))
        }
        row.secondary?.let { second ->
            add(
                context.getString(
                    R.string.overview_widget_secondary_format,
                    shortWindowCode(second.limitWindowSeconds) ?: compactLabel(
                        context.getString(quotaWindowLabelRes(second.windowId, second.limitWindowSeconds)),
                    ),
                    second.valueText(context),
                ),
            )
        }
        if (row.status != WidgetQuotaStatus.Fresh) add(context.getString(statusLabelFor(row)))
    }
    return parts.joinToString(" · ")
}

private fun ringBitmap(context: Context, row: OverviewRow, pace: QuotaPace, now: Instant): android.graphics.Bitmap {
    val main = row.main
    val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    val attention = row.needsAttention || main == null
    return OverviewRingRenderer.render(
        spec = OverviewRingRenderer.Spec(
            outerFraction = if (attention) null else main.progressFraction,
            innerFraction = if (attention) null else row.secondary?.progressFraction,
            timeLeftFraction = if (attention) null else QuotaPaceCalculator.timeLeftFraction(main, now),
            color = paceRingColor(pace),
            tickColor = if (night) 0xFFFFFFFF.toInt() else 0xFF151821.toInt(),
        ),
        sizeDp = OverviewLayout.RING_DP.toFloat(),
        density = context.resources.displayMetrics.density,
    )
}

// 節奏配色：藍＝用太慢快去用、綠＝剛好、黃＝用太快、紅＝用完。藍色刻意跟「快沒了」的紅黃分開。
private fun paceRingColor(pace: QuotaPace): Int = when (pace) {
    QuotaPace.UnderUsing -> 0xFF378ADD.toInt()
    QuotaPace.OnTrack -> 0xFF1D9E75.toInt()
    QuotaPace.OverUsing -> 0xFFEF9F27.toInt()
    QuotaPace.Depleted -> 0xFFE24B4A.toInt()
    QuotaPace.Unknown -> 0xFF888780.toInt()
}

private fun paceText(pace: QuotaPace, fallback: WidgetQuotaTone) = when (pace) {
    QuotaPace.UnderUsing -> ColorProvider(day = Color(0xFF185FA5), night = Color(BRIGHT_SKY_BLUE))
    QuotaPace.OnTrack -> ColorProvider(day = Color(0xFF0F6E56), night = Color(0xFF5DCAA5))
    QuotaPace.OverUsing -> ColorProvider(day = Color(0xFF854F0B), night = Color(0xFFEF9F27))
    QuotaPace.Depleted -> ColorProvider(day = Color(0xFFA32D2D), night = Color(0xFFF09595))
    QuotaPace.Unknown -> fallback.percentProvider()
}

private fun paceLabelRes(pace: QuotaPace): Int? = when (pace) {
    QuotaPace.UnderUsing -> R.string.overview_pace_under
    QuotaPace.OnTrack -> R.string.overview_pace_on_track
    QuotaPace.OverUsing -> R.string.overview_pace_over
    QuotaPace.Depleted -> R.string.overview_pace_depleted
    QuotaPace.Unknown -> null
}

/** 副標空間很窄：「5 小時額度」縮成「5 小時」；只剩「額度」這種通用名稱時保留原文。 */
internal fun compactLabel(label: String): String =
    listOf("額度", "额度", " quota")
        .firstOrNull { label.endsWith(it) && label.length > it.length }
        ?.let { label.removeSuffix(it).trim() }
        ?: label

private fun statusLabelFor(row: OverviewRow): Int =
    // 狀態正常卻沒有任何可顯示的額度時，明講「尚無額度資料」，不要顯示「正常」。
    if (row.status == WidgetQuotaStatus.Fresh && row.main == null) R.string.widget_status_no_data
    else com.kmnexus.codexmeter.widget.WidgetQuotaState(
        status = row.status,
        providerName = "",
        providerId = row.providerId,
        localAccountId = null,
        accountName = null,
        tone = row.tone,
        clickTarget = com.kmnexus.codexmeter.widget.WidgetClickTarget.Home,
    ).statusLabelResId()

private fun OverviewField.valueText(context: Context): String =
    if (isBalance) {
        formatProviderBalance(balanceAmount, balanceCurrency) ?: context.getString(R.string.widget_percent_unavailable)
    } else {
        percent?.let { context.getString(R.string.widget_percent_format, it) }
            ?: context.getString(R.string.widget_percent_unavailable)
    }

private fun formatReset(context: Context, reset: Instant, now: Instant): String {
    val locale = context.resources.configuration.locales[0]
    val zone = ZoneId.systemDefault()
    val skeleton = if (isSameLocalDay(reset, now, zone)) "Hm" else "MdHm"
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    return DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(reset)
}

private fun launchIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(CodexMeterRoute.EXTRA_LAUNCH_DESTINATION, CodexMeterLaunchDestination.Home.value)
    }

// 與單帳號小工具同一組配色（淺色／深色），兩種小工具並排時看起來是同一套。
private val InkText = ColorProvider(day = Color(0xFF151821), night = Color(0xFFFFFFFF))
// 「現在用」膠囊：淺色模式淡藍底深藍字，深色模式黑色半透明底＋淺冰藍字
private const val DEEP_BLUE = 0xFF185FA5
private const val DEEP_BLUE_TINT = 0x1F185FA5
private const val SHADE_40 = 0x66000000
private const val ICE_BLUE = 0xFFB8E3FF
private const val BRIGHT_SKY_BLUE = 0xFF8FD3FF
private val RecommendPillBackground = ColorProvider(day = Color(DEEP_BLUE_TINT), night = Color(SHADE_40))
private val RecommendPillText = ColorProvider(day = Color(DEEP_BLUE), night = Color(ICE_BLUE))
private val MutedText = ColorProvider(day = Color(0x8C151821), night = Color(0xE6FFFFFF))
