package com.kmnexus.codexmeter.providers.gemini.mapper

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.model.SnapshotId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.providers.gemini.dto.GeminiUsageInfoDto
import java.time.DateTimeException
import java.time.Instant

/**
 * 把 Gemini app 的兩個額度池轉成通用視窗：5 小時（主要）與每週。
 * `percentageUsed` 是 0～1 的比例；缺值就顯示不可用，不估算（RULES §12）。
 */
object GeminiUsageMapper {
    const val FIVE_HOUR_WINDOW_ID = "gemini_5h_window"
    const val WEEKLY_WINDOW_ID = "gemini_weekly_window"
    private const val FIVE_HOUR_POOL = "GXU_FIVE_HOURLY"
    private const val WEEKLY_POOL = "GXU_WEEKLY"
    private const val FIVE_HOUR_SECONDS = 5 * 60 * 60
    private const val WEEK_SECONDS = 7 * 24 * 60 * 60

    // CodexBar 實測 Google AI Pro 回傳 TIER1；其他值沒有可靠對照，就不顯示方案名稱。
    private const val PRO_TIER = "TIER1"
    private const val PRO_DISPLAY_NAME = "Google AI Pro"

    private val GEMINI_PROVIDER_ID = ProviderId("gemini")

    fun map(
        usage: GeminiUsageInfoDto,
        localAccountId: LocalAccountId,
        providerAccountId: ProviderAccountId?,
        fetchedAt: Instant,
        source: QuotaSnapshotSource,
    ): QuotaSnapshot {
        val pools = usage.pools.orEmpty()
        val windows = listOfNotNull(
            pools.firstOrNull { it.poolType == FIVE_HOUR_POOL }
                ?.let { toWindow(it, FIVE_HOUR_WINDOW_ID, FIVE_HOUR_SECONDS, isPrimary = true) },
            pools.firstOrNull { it.poolType == WEEKLY_POOL }
                ?.let { toWindow(it, WEEKLY_WINDOW_ID, WEEK_SECONDS, isPrimary = false) },
        )
        return QuotaSnapshot(
            snapshotId = SnapshotId("gemini_${fetchedAt}"),
            providerId = GEMINI_PROVIDER_ID,
            localAccountId = localAccountId,
            providerAccountId = providerAccountId,
            fetchedAt = fetchedAt,
            source = source,
            planType = if (usage.subscriptionTier == PRO_TIER) PRO_DISPLAY_NAME else null,
            windows = windows,
            credits = null,
            responseDigest = null,
        )
    }

    private fun toWindow(
        pool: GeminiUsageInfoDto.Pool,
        windowId: String,
        windowSeconds: Int,
        isPrimary: Boolean,
    ): QuotaWindow {
        val fraction = pool.percentageUsed?.takeIf { it.isFinite() && it >= 0.0 }
        val usedPercent = fraction?.let { (it * 100).coerceAtMost(100.0).toInt() }
        val availability = when {
            usedPercent == null -> QuotaWindowAvailability.Missing
            usedPercent >= 100 -> QuotaWindowAvailability.Depleted
            else -> QuotaWindowAvailability.Available
        }
        return QuotaWindow(
            windowId = QuotaWindowId(windowId),
            titleKey = windowId,
            usedPercent = usedPercent,
            resetAt = pool.gxu?.windowRefreshesAt?.let(::parseInstant),
            limitWindowSeconds = windowSeconds,
            isPrimaryCandidate = isPrimary,
            availability = availability,
        )
    }

    private fun parseInstant(raw: String): Instant? =
        try {
            Instant.parse(raw)
        } catch (_: DateTimeException) {
            null
        }
}
