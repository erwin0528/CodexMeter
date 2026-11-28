package com.kmnexus.codexmeter.app

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Environment
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.update.AppUpdateDownloadResult
import com.kmnexus.codexmeter.domain.update.AppUpdateDownloadUseCase
import com.kmnexus.codexmeter.domain.update.AppUpdateInfo
import java.io.FileInputStream

class AndroidAppUpdateDownloader(
    context: Context,
) : AppUpdateDownloadUseCase {
    private val appContext = context.applicationContext

    override suspend fun download(update: AppUpdateInfo): AppUpdateDownloadResult {
        val downloadManager = appContext.getSystemService(DownloadManager::class.java)
            ?: return AppUpdateDownloadResult.Failure("download_manager_unavailable")

        return runCatching {
            val request = DownloadManager.Request(Uri.parse(update.apkDownloadUrl))
                .setTitle(update.apkFileName)
                .setDescription(
                    appContext.getString(R.string.settings_update_download_description, update.versionName),
                )
                .setMimeType(APK_MIME_TYPE)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, update.apkFileName)
            val downloadId = downloadManager.enqueue(request)
            AppUpdateDownloadResult.Enqueued(downloadId)
        }.getOrElse {
            AppUpdateDownloadResult.Failure("download_enqueue_failed")
        }
    }

    /**
     * 輪詢系統下載器直到完成或失敗。原本只交給系統下載器就結束，使用者得自己去「下載」資料夾找檔案，
     * 看起來就像按了沒反應；現在等它下載完，App 直接接手開安裝畫面。
     */
    override suspend fun awaitDownloaded(downloadId: Long): Boolean {
        val downloadManager = appContext.getSystemService(DownloadManager::class.java) ?: return false
        repeat(MAX_POLLS) {
            val status = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
                if (!cursor.moveToFirst()) return false
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            } ?: return false
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> return true
                DownloadManager.STATUS_FAILED -> return false
            }
            kotlinx.coroutines.delay(POLL_INTERVAL_MILLIS)
        }
        return false
    }

    /**
     * 用 PackageInstaller 安裝：直接把下載好的 APK 寫進安裝工作階段（session），
     * 不再把 content:// 網址丟給別的 App，避免某些手機上「權限沒轉過去、安裝畫面不跳」。
     * Android 仍會檢查簽章：只有跟現在裝的同一把金鑰簽的 APK 才能覆蓋安裝。
     * 寫檔是磁碟 IO，呼叫端要放在背景執行緒。
     */
    override fun launchInstall(downloadId: Long): Boolean = runCatching {
        val downloadManager = appContext.getSystemService(DownloadManager::class.java)
            ?: error("download_manager_unavailable")
        val installer = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            downloadManager.openDownloadedFile(downloadId).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    session.openWrite(SESSION_APK_NAME, 0, descriptor.statSize).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
            }
            session.commit(AppUpdateInstallReceiver.statusSender(appContext, sessionId))
        }
    }.isSuccess

    private companion object {
        const val SESSION_APK_NAME = "codexmeter.apk"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val POLL_INTERVAL_MILLIS = 1_000L

        // 最多等 10 分鐘；網路很慢時使用者還有系統下載通知可以點。
        const val MAX_POLLS = 600
    }
}
