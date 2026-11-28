package com.kmnexus.codexmeter.domain.theme

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface AppearancePreferenceStore {
    val themeMode: Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)

    /** 額度貓特效等級；預設實作給測試替身用，正式存檔見 AppearancePreferences。 */
    val catEffects: Flow<CatEffectsLevel> get() = flowOf(CatEffectsLevel.FULL)
    suspend fun setCatEffects(level: CatEffectsLevel) = Unit
}
