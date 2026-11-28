package com.kmnexus.codexmeter.ui.settings

import android.os.Build

object SettingsNotificationPermissionGate {
    fun shouldRequestPermission(
        sdkInt: Int,
        notificationPermissionGranted: Boolean,
        requestedEnabled: Boolean,
    ): Boolean =
        requestedEnabled &&
            sdkInt >= Build.VERSION_CODES.TIRAMISU &&
            !notificationPermissionGranted

    // 被拒絕兩次後系統會「永久拒絕」：之後再請求，視窗不會出現、直接回 denied，
    // 而且 shouldShowRequestPermissionRationale 也會變 false。這時只能帶使用者去系統設定頁自己打開。
    fun shouldOpenSystemSettingsAfterDenial(
        granted: Boolean,
        canShowRationale: Boolean,
    ): Boolean = !granted && !canShowRationale
}
