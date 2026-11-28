package com.kmnexus.codexmeter.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kmnexus.codexmeter.domain.cat.CatMemory
import com.kmnexus.codexmeter.domain.cat.CatMemoryStore
import com.kmnexus.codexmeter.domain.cat.CatSnack
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import com.kmnexus.codexmeter.domain.theme.AppearancePreferenceStore
import com.kmnexus.codexmeter.domain.theme.CatEffectsLevel
import com.kmnexus.codexmeter.domain.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AppearancePreferences(
    internal val dataStore: DataStore<Preferences>,
) : AppearancePreferenceStore, CatMemoryStore {
    override val themeMode: Flow<ThemeMode> =
        dataStore.data.map { prefs -> ThemeMode.fromStorage(prefs[Keys.THEME_MODE]) }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.storageValue }
    }

    override val catEffects: Flow<CatEffectsLevel> =
        dataStore.data.map { prefs -> CatEffectsLevel.fromStorage(prefs[Keys.CAT_EFFECTS]) }

    override suspend fun setCatEffects(level: CatEffectsLevel) {
        dataStore.edit { it[Keys.CAT_EFFECTS] = level.storageValue }
    }

    // 額度貓的記憶跟外觀設定放同一個檔案：都是「這支手機上的個人化」，量很小
    override val memory: Flow<CatMemory> = dataStore.data.map { prefs ->
        CatMemory(
            totalFeedCount = prefs[Keys.CAT_FEEDS] ?: 0,
            totalPetCount = prefs[Keys.CAT_PETS] ?: 0,
            daysVisited = prefs[Keys.CAT_DAYS] ?: 0,
            lastVisitDate = prefs[Keys.CAT_LAST_VISIT]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            recentLineIds = prefs[Keys.CAT_RECENT]?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            snack = CatSnack.fromStorage(prefs[Keys.CAT_SNACK]),
        )
    }

    override suspend fun update(transform: (CatMemory) -> CatMemory) {
        val next = transform(memory.first())
        dataStore.edit { prefs ->
            prefs[Keys.CAT_FEEDS] = next.totalFeedCount
            prefs[Keys.CAT_PETS] = next.totalPetCount
            prefs[Keys.CAT_DAYS] = next.daysVisited
            next.lastVisitDate?.let { prefs[Keys.CAT_LAST_VISIT] = it.toString() }
            prefs[Keys.CAT_RECENT] = next.recentLineIds.joinToString(",")
            prefs[Keys.CAT_SNACK] = next.snack.storageValue
        }
    }

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val CAT_EFFECTS = stringPreferencesKey("cat_effects")
        val CAT_FEEDS = intPreferencesKey("cat_feed_count")
        val CAT_PETS = intPreferencesKey("cat_pet_count")
        val CAT_DAYS = intPreferencesKey("cat_days_visited")
        val CAT_LAST_VISIT = stringPreferencesKey("cat_last_visit")
        val CAT_RECENT = stringPreferencesKey("cat_recent_lines")
        val CAT_SNACK = stringPreferencesKey("cat_snack")
    }

    companion object {
        fun create(file: File): AppearancePreferences =
            AppearancePreferences(
                PreferenceDataStoreFactory.create(
                    produceFile = { file },
                ),
            )
    }
}
