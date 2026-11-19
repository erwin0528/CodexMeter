package com.kmnexus.codexmeter.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun formattedInstant(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(instant, locale) {
        val zone = ZoneId.systemDefault()
        // 今天只顯示時間（下午 9:42），其他天「11/24 下午 9:42」；年份省略，卡片上才放得下。
        val skeleton = if (instant.atZone(zone).toLocalDate() == java.time.LocalDate.now(zone)) "jm" else "Mdjm"
        DateTimeFormatter
            .ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale)
            .withZone(zone)
            .format(instant)
    }
}

@Composable
@ReadOnlyComposable
internal fun toneColor(tone: HomeStatusTone): Color =
    when (tone) {
        HomeStatusTone.Neutral -> CodexMeterTheme.colors.tertiary
        HomeStatusTone.Success -> CodexMeterTheme.colors.success
        HomeStatusTone.Warning -> CodexMeterTheme.colors.warning
        HomeStatusTone.Danger -> CodexMeterTheme.colors.danger
    }
