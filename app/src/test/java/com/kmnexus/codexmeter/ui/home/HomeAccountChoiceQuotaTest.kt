package com.kmnexus.codexmeter.ui.home

import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import com.kmnexus.codexmeter.domain.model.QuotaWindowId
import com.kmnexus.codexmeter.domain.model.SnapshotId
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshot
import com.kmnexus.codexmeter.domain.quota.QuotaSnapshotSource
import com.kmnexus.codexmeter.domain.quota.QuotaWindow
import com.kmnexus.codexmeter.domain.quota.QuotaWindowAvailability
import com.kmnexus.codexmeter.widget.overview.QuotaPace
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeAccountChoiceQuotaTest {
    private val now = Instant.parse("2026-11-28T10:00:00Z")

    private fun window(id: String, used: Int, primary: Boolean, resetInMinutes: Long, seconds: Int) = QuotaWindow(
        windowId = QuotaWindowId(id),
        titleKey = id,
        usedPercent = used,
        resetAt = now.plusSeconds(resetInMinutes * 60),
        limitWindowSeconds = seconds,
        isPrimaryCandidate = primary,
        availability = QuotaWindowAvailability.Available,
    )

    private fun snapshot(vararg windows: QuotaWindow) = QuotaSnapshot(
        snapshotId = SnapshotId("s"),
        providerId = ProviderId("gemini"),
        localAccountId = LocalAccountId("a"),
        providerAccountId = ProviderAccountId("p"),
        fetchedAt = now,
        source = QuotaSnapshotSource.ManualRefresh,
        planType = null,
        windows = windows.toList(),
        credits = null,
        responseDigest = "d",
    )

    @Test
    fun `no snapshot shows no percent`() {
        val quota = HomeAccountChoiceQuota.from(null, now)
        assertNull(quota.remainingPercent)
        assertEquals(QuotaPace.Unknown, quota.pace)
    }

    @Test
    fun `uses the primary window remaining and pace`() {
        // 5 小時剩 90%、時間只剩 20%：用太慢 → 快去用（藍）
        val quota = HomeAccountChoiceQuota.from(
            snapshot(
                window("weekly", used = 50, primary = false, resetInMinutes = 3_000, seconds = 604_800),
                window("5h", used = 10, primary = true, resetInMinutes = 60, seconds = 18_000),
            ),
            now,
        )
        assertEquals(90, quota.remainingPercent)
        assertEquals(QuotaPace.UnderUsing, quota.pace)
    }

    @Test
    fun `fully used is depleted`() {
        val quota = HomeAccountChoiceQuota.from(
            snapshot(window("5h", used = 100, primary = true, resetInMinutes = 60, seconds = 18_000)),
            now,
        )
        assertEquals(QuotaPace.Depleted, quota.pace)
    }
}
