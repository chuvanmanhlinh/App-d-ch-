package com.appdichtruyen.config

import android.content.Context
import android.content.SharedPreferences
import com.appdichtruyen.model.SourceLanguage
import com.appdichtruyen.model.TranslateMode

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("app_dich_truyen_prefs", Context.MODE_PRIVATE)

    var sourceLanguage: SourceLanguage
        get() = SourceLanguage.fromCode(prefs.getString(KEY_SOURCE_LANG, SourceLanguage.ENGLISH.code))
        set(value) = prefs.edit().putString(KEY_SOURCE_LANG, value.code).apply()

    var translateMode: TranslateMode
        get() = TranslateMode.fromCode(prefs.getString(KEY_TRANSLATE_MODE, TranslateMode.ONLINE_FAST.code))
        set(value) = prefs.edit().putString(KEY_TRANSLATE_MODE, value.code).apply()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_KEY, value.trim()).apply()

    var autoFallbackOffline: Boolean
        get() = prefs.getBoolean(KEY_AUTO_FALLBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_FALLBACK, value).apply()

    var scrollDebounceMs: Long
        get() = prefs.getLong(KEY_SCROLL_DEBOUNCE, 180L)
        set(value) = prefs.edit().putLong(KEY_SCROLL_DEBOUNCE, value.coerceIn(80L, 600L)).apply()

    var overlayAlpha: Int
        get() = prefs.getInt(KEY_OVERLAY_ALPHA, 245)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_ALPHA, value.coerceIn(140, 255)).apply()

    companion object {
        private const val KEY_SOURCE_LANG = "source_lang"
        private const val KEY_TRANSLATE_MODE = "translate_mode"
        private const val KEY_GEMINI_KEY = "gemini_api_key"
        private const val KEY_AUTO_FALLBACK = "auto_fallback_offline"
        private const val KEY_SCROLL_DEBOUNCE = "scroll_debounce_ms"
        private const val KEY_OVERLAY_ALPHA = "overlay_alpha"
    }
}
