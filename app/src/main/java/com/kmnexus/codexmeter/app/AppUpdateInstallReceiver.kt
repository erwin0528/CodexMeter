package com.kmnexus.codexmeter.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * 接收 PackageInstaller 的安裝進度。系統要使用者按「更新」確認時會回 STATUS_PENDING_USER_ACTION，
 * 並附上確認畫面的 Intent，由這裡把它打開。其他狀態（成功、失敗）不需要處理：
 * 成功時 App 會被系統重新啟動成新版，失敗時系統自己會顯示原因。
 */
class AppUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        val confirm = intent.confirmIntent() ?: return
        runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun Intent.confirmIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
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
