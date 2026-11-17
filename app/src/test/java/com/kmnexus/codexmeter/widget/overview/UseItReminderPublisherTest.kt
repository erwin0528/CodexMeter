package com.kmnexus.codexmeter.widget.overview

import com.kmnexus.codexmeter.notification.NotificationRequest
import com.kmnexus.codexmeter.notification.NotificationRequestOptions
import com.kmnexus.codexmeter.widget.WidgetClickTarget
import com.kmnexus.codexmeter.widget.WidgetField
import com.kmnexus.codexmeter.widget.WidgetQuotaState
import com.kmnexus.codexmeter.widget.WidgetQuotaStatus
import com.kmnexus.codexmeter.widget.WidgetQuotaTone
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UseItReminderPublisherTest {
    private val now = Instant.parse("2026-11-17T10:00:00Z")
    private val posted = mutableListOf<NotificationRequest>()
    private val store = object : UseItReminderStore {
        val map = mutableMapOf<String, Long>()
        override fun lastRemindedResetAt(localAccountId: String) = map[localAccountId]
        override fun markReminded(localAccountId: String, resetAtEpochMillis: Long) {
            map[localAccountId] = resetAtEpochMillis
        }
    }

    private fun state(id: String, remaining: Int, resetInMinutes: Long) = WidgetQuotaState(
        status = WidgetQuotaStatus.Fresh,
        providerName = "Gemini",
        providerId = "gemini",
        localAccountId = id,
        accountName = "Gemini $id",
        tone = WidgetQuotaTone.Success,
        clickTarget = WidgetClickTarget.Home,
        fields = listOf(
            WidgetField("gemini_5h_window", false, remaining, null, null, now.plusSeconds(resetInMinutes * 60), WidgetQuotaTone.Success, 18_000),
            WidgetField("gemini_weekly_window", false, 90, null, null, null, WidgetQuotaTone.Success, 604_800),
        ),
    )

    private fun publisher(states: List<WidgetQuotaState>, alertsEnabled: Boolean = true) = UseItReminderPublisher(
        loader = { states },
        sink = { posted += it },
        optionsReader = { NotificationRequestOptions(notificationPermissionAvailable = true, quotaAlertsEnabled = alertsEnabled) },
        store = store,
        formatTime = { "18:40" },
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `reminds once per reset window for accounts about to waste quota`() = runTest {
        val p = publisher(listOf(state("a", 70, 30), state("b", 70, 200)))

        p.publish(dummyState())
        p.publish(dummyState())

        assertEquals(1, posted.size)
        assertEquals(listOf(70, "18:40"), posted.single().body.formatArgs)
    }

    @Test
    fun `respects the quota alerts switch`() = runTest {
        publisher(listOf(state("a", 70, 30)), alertsEnabled = false).publish(dummyState())

        assertTrue(posted.isEmpty())
    }

    // publisher 不看傳入的 state（它自己讀全部帳號），給一個空殼即可。
    private fun dummyState() = com.kmnexus.codexmeter.domain.quota.CurrentQuotaState(
        status = com.kmnexus.codexmeter.domain.quota.CurrentQuotaStatus.NoData,
        freshness = com.kmnexus.codexmeter.domain.quota.CurrentQuotaFreshness.entries.first(),
        account = null,
        snapshot = null,
        latestAttempt = null,
        primaryWindow = null,
        secondaryWindows = emptyList(),
        primaryWindowCanAlert = false,
        error = null,
    )
}
