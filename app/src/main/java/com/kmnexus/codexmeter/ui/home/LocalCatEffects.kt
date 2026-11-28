package com.kmnexus.codexmeter.ui.home

import androidx.compose.runtime.compositionLocalOf
import com.kmnexus.codexmeter.domain.theme.CatEffectsLevel

/** 設定頁選的額度貓特效等級，由 MainActivity 從偏好設定讀出來往下傳；畫面層不直接碰 DataStore。 */
val LocalCatEffects = compositionLocalOf { CatEffectsLevel.FULL }

/** 額度貓記憶的存取口，由 MainActivity 接上正式存檔；預覽與測試用記憶體版本。 */
val LocalCatMemoryStore = compositionLocalOf<com.kmnexus.codexmeter.domain.cat.CatMemoryStore> {
    com.kmnexus.codexmeter.domain.cat.InMemoryCatMemoryStore()
}
