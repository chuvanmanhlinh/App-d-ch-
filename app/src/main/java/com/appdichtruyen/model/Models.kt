package com.appdichtruyen.model

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect

data class OcrLine(
    val text: String,
    val bounds: Rect,
    val isVertical: Boolean = false
)

data class SpeechBubble(
    val id: String,
    val sourceText: String,
    val bounds: Rect,
    var translatedText: String = "",
    val bgColor: Int = Color.WHITE,
    val textColor: Int = Color.rgb(20, 20, 24),
    val isComplexBg: Boolean = false,
    var engineTag: String = "",
    val blurPatch: Bitmap? = null
) {
    fun shifted(dy: Int): SpeechBubble {
        val shiftedRect = Rect(bounds.left, bounds.top + dy, bounds.right, bounds.bottom + dy)
        return copy(bounds = shiftedRect)
    }
}

enum class SourceLanguage(val code: String, val label: String) {
    ENGLISH("en", "Tiếng Anh (Comic)"),
    JAPANESE("ja", "Tiếng Nhật (Manga)"),
    KOREAN("ko", "Tiếng Hàn (Manhwa)"),
    CHINESE("zh", "Tiếng Trung (Manhua)");

    companion object {
        fun fromCode(code: String?): SourceLanguage =
            entries.firstOrNull { it.code == code } ?: ENGLISH
    }
}

enum class TranslateMode(val code: String, val label: String) {
    ONLINE_FAST("online_fast", "Online Nhanh (Google)"),
    ONLINE_GEMINI("online_gemini", "Online AI (Gemini)"),
    OFFLINE("offline", "Offline 100% (ML Kit)");

    companion object {
        fun fromCode(code: String?): TranslateMode =
            entries.firstOrNull { it.code == code } ?: ONLINE_FAST
    }
}
