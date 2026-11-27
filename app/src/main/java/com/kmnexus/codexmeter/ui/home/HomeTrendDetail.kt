package com.kmnexus.codexmeter.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.ui.theme.CodexMeterSpacing
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** 趨勢圖詳細頁：把 24 小時的用量按「每小時」拆開。 */
data class HomeTrendHour(val hourStart: LocalDateTime, val value: Double)

object HomeTrendBreakdown {
    /** 依當地時間的整點分組加總，只留有用量的小時，新的在前。 */
    fun byHour(points: List<HomeTrendPointUi>, zone: ZoneId): List<HomeTrendHour> =
        points
            .filter { it.usageValue > 0.0 }
            .groupBy { LocalDateTime.ofInstant(it.capturedAt, zone).truncatedTo(ChronoUnit.HOURS) }
            .map { (hour, items) -> HomeTrendHour(hour, items.sumOf { it.usageValue }) }
            .sortedByDescending { it.hourStart }

    fun total(points: List<HomeTrendPointUi>): Double = points.sumOf { it.usageValue.coerceAtLeast(0.0) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeTrendDetailSheet(trend: HomeTrendUi, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val hours = HomeTrendBreakdown.byHour(trend.points, zone)
    val peak = hours.maxByOrNull { it.value }
    val hourFormat = DateTimeFormatter.ofPattern("HH:mm")
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CodexMeterSpacing.lg)
                .padding(bottom = CodexMeterSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.md),
        ) {
            Text(
                text = stringResource(R.string.home_trend_detail_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    R.string.home_trend_detail_total,
                    formatTrendValue(trend, HomeTrendBreakdown.total(trend.points)),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = CodexMeterTheme.colors.primary,
            )
            if (peak != null) {
                Text(
                    text = stringResource(
                        R.string.home_trend_detail_peak,
                        peak.hourStart.format(hourFormat),
                        formatTrendValue(trend, peak.value),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = CodexMeterTheme.colors.secondary,
                )
            }
            HorizontalDivider()
            if (hours.isEmpty()) {
                Text(
                    text = stringResource(R.string.home_trend_detail_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm)) {
                    items(hours, key = { it.hourStart.toString() }) { hour ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.home_trend_detail_hour_range,
                                    hour.hourStart.format(hourFormat),
                                    hour.hourStart.plusHours(1).format(hourFormat),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = formatTrendValue(trend, hour.value),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (hour == peak) CodexMeterTheme.colors.accent else CodexMeterTheme.colors.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 百分比型顯示「12%」；次數顯示整數；消費保留兩位小數。 */
@Composable
private fun formatTrendValue(trend: HomeTrendUi, value: Double): String =
    when (trend.metricLabelResId) {
        R.string.home_trend_metric_calls -> stringResource(R.string.home_trend_detail_calls, value.roundToInt())
        R.string.home_trend_metric_spend -> "%.2f".format(value)
        // 不到 1% 的小用量顯示「<1%」，免得列出來卻寫 0%
        else -> stringResource(
            R.string.home_trend_detail_percent,
            if (value > 0.0 && value.roundToInt() == 0) "<1" else value.roundToInt().toString(),
        )
    }
