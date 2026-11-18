package com.kmnexus.codexmeter.widget.overview

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.kmnexus.codexmeter.domain.quota.CurrentQuotaState
import com.kmnexus.codexmeter.refresh.CurrentQuotaStatePublisher
import com.kmnexus.codexmeter.refresh.QuotaRefreshWorker

class CodexMeterOverviewWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CodexMeterOverviewWidget()
}

/**
 * 任何帳號刷新完成後都會呼叫這裡：重寫每個總覽小工具的快照再重繪。
 * 沒有放總覽小工具時 getGlanceIds 是空的，幾乎沒有成本。
 */
class OverviewWidgetPublisher(context: Context) : CurrentQuotaStatePublisher {
    private val appContext = context.applicationContext

    override suspend fun publish(state: CurrentQuotaState) {
        val widget = CodexMeterOverviewWidget()
        GlanceAppWidgetManager(appContext).getGlanceIds(CodexMeterOverviewWidget::class.java).forEach { id ->
            CodexMeterOverviewWidget.writeSnapshot(appContext, id)
            widget.update(appContext, id)
        }
    }
}

/** 小工具上的重新整理鈕：排一次立即刷新所有帳號（同一時間只排一個，連點不會重複打 API）。 */
class OverviewRefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // 先讓小工具顯示「更新中…」，按下去馬上有回饋；新資料寫入時會清掉這個旗標。
        androidx.glance.appwidget.state.updateAppWidgetState(context, glanceId) { prefs ->
            prefs[CodexMeterOverviewWidget.REFRESHING_SINCE_KEY] = System.currentTimeMillis()
        }
        CodexMeterOverviewWidget().update(context, glanceId)
        val request = OneTimeWorkRequestBuilder<QuotaRefreshWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val UNIQUE_WORK_NAME = "overview_widget_refresh"
    }
}
