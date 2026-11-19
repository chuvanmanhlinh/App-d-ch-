package com.appdichtruyen.engine

import android.util.LruCache
import com.appdichtruyen.config.AppPreferences
import com.appdichtruyen.model.SourceLanguage
import com.appdichtruyen.model.SpeechBubble
import com.appdichtruyen.model.TranslateMode
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Hybrid Online (Google Fast / Gemini 2.5 Flash) + 100% Offline (ML Kit) translation engine
 * with in-memory LRU cache and automatic Offline fallback when network drops.
 */
class TranslationRepository(private val prefs: AppPreferences) {

    private val httpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(8, 2, TimeUnit.MINUTES))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        .build()

    private val cache = LruCache<String, String>(1500)
    private val mlkitTranslators = mutableMapOf<SourceLanguage, Translator>()

    private fun normalizeOcrText(text: String): String =
        text.trim()
            .replace(Regex("-\\s+"), "")
            .replace(Regex("\\s+"), " ")

    private fun cacheKey(lang: SourceLanguage, mode: TranslateMode, text: String): String =
        "${lang.code}|${mode.code}|${normalizeOcrText(text)}"

    suspend fun translateBubbles(bubbles: List<SpeechBubble>): List<SpeechBubble> =
        withContext(Dispatchers.IO) {
            if (bubbles.isEmpty()) return@withContext bubbles

            val lang = prefs.sourceLanguage
            val mode = prefs.translateMode
            val uncached = mutableListOf<SpeechBubble>()

            for (b in bubbles) {
                val hit = cache.get(cacheKey(lang, mode, b.sourceText))
                if (hit != null) {
                    b.translatedText = hit
                    b.engineTag = "Cache"
                } else {
                    uncached.add(b)
                }
            }

            if (uncached.isEmpty()) return@withContext bubbles

            try {
                when (mode) {
                    TranslateMode.ONLINE_GEMINI -> {
                        if (prefs.geminiApiKey.isNotBlank()) {
                            runCatching { translateBatchGemini(uncached) }
                                .onFailure { translateBatchGoogle(uncached, lang) }
                        } else {
                            translateBatchGoogle(uncached, lang)
                        }
                    }
                    TranslateMode.OFFLINE -> translateBatchOffline(uncached, lang)
                    TranslateMode.ONLINE_FAST -> translateBatchGoogle(uncached, lang)
                }
            } catch (e: Exception) {
                if (mode != TranslateMode.OFFLINE && prefs.autoFallbackOffline) {
                    runCatching { translateBatchOffline(uncached, lang) }
                        .onFailure {
                            uncached.forEach { b ->
                                if (b.translatedText.isBlank()) {
                                    b.translatedText = b.sourceText
                                    b.engineTag = "Offline chưa tải"
                                }
                            }
                        }
                } else {
                    uncached.forEach { b ->
                        if (b.translatedText.isBlank()) {
                            b.translatedText = b.sourceText
                            b.engineTag = "Lỗi mạng"
                        }
                    }
                }
            }

            for (b in uncached) {
                if (b.translatedText.isNotBlank() && b.translatedText != b.sourceText) {
                    cache.put(cacheKey(lang, mode, b.sourceText), b.translatedText)
                }
            }
            bubbles
        }

    private suspend fun translateBatchGoogle(bubbles: List<SpeechBubble>, lang: SourceLanguage) {
        val sl = when (lang) {
            SourceLanguage.AUTO -> "auto"
            SourceLanguage.CHINESE -> "zh-CN"
            else -> lang.code
        }

        // Translate each bubble independently in parallel (max 5 concurrent)
        // so no bubble is ever dropped or merged by a delimiter failure.
        val gate = Semaphore(8)
        coroutineScope {
            bubbles.map { bubble ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val cleaned = normalizeOcrText(bubble.sourceText)
                        val translated = runCatching {
                            callGoogleTranslateApi(cleaned, sl, "vi")
                        }.getOrElse {
                            runCatching { callGoogleTranslateApi(cleaned, sl, "vi") }.getOrNull()
                        }
                        if (!translated.isNullOrBlank()) {
                            bubble.translatedText = translated
                            bubble.engineTag = "Google"
                        } else {
                            bubble.translatedText = bubble.sourceText
                            bubble.engineTag = "Lỗi mạng"
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun callGoogleTranslateApi(text: String, sl: String, tl: String): String {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$tl&dt=t&q=$encoded"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Google HTTP ${resp.code}")
            val body = resp.body?.string() ?: return text
            val arr = JSONArray(body).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val seg = arr.getJSONArray(i).optString(0, "")
                sb.append(seg)
            }
            return sb.toString().trim()
        }
    }

    private fun translateBatchGemini(bubbles: List<SpeechBubble>) {
        val apiKey = prefs.geminiApiKey.trim()
        val items = JSONArray()
        bubbles.forEachIndexed { idx, b ->
            items.put(JSONObject().put("id", idx).put("text", b.sourceText))
        }

        val prompt = """
            Bạn là biên dịch viên truyện tranh 18+ (manga/manhwa/manhua/doujinshi/comic) chuyên nghiệp sang tiếng Việt.
            Quy tắc bắt buộc:
            1. Dịch ĐẦY ĐỦ NGHĨA, TRUNG THỰC 100%, giữ nguyên sắc thái hội thoại người lớn (18+), tiếng lóng hoặc từ ngữ nhạy cảm/táo bạo đúng ngữ cảnh truyện — TUYỆT ĐỐI KHÔNG kiểm duyệt, không lược bỏ, không nói giảm nói tránh.
            2. Văn phong tiếng Việt tự nhiên, mượt mà, đúng ngôi xưng của nhân vật.
            3. CHỈ trả về mảng JSON dạng [{"id": 0, "vi": "..."}].
            Input: $items
        """.trimIndent()

        val safetySettings = JSONArray()
            .put(JSONObject().put("category", "HARM_CATEGORY_HARASSMENT").put("threshold", "BLOCK_NONE"))
            .put(JSONObject().put("category", "HARM_CATEGORY_HATE_SPEECH").put("threshold", "BLOCK_NONE"))
            .put(JSONObject().put("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT").put("threshold", "BLOCK_NONE"))
            .put(JSONObject().put("category", "HARM_CATEGORY_DANGEROUS_CONTENT").put("threshold", "BLOCK_NONE"))

        val payload = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
            )
            .put("safetySettings", safetySettings)
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.15)
                    .put("responseMimeType", "application/json")
            )

        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Gemini HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("Empty Gemini response")
            val text = JSONObject(body)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            val parsed = JSONArray(text)
            val map = HashMap<Int, String>()
            for (i in 0 until parsed.length()) {
                val obj = parsed.getJSONObject(i)
                map[obj.getInt("id")] = obj.optString("vi", "").trim()
            }
            bubbles.forEachIndexed { idx, b ->
                b.translatedText = map[idx]?.takeIf { it.isNotBlank() } ?: b.sourceText
                b.engineTag = "Gemini AI"
            }
        }
    }

    private fun mlKitSourceCode(lang: SourceLanguage): String = when (lang) {
        SourceLanguage.ENGLISH, SourceLanguage.AUTO -> TranslateLanguage.ENGLISH
        SourceLanguage.JAPANESE -> TranslateLanguage.JAPANESE
        SourceLanguage.KOREAN -> TranslateLanguage.KOREAN
        SourceLanguage.CHINESE -> TranslateLanguage.CHINESE
    }

    private fun detectOfflineLang(text: String): SourceLanguage {
        for (ch in text) {
            val code = ch.code
            if (code in 0x3040..0x30FF) return SourceLanguage.JAPANESE
            if (code in 0xAC00..0xD7AF || code in 0x1100..0x11FF) return SourceLanguage.KOREAN
        }
        if (text.any { it.code in 0x4E00..0x9FFF }) return SourceLanguage.CHINESE
        return SourceLanguage.ENGLISH
    }

    private fun getOrCreateMlKitTranslator(lang: SourceLanguage): Translator {
        val resolved = if (lang == SourceLanguage.AUTO) SourceLanguage.ENGLISH else lang
        return mlkitTranslators.getOrPut(resolved) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(mlKitSourceCode(resolved))
                .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                .build()
            Translation.getClient(options)
        }
    }

    private suspend fun translateBatchOffline(bubbles: List<SpeechBubble>, lang: SourceLanguage) {
        val conditions = DownloadConditions.Builder().build()
        if (lang != SourceLanguage.AUTO) {
            val translator = getOrCreateMlKitTranslator(lang)
            translator.downloadModelIfNeeded(conditions).await()
            for (b in bubbles) {
                b.translatedText = translator.translate(b.sourceText).await().ifBlank { b.sourceText }
                b.engineTag = "Offline"
            }
            return
        }

        val grouped = bubbles.groupBy { detectOfflineLang(it.sourceText) }
        for ((detectedLang, group) in grouped) {
            val translator = getOrCreateMlKitTranslator(detectedLang)
            translator.downloadModelIfNeeded(conditions).await()
            for (b in group) {
                b.translatedText = translator.translate(b.sourceText).await().ifBlank { b.sourceText }
                b.engineTag = "Offline"
            }
        }
    }

    suspend fun downloadAllOfflineModels(onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val conditions = DownloadConditions.Builder().build()
        for (lang in SourceLanguage.entries.filter { it != SourceLanguage.AUTO }) {
            onProgress("Đang tải gói Offline: ${lang.label} → Việt...")
            val translator = getOrCreateMlKitTranslator(lang)
            translator.downloadModelIfNeeded(conditions).await()
        }
        onProgress("Đã tải xong toàn bộ gói Offline (Anh/Nhật/Hàn/Trung → Việt)!")
    }

    fun close() {
        mlkitTranslators.values.forEach { runCatching { it.close() } }
        mlkitTranslators.clear()
    }
}
