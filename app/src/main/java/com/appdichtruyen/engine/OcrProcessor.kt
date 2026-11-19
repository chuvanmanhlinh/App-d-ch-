package com.appdichtruyen.engine

import android.graphics.Bitmap
import android.graphics.Rect
import com.appdichtruyen.model.OcrLine
import com.appdichtruyen.model.SourceLanguage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlin.math.max
import kotlin.math.min

/**
 * 100% Offline on-device OCR powered by Google ML Kit v2
 * (supports Auto multi-script detection, Latin, Japanese, Korean, and Chinese).
 */
class OcrProcessor {
    private val recognizers = mutableMapOf<SourceLanguage, TextRecognizer>()

    private fun getRecognizer(lang: SourceLanguage): TextRecognizer {
        val target = if (lang == SourceLanguage.AUTO) SourceLanguage.ENGLISH else lang
        return recognizers.getOrPut(target) {
            when (target) {
                SourceLanguage.ENGLISH, SourceLanguage.AUTO ->
                    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                SourceLanguage.JAPANESE ->
                    TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                SourceLanguage.KOREAN ->
                    TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                SourceLanguage.CHINESE ->
                    TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            }
        }
    }

    suspend fun detectLines(bitmap: Bitmap, lang: SourceLanguage): List<OcrLine> {
        if (lang != SourceLanguage.AUTO) {
            return detectWithRecognizer(bitmap, lang)
        }

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val candidates = coroutineScope {
            val latinJob = async { runCatching { detectFromImage(inputImage, SourceLanguage.ENGLISH) }.getOrDefault(emptyList()) }
            val zhJob = async { runCatching { detectFromImage(inputImage, SourceLanguage.CHINESE) }.getOrDefault(emptyList()) }
            val jaJob = async { runCatching { detectFromImage(inputImage, SourceLanguage.JAPANESE) }.getOrDefault(emptyList()) }
            val koJob = async { runCatching { detectFromImage(inputImage, SourceLanguage.KOREAN) }.getOrDefault(emptyList()) }
            listOf(
                SourceLanguage.ENGLISH to latinJob.await(),
                SourceLanguage.CHINESE to zhJob.await(),
                SourceLanguage.JAPANESE to jaJob.await(),
                SourceLanguage.KOREAN to koJob.await()
            )
        }
        return mergeAutoLines(candidates)
    }

    private suspend fun detectWithRecognizer(bitmap: Bitmap, lang: SourceLanguage): List<OcrLine> {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        return detectFromImage(inputImage, lang)
    }

    private suspend fun detectFromImage(inputImage: InputImage, lang: SourceLanguage): List<OcrLine> {
        val recognizer = getRecognizer(lang)
        val result = recognizer.process(inputImage).await()

        val lines = mutableListOf<OcrLine>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val box: Rect = line.boundingBox ?: continue
                val text = line.text.trim()
                if (text.isEmpty()) continue
                val isVert = (box.height() > box.width() * 1.45f) && text.length >= 2
                lines.add(OcrLine(text = text, bounds = Rect(box), isVertical = isVert))
            }
        }
        return lines
    }

    private fun mergeAutoLines(batches: List<Pair<SourceLanguage, List<OcrLine>>>): List<OcrLine> {
        data class ScoredLine(val line: OcrLine, val score: Int)

        val scored = mutableListOf<ScoredLine>()
        for ((engine, lines) in batches) {
            for (line in lines) {
                scored.add(ScoredLine(line, scoreLineForEngine(line.text, engine)))
            }
        }
        scored.sortByDescending { it.score }

        val kept = mutableListOf<OcrLine>()
        for (item in scored) {
            val r = item.line.bounds
            val overlapsExisting = kept.any { existing ->
                iouRatio(r, existing.bounds) > 0.45f
            }
            if (!overlapsExisting) {
                kept.add(item.line)
            }
        }
        return kept
    }

    private fun iouRatio(a: Rect, b: Rect): Float {
        if (!Rect.intersects(a, b)) return 0f
        val iw = min(a.right, b.right) - max(a.left, b.left)
        val ih = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (iw <= 0 || ih <= 0) return 0f
        val interArea = iw.toFloat() * ih.toFloat()
        val areaA = (a.width() * a.height()).coerceAtLeast(1).toFloat()
        val areaB = (b.width() * b.height()).coerceAtLeast(1).toFloat()
        val unionArea = (areaA + areaB - interArea).coerceAtLeast(1f)
        return interArea / unionArea
    }

    private fun scoreLineForEngine(text: String, engine: SourceLanguage): Int {
        var kana = 0
        var hangul = 0
        var hanzi = 0
        var latin = 0
        for (ch in text) {
            val code = ch.code
            when {
                code in 0x3040..0x30FF -> kana++
                code in 0xAC00..0xD7AF || code in 0x1100..0x11FF -> hangul++
                code in 0x4E00..0x9FFF -> hanzi++
                ch.isLetter() -> latin++
            }
        }
        return when {
            kana > 0 -> if (engine == SourceLanguage.JAPANESE) 1000 + text.length else 100
            hangul > 0 -> if (engine == SourceLanguage.KOREAN) 1000 + text.length else 100
            hanzi > 0 -> when (engine) {
                SourceLanguage.CHINESE -> 900 + text.length
                SourceLanguage.JAPANESE -> 850 + text.length
                else -> 50
            }
            latin > 0 -> if (engine == SourceLanguage.ENGLISH) 1000 + text.length else 200
            else -> 10
        }
    }

    fun close() {
        recognizers.values.forEach { runCatching { it.close() } }
        recognizers.clear()
    }
}
