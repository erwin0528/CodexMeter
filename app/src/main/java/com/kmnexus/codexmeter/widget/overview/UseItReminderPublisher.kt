package com.kmnexus.codexmeter.widget.overview

import android.app.PendingIntent
import android.content.Context
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.notification.NotificationChannels
import com.kmnexus.codexmeter.notification.NotificationPendingIntentMetadata
import com.kmnexus.codexmeter.notification.NotificationRequest
import com.kmnexus.codexmeter.notification.NotificationRequestOptionsReader
import com.kmnexus.codexmeter.notification.NotificationSink
import com.kmnexus.codexmeter.notification.NotificationText
import com.kmnexus.codexmeter.refresh.CurrentQuotaStatePublisher
import com.kmnexus.codexmeter.widget.WidgetOverviewLoader
import java.time.Clock
import java.time.Instant

/** 記住每個帳號已經針對哪一次重置提醒過，同一個週期只提醒一次。 */
interface UseItReminderStore {
    fun lastRemindedResetAt(localAccountId: String): Long?
    fun markReminded(localAccountId: String, resetAtEpochMillis: Long)
}

class SharedPreferencesUseItReminderStore(context: Context) : UseItReminderStore {
    private val prefs = context.applicationContext.getSharedPreferences("use_it_reminder", Context.MODE_PRIVATE)

    override fun lastRemindedResetAt(localAccountId: String): Long? =
        prefs.getLong(localAccountId, -1L).takeIf { it >= 0 }

    override fun markReminded(localAccountId: String, resetAtEpochMillis: Long) {
        prefs.edit().putLong(localAccountId, resetAtEpochMillis).apply()
    }
}

/**
 * 「快重置還剩很多」提醒：每次刷新後檢查所有帳號，符合 [UseItAdvisor.isExpiringUnused] 就通知。
 * 跟著 App 原本的「額度警示」開關與通知權限走，關掉警示就不會吵。
 */
class UseItReminderPublisher(
    private val loader: WidgetOverviewLoader,
    private val sink: NotificationSink,
    private val optionsReader: NotificationRequestOptionsReader,
    private val store: UseItReminderStore,
    private val formatTime: (Instant) -> String,
    private val clock: Clock = Clock.systemUTC(),
) : CurrentQuotaStatePublisher {
    override suspend fun publish(state: CurrentQuotaState) {
        val options = optionsReader.currentOptions()
        if (!options.notificationPermissionAvailable || !options.quotaAlertsEnabled) return
        val now = clock.instant()
        loader.loadOverview()
            .mapNotNull { it.toOverviewRow() }
            .filter { UseItAdvisor.isExpiringUnused(it, now) }
            .forEach { row -> remind(row) }
    }

    private fun remind(row: OverviewRow) {
        val accountId = row.localAccountId ?: return
        val main = row.main ?: return
        val resetAt = main.resetAtEpochMillis ?: return
        val remaining = main.percent ?: return
        if (store.lastRemindedResetAt(accountId) == resetAt) return

        val notificationId = NOTIFICATION_ID_BASE + (accountId.hashCode() and 0x3FF)
        sink.post(
            NotificationRequest(
                notificationId = notificationId,
                channelId = NotificationChannels.QUOTA_ALERTS_CHANNEL_ID,
                title = NotificationText(R.string.waste_reminder_title_format, listOf(row.accountName)),
                body = NotificationText(
                    R.string.waste_reminder_body_format,
                    listOf(remaining, formatTime(Instant.ofEpochMilli(resetAt))),
                ),
                pendingIntent = NotificationPendingIntentMetadata(
                    requestCode = notificationId,
                    flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
                ongoing = false,
                // 過了重置時間這則通知就沒意義了，自動消失。
                timeoutAfterMillis = (resetAt - clock.millis()).coerceAtLeast(60_000L),
            ),
        )
        store.markReminded(accountId, resetAt)
    }

    private companion object {
        const val NOTIFICATION_ID_BASE = 7_100
    }
}
