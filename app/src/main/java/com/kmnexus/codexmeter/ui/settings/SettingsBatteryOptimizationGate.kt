package com.kmnexus.codexmeter.ui.settings

object SettingsBatteryOptimizationGate {
    /**
     * Offer the optional "ignore battery optimization" guidance only when background refresh is on and
     * the app is still subject to optimization. A null status means we could not read it, so we stay
     * quiet rather than nag. Exemption never bypasses Doze entirely; it only improves reliability.
     */
    fun shouldOfferExemption(
        batteryOptimizationIgnored: Boolean?,
        backgroundRefreshEnabled: Boolean,
    ): Boolean =
        backgroundRefreshEnabled && batteryOptimizationIgnored == false

    // 三星 One UI 另有自己的「休眠應用程式」名單，跟 Android 的電池最佳化是兩回事，
    // App 讀不到自己在不在名單裡，所以只在三星手機、背景刷新開著時顯示文字指引。
    fun shouldShowSamsungSleepHint(
        manufacturer: String,
        backgroundRefreshEnabled: Boolean,
    ): Boolean =
        backgroundRefreshEnabled && manufacturer.equals("samsung", ignoreCase = true)
}
