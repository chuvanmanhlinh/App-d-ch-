package com.appdichtruyen.engine

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.roundToInt

data class ScrollDeltaResult(
    val changed: Boolean,
    val dyPixels: Int,
    val confidence: Float,
    val diffScore: Float
)

/**
 * Ultra-fast pure-Kotlin grayscale strip tracker (~2-4ms/frame) that detects
 * vertical scrolling so translated speech bubbles glide with the comic page in real time.
 */
class ScrollDeltaTracker(
    private val sampleWidth: Int = 96,
    private val diffThreshold: Float = 4.0f
) {
    private var prevGray: IntArray? = null
    private var prevW: Int = 0
    private var prevH: Int = 0
    private var scaleY: Float = 1f

    fun reset() {
        prevGray = null
    }

    private fun toSmallGray(bitmap: Bitmap): Triple<IntArray, Int, Int> {
        val bw = bitmap.width.coerceAtLeast(1)
        val bh = bitmap.height.coerceAtLeast(1)
        val w = sampleWidth.coerceAtMost(bw)
        val h = ((bh.toFloat() / bw.toFloat()) * w).roundToInt().coerceAtLeast(32)
        scaleY = bh.toFloat() / h.toFloat()

        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, false)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) {
            scaled.recycle()
        }

        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            gray[i] = (r * 77 + g * 150 + b * 29) shr 8
        }
        return Triple(gray, w, h)
    }

    fun analyze(bitmap: Bitmap): ScrollDeltaResult {
        val (curr, w, h) = toSmallGray(bitmap)
        val prev = prevGray
        if (prev == null || prevW != w || prevH != h) {
            prevGray = curr
            prevW = w
            prevH = h
            return ScrollDeltaResult(changed = true, dyPixels = 0, confidence = 0f, diffScore = 255f)
        }

        var sumDiff = 0L
        for (i in curr.indices) {
            sumDiff += abs(curr[i] - prev[i])
        }
        val meanDiff = sumDiff.toFloat() / curr.size.toFloat()
        if (meanDiff < diffThreshold) {
            return ScrollDeltaResult(changed = false, dyPixels = 0, confidence = 1f, diffScore = meanDiff)
        }

        // Match central horizontal band from previous frame against shifted bands in current frame
        val xStart = (w * 0.15f).toInt()
        val xEnd = (w * 0.85f).toInt()
        val bandTop = (h * 0.35f).toInt()
        val bandHeight = (h * 0.30f).toInt().coerceAtLeast(12)
        val maxShift = (h * 0.32f).toInt()

        var bestShift = 0
        var bestSad = Float.MAX_VALUE
        var worstSad = 0f

        for (shift in -maxShift..maxShift) {
            val targetTop = bandTop + shift
            if (targetTop < 0 || targetTop + bandHeight > h) continue

            var sad = 0L
            var count = 0
            for (row in 0 until bandHeight step 2) {
                val prevRowOffset = (bandTop + row) * w
                val currRowOffset = (targetTop + row) * w
                for (col in xStart until xEnd step 2) {
                    sad += abs(prev[prevRowOffset + col] - curr[currRowOffset + col])
                    count++
                }
            }
            if (count == 0) continue
            val avgSad = sad.toFloat() / count.toFloat()
            if (avgSad < bestSad) {
                bestSad = avgSad
                bestShift = shift
            }
            if (avgSad > worstSad) {
                worstSad = avgSad
            }
        }

        prevGray = curr
        prevW = w
        prevH = h

        // Confidence: strong match has very low SAD compared to background variance
        val conf = if (worstSad > 10f) {
            (1f - (bestSad / worstSad)).coerceIn(0f, 1f)
        } else {
            0f
        }
        val dyFull = if (conf >= 0.65f) (bestShift * scaleY).roundToInt() else 0

        return ScrollDeltaResult(
            changed = true,
            dyPixels = dyFull,
            confidence = conf,
            diffScore = meanDiff
        )
    }
}
