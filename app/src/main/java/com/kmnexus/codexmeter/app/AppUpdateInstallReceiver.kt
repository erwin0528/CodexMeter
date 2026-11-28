package com.kmnexus.codexmeter.app

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.notification.NotificationChannels

/**
 * 接收 PackageInstaller 的安裝進度。系統要使用者按「更新」確認時會回 STATUS_PENDING_USER_ACTION，
 * 並附上確認畫面的 Intent，由這裡把它打開。其他狀態（成功、失敗）不需要處理：
 * 成功時 App 會被系統重新啟動成新版，失敗時系統自己會顯示原因。
 */
class AppUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        val confirm = intent.confirmIntent()?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        // Android 14 起，App 不在前景時從 receiver 開畫面會被系統默默擋掉（不會丟錯誤）。
        // 所以在前景才直接開；不在前景就改發一則「點我安裝」通知，點通知屬於系統允許的例外。
        if (isAppInForeground()) {
            runCatching { context.startActivity(confirm) }
        } else {
            postInstallNotification(context, confirm)
        }
    }

    private fun isAppInForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun postInstallNotification(context: Context, confirm: Intent) {
        val contentIntent = PendingIntent.getActivity(
            context,
            INSTALL_NOTIFICATION_ID,
            confirm,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.APP_UPDATES_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_status)
            .setContentTitle(context.getString(R.string.app_update_install_ready_title))
            .setContentText(context.getString(R.string.app_update_install_ready_text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        // 沒有通知權限時 notify 會被系統忽略；使用者仍可回設定頁按「安裝」。
        runCatching { NotificationManagerCompat.from(context).notify(INSTALL_NOTIFICATION_ID, notification) }
    }

    private fun Intent.confirmIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        private const val INSTALL_NOTIFICATION_ID = 7301

        // 系統會往這個 PendingIntent 填入狀態，所以必須是 MUTABLE；只指向本 App 的 receiver，不外流。
        fun statusSender(context: Context, sessionId: Int): IntentSender =
            PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, AppUpdateInstallReceiver::class.java).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ).intentSender
    }
}
