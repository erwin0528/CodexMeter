package com.kmnexus.codexmeter.core.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import com.kmnexus.codexmeter.domain.settings.LanguagePreference
import java.util.Locale

object AppLocaleController {
    private val TRADITIONAL_CHINESE_REGIONS = setOf("TW", "HK", "MO")

    fun ensureFollowsSystemLocale(context: Context) {
        clearPlatformLocaleOverride(context)
        updateProcessResources(context, supportedSystemLocaleList())
    }

    fun localizedContext(context: Context, preference: LanguagePreference): Context {
        val localeList = localeListFor(preference) ?: supportedSystemLocaleList()
        return context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                setLocales(localeList)
            },
        )
    }

    internal fun supportedSystemLocaleList(
        systemLocales: LocaleList = Resources.getSystem().configuration.locales,
    ): LocaleList {
        for (index in 0 until systemLocales.size()) {
            val supportedLocale = supportedLocaleFor(systemLocales[index])
            if (supportedLocale != null) {
                return LocaleList(supportedLocale)
            }
        }
        return LocaleList(Locale.ENGLISH)
    }

    @Suppress("DEPRECATION")
    private fun updateProcessResources(context: Context, localeList: LocaleList) {
        val primaryLocale = localeList[0] ?: return
        Locale.setDefault(primaryLocale)
        context.resources.updateConfiguration(
            Configuration(context.resources.configuration).apply {
                setLocales(localeList)
            },
            context.resources.displayMetrics,
        )
    }

    private fun clearPlatformLocaleOverride(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            LocaleList.getEmptyLocaleList()
    }

    private fun localeListFor(preference: LanguagePreference): LocaleList? =
        preference.localeTag?.let(LocaleList::forLanguageTags)

    private fun supportedLocaleFor(locale: Locale): Locale? =
        when (locale.language.lowercase(Locale.ROOT)) {
            "en" -> Locale.ENGLISH
            "zh" -> when {
                // 正體中文（台灣用語）：繁體字系統（台灣、香港、澳門或 Hant 字集）都用 zh-TW 資源。
                locale.script.equals("Hant", ignoreCase = true) ||
                    locale.country.uppercase(Locale.ROOT) in TRADITIONAL_CHINESE_REGIONS ->
                    Locale.TRADITIONAL_CHINESE
                locale.country.equals("CN", ignoreCase = true) ||
                    locale.script.equals("Hans", ignoreCase = true) ->
                    Locale.SIMPLIFIED_CHINESE
                else -> null
            }
            else -> null
        }
}
