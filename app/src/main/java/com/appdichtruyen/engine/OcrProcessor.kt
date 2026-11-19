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
import kotlinx.coroutines.tasks.await

/**
 * 100% Offline on-device OCR powered by Google ML Kit v2
 * (supports English/Latin, Japanese, Korean, and Chinese).
 */
class OcrProcessor {
    private val recognizers = mutableMapOf<SourceLanguage, TextRecognizer>()

    private fun getRecognizer(lang: SourceLanguage): TextRecognizer {
        return recognizers.getOrPut(lang) {
            when (lang) {
                SourceLanguage.ENGLISH ->
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
        val recognizer = getRecognizer(lang)
        val inputImage = InputImage.fromBitmap(bitmap, 0)
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

    fun close() {
        recognizers.values.forEach { runCatching { it.close() } }
        recognizers.clear()
    }
}
