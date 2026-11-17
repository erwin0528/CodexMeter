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
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
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
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.providers.ProviderRegistry
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
 * 「AI 額度總覽」小工具：一個小工具同時列出所有帳號，每列一個帳號。
 * 每列：圖示、帳號名、主要額度大字＋進度條、副標（額度名稱 · 重置時間 · 第二個額度）。
 * 右上角重新整理鈕會排一次立即刷新；點其他地方開 App 首頁。
 */
class CodexMeterOverviewWidget : GlanceAppWidget() {
    // Exact：依實際高度決定放幾列（見 OverviewLayout.rowCapacity）。
    override val sizeMode: SizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 第一次放到桌面時還沒有快照，先載一次，不用等下次背景刷新。
        val hasSnapshot = runCatching {
            androidx.glance.appwidget.state.getAppWidgetState(context, stateDefinition, id)[ROWS_KEY] != null
        }.getOrDefault(false)
        if (!hasSnapshot) writeSnapshot(context, id)

        provideContent {
            val rows = OverviewRowsCodec.decode(currentState<Preferences>()[ROWS_KEY]).orEmpty()
            OverviewContent(context, rows, LocalSize.current.height.value, Instant.now())
        }
    }

    companion object {
        internal val ROWS_KEY = stringPreferencesKey("overview_rows_json")

        /** 從資料庫讀所有帳號的最新狀態，寫進這個小工具的狀態。只存額度數字，不含任何憑證。 */
        suspend fun writeSnapshot(context: Context, id: GlanceId) {
            val loader = (context.applicationContext as? CodexMeterApp)?.widgetOverviewLoader ?: return
            val rows = loader.loadOverview().mapNotNull { it.toOverviewRow() }
            updateAppWidgetState(context, id) { prefs -> prefs[ROWS_KEY] = OverviewRowsCodec.encode(rows) }
        }
    }
}

@Composable
private fun OverviewContent(context: Context, rows: List<OverviewRow>, heightDp: Float, now: Instant) {
    val capacity = OverviewLayout.rowCapacity(heightDp, context.resources.configuration.fontScale)
    val visible = rows.take(capacity)
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
            Header(context, rows, hidden, now)
            if (rows.isEmpty()) {
                Text(
                    text = context.getString(R.string.overview_widget_empty),
                    style = TextStyle(color = MutedText, fontSize = 12.sp),
                    modifier = GlanceModifier.padding(top = 6.dp),
                )
            }
            visible.forEach { AccountRow(context, it, now) }
        }
    }
}

@Composable
private fun Header(context: Context, rows: List<OverviewRow>, hiddenCount: Int, now: Instant) {
    Column(modifier = GlanceModifier.fillMaxWidth().height(OverviewLayout.HEADER_HEIGHT_DP.dp)) {
        TitleRow(context, rows, hiddenCount, now)
        // 第二行：「現在最該用哪個」，給想把額度用完的人一眼決定。
        val pick = UseItAdvisor.recommend(rows)
        if (pick != null) {
            val reset = pick.main?.resetAtEpochMillis?.let { formatReset(context, Instant.ofEpochMilli(it), now) }
            Text(
                text = if (reset != null) {
                    context.getString(R.string.overview_widget_recommend_with_reset_format, pick.accountName, reset)
                } else {
                    context.getString(R.string.overview_widget_recommend_format, pick.accountName)
                },
                style = TextStyle(color = WidgetQuotaTone.Neutral.statusAccentProvider(), fontSize = 11.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TitleRow(context: Context, rows: List<OverviewRow>, hiddenCount: Int, now: Instant) {
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
        // 顯示最舊的那筆更新時間：只要有一個帳號的資料舊了，使用者就該知道。
        rows.mapNotNull { it.updatedAtEpochMillis }.minOrNull()?.let { oldest ->
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
    // 列高不寫死：跟著內容（含進度條）長，之前固定 48dp 在大字體手機上會把進度條裁掉。
    Column(
        modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ProviderRegistry.iconFor(ProviderId(row.providerId))?.let { icon ->
                Image(
                    provider = ImageProvider(icon),
                    contentDescription = null,
                    modifier = GlanceModifier.width(16.dp).height(16.dp),
                    colorFilter = ColorFilter.tint(InkText),
                )
                Spacer(modifier = GlanceModifier.width(8.dp))
            }
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = row.accountName,
                    style = TextStyle(color = InkText, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
                Text(
                    text = subline(context, row, now),
                    style = TextStyle(color = MutedText, fontSize = 11.sp),
                    maxLines = 1,
                )
            }
            Spacer(modifier = GlanceModifier.width(8.dp))
            ValueText(context, row, now)
        }
        val fraction = row.main?.progressFraction
        if (fraction != null && !row.needsAttention) {
            Spacer(modifier = GlanceModifier.height(5.dp))
            LinearProgressIndicator(
                progress = fraction,
                modifier = GlanceModifier.fillMaxWidth().height(5.dp).cornerRadius(3.dp),
                color = row.main.tone.statusAccentProvider(),
                backgroundColor = TrackColor,
            )
        }
    }
}

@Composable
private fun ValueText(context: Context, row: OverviewRow, now: Instant) {
    val main = row.main
    // 需要重新登入、或完全沒有資料時，右側直接寫狀態，比一個「--」更清楚。
    if (row.needsAttention || main == null) {
        val tone = if (row.needsAttention) WidgetQuotaTone.Danger else WidgetQuotaTone.Neutral
        Text(
            text = context.getString(statusLabelFor(row)),
            style = TextStyle(color = tone.statusAccentProvider(), fontSize = 12.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        return
    }
    // 快作廢（快重置還剩很多）用藍色強調：這是「快去用」的提示，跟紅黃的「快沒了」區分開。
    val color = if (UseItAdvisor.isExpiringUnused(row, now)) {
        WidgetQuotaTone.Neutral.statusAccentProvider()
    } else {
        main.tone.percentProvider()
    }
    Text(
        text = main.valueText(context),
        style = TextStyle(color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold),
        maxLines = 1,
    )
}

private fun subline(context: Context, row: OverviewRow, now: Instant): String {
    val main = row.main ?: return context.getString(statusLabelFor(row))
    val parts = buildList {
        if (UseItAdvisor.isExpiringUnused(row, now)) add(context.getString(R.string.overview_widget_expiring))
        add(compactLabel(context.getString(quotaWindowLabelRes(main.windowId, main.limitWindowSeconds))))
        main.resetAtEpochMillis?.let {
            add(context.getString(R.string.widget_reset_at_compact_format, formatReset(context, Instant.ofEpochMilli(it), now)))
        }
        row.secondary?.let { second ->
            add(
                context.getString(
                    R.string.overview_widget_secondary_format,
                    compactLabel(context.getString(quotaWindowLabelRes(second.windowId, second.limitWindowSeconds))),
                    second.valueText(context),
                ),
            )
        }
        // 資料可能過期／刷新失敗時補一個提示，避免把舊數字當成現在。
        if (row.status != WidgetQuotaStatus.Fresh) add(context.getString(statusLabelFor(row)))
    }
    return parts.joinToString(" · ")
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
    val skeleton = if (isSameLocalDay(reset, now, zone)) "jm" else "Mdjm"
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
private val MutedText = ColorProvider(day = Color(0x8C151821), night = Color(0xE6FFFFFF))
private val TrackColor = ColorProvider(day = Color(0x226A86B0), night = Color(0x33B9D8FF))
