package com.kmnexus.codexmeter.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.ui.theme.CodexMeterShapes
import com.kmnexus.codexmeter.ui.theme.CodexMeterSpacing
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme
import com.kmnexus.codexmeter.widget.overview.QuotaPace

/**
 * 首頁頂部的帳號比較列：每個帳號一顆膠囊，名稱＋主要額度 %＋節奏色點，
 * 不用切頁就能橫向比較；點一下切換首頁看的帳號。每顆至少 56dp 高，戴手套也點得到。
 */
@Suppress("FunctionNaming") // Compose 元件慣例用大寫開頭
@Composable
internal fun HomeAccountPills(
    choices: List<HomeAccountChoiceUi>,
    selectedId: LocalAccountId?,
    onAccountSelected: (ProviderId, LocalAccountId) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.sm),
    ) {
        choices.forEach { choice ->
            val selected = choice.localAccountId == selectedId
            AccountPill(
                choice = choice,
                selected = selected,
                modifier = Modifier.weight(1f),
                onClick = { if (!selected) onAccountSelected(choice.providerId, choice.localAccountId) },
            )
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun AccountPill(
    choice: HomeAccountChoiceUi,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = CodexMeterTheme.colors
    Box(
        modifier = modifier
            .heightIn(min = PILL_MIN_HEIGHT)
            .clip(CodexMeterShapes.md)
            .background(if (selected) colors.accentSoft else colors.surface)
            .border(1.dp, if (selected) colors.accent else colors.border, CodexMeterShapes.md)
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = CodexMeterSpacing.sm, vertical = CodexMeterSpacing.xs),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column {
            Text(
                text = choice.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = colors.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CodexMeterSpacing.xs),
            ) {
                Text(
                    text = choice.remainingPercent?.let { stringResource(R.string.account_quota_percent_format, it) }
                        ?: stringResource(R.string.account_quota_placeholder_no_data),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary,
                    maxLines = 1,
                )
                Box(Modifier.size(PACE_DOT).clip(CircleShape).background(paceColor(choice.pace)))
            }
        }
    }
}

/** 跟小工具同一套節奏色：藍＝快去用、綠＝剛好、琥珀＝用太快、紅＝用完。 */
@Composable
@ReadOnlyComposable
internal fun paceColor(pace: QuotaPace): Color {
    val colors = CodexMeterTheme.colors
    return when (pace) {
        QuotaPace.UnderUsing -> colors.accent
        QuotaPace.OnTrack -> colors.success
        QuotaPace.OverUsing -> colors.warning
        QuotaPace.Depleted -> colors.danger
        QuotaPace.Unknown -> colors.border
    }
}

private val PILL_MIN_HEIGHT = 56.dp
private val PACE_DOT = 8.dp
