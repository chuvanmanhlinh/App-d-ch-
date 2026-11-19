package com.appdichtruyen.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.appdichtruyen.model.OcrLine
import com.appdichtruyen.model.SourceLanguage
import com.appdichtruyen.model.SpeechBubble
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object BubbleGrouper {

    private val noiseRegex = Regex("^[\\s\\p{Punct}0-9_~`|\\\\/<>]+$")
    private val pageNumberRegex = Regex("^(?:p\\.?|ch\\.?|chap\\.?|ep\\.?|vol\\.?)?\\s*\\d+(?:\\s*/\\s*\\d+)?$", RegexOption.IGNORE_CASE)
    private val timeRegex = Regex("^\\d{1,2}:\\d{2}(?:\\s*[AP]M)?$", RegexOption.IGNORE_CASE)
    private val urlNoiseRegex = Regex("(https?://|www\\.|\\.com\\b|\\.net\\b|\\.org\\b)", RegexOption.IGNORE_CASE)

    fun isMeaningfulText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.length == 1 && trimmed[0].code < 128) return false
        if (noiseRegex.matches(trimmed)) return false
        if (pageNumberRegex.matches(trimmed)) return false
        if (timeRegex.matches(trimmed)) return false
        if (urlNoiseRegex.containsMatchIn(trimmed) && trimmed.length < 40) return false

        // Require at least 2 letters for pure-ASCII text to filter stray SFX/UI glyphs
        val hasNonAscii = trimmed.any { it.code >= 128 }
        if (!hasNonAscii) {
            val letterCount = trimmed.count { it.isLetter() }
            if (letterCount < 2) return false
        }
        return true
    }

    fun groupLines(
        lines: List<OcrLine>,
        bitmap: Bitmap?,
        language: SourceLanguage,
        screenWidth: Int = bitmap?.width ?: 0,
        screenHeight: Int = bitmap?.height ?: 0
    ): List<SpeechBubble> {
        val topSafeY = if (screenHeight > 0) (screenHeight * 0.042f).roundToInt() else 0
        val bottomSafeY = if (screenHeight > 0) (screenHeight * 0.945f).roundToInt() else Int.MAX_VALUE

        val valid = lines.filter {
            it.bounds.width() >= 6 &&
                it.bounds.height() >= 6 &&
                it.bounds.bottom > topSafeY &&
                it.bounds.top < bottomSafeY &&
                isMeaningfulText(it.text)
        }
        if (valid.isEmpty()) return emptyList()

        val n = valid.size
        val parent = IntArray(n) { it }

        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        val preferVertical = (language == SourceLanguage.JAPANESE || language == SourceLanguage.AUTO)
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val verticalPair = preferVertical && (valid[i].isVertical || valid[j].isVertical)
                if (shouldMerge(valid[i], valid[j], verticalPair, bitmap)) {
                    union(i, j)
                }
            }
        }

        val clusters = LinkedHashMap<Int, MutableList<OcrLine>>()
        for (i in 0 until n) {
            clusters.getOrPut(find(i)) { mutableListOf() }.add(valid[i])
        }

        val rawRects = mutableListOf<Pair<String, Rect>>()
        for (cluster in clusters.values) {
            val vertCount = cluster.count { it.isVertical }
            val useVertical = preferVertical && vertCount > cluster.size / 2
            val mergedText = joinClusterText(cluster, useVertical)
            if (!isMeaningfulText(mergedText)) continue

            val left = cluster.minOf { it.bounds.left }
            val top = cluster.minOf { it.bounds.top }
            val right = cluster.maxOf { it.bounds.right }
            val bottom = cluster.maxOf { it.bounds.bottom }
            val rect = Rect(left, top, right, bottom)

            // Reject giant accidental page-wide merges unless it's a single compact block
            if (screenWidth > 0 && screenHeight > 0 &&
                rect.width() > screenWidth * 0.88f &&
                rect.height() > screenHeight * 0.45f &&
                cluster.size > 6
            ) {
                continue
            }

            rawRects.add(mergedText to rect)
        }

        resolveOverlaps(rawRects.map { it.second })

        val bubbles = mutableListOf<SpeechBubble>()
        for ((mergedText, rect) in rawRects) {
            if (rect.width() < 8 || rect.height() < 8) continue

            val (bgColor, textColor, isComplex) = if (bitmap != null) {
                sampleBubbleColors(bitmap, rect)
            } else {
                Triple(Color.WHITE, Color.rgb(20, 20, 24), false)
            }

            val blurPatch = if (bitmap != null) {
                createBlurredPatch(bitmap, rect)
            } else {
                null
            }

            val id = "${mergedText.hashCode()}_${rect.left / 24}_${rect.top / 24}"
            bubbles.add(
                SpeechBubble(
                    id = id,
                    sourceText = mergedText,
                    bounds = rect,
                    bgColor = bgColor,
                    textColor = textColor,
                    isComplexBg = isComplex,
                    blurPatch = blurPatch
                )
            )
        }

        return bubbles.sortedWith(compareBy({ it.bounds.top / 48 }, { it.bounds.left }))
    }

    private fun shouldMerge(
        a: OcrLine,
        b: OcrLine,
        verticalMode: Boolean,
        bitmap: Bitmap?
    ): Boolean {
        val sizeA = if (a.isVertical) a.bounds.width() else a.bounds.height()
        val sizeB = if (b.isVertical) b.bounds.width() else b.bounds.height()
        val ratio = max(sizeA, sizeB).toFloat() / max(1, min(sizeA, sizeB)).toFloat()
        if (ratio > 1.65f) return false

        if (verticalMode) {
            val charW = max(12, min(a.bounds.width(), b.bounds.width()))
            val padX = (charW * 0.60f).roundToInt()
            val padY = (charW * 0.40f).roundToInt()

            val overlapY = min(a.bounds.bottom, b.bounds.bottom) - max(a.bounds.top, b.bounds.top)
            if (overlapY < -padY && abs(a.bounds.centerY() - b.bounds.centerY()) > max(a.bounds.height(), b.bounds.height())) {
                return false
            }
            if (overlapY < 0 && abs(a.bounds.centerX() - b.bounds.centerX()) > charW * 1.35f) {
                return false
            }
            val expanded = Rect(
                a.bounds.left - padX,
                a.bounds.top - padY,
                a.bounds.right + padX,
                a.bounds.bottom + padY
            )
            if (!Rect.intersects(expanded, b.bounds)) return false
            if (bitmap != null && hasVisualSeparator(bitmap, a.bounds, b.bounds, verticalGap = false)) {
                return false
            }
            return true
        }

        val lineH = max(12, min(a.bounds.height(), b.bounds.height()))
        val overlapX = min(a.bounds.right, b.bounds.right) - max(a.bounds.left, b.bounds.left)
        val overlapY = min(a.bounds.bottom, b.bounds.bottom) - max(a.bounds.top, b.bounds.top)
        val gapX = -overlapX
        val gapY = -overlapY

        val sameRow = overlapY >= min(a.bounds.height(), b.bounds.height()) * 0.40f
        if (sameRow) {
            if (gapX > (lineH * 0.42f).roundToInt() || ratio > 1.45f) return false
            if (bitmap != null && gapX > 2 && hasVisualSeparator(bitmap, a.bounds, b.bounds, verticalGap = false)) {
                return false
            }
            return true
        }

        // Stacked lines in the same speech bubble / paragraph
        val minW = min(a.bounds.width(), b.bounds.width())
        val canStack = overlapX >= (minW * 0.25f).roundToInt() &&
            gapY <= (lineH * 0.55f).roundToInt() &&
            ratio <= 1.55f
        if (!canStack) return false

        if (bitmap != null && gapY > 2 && hasVisualSeparator(bitmap, a.bounds, b.bounds, verticalGap = true)) {
            return false
        }
        return true
    }

    /**
     * Checks if there is a button border, speech bubble outline, or contrasting background strip
     * between two adjacent OCR boxes so two separate buttons/bubbles are never merged into one.
     */
    private fun hasVisualSeparator(
        bitmap: Bitmap,
        a: Rect,
        b: Rect,
        verticalGap: Boolean
    ): Boolean {
        return try {
            if (verticalGap) {
                val topRect = if (a.bottom <= b.top) a else b
                val botRect = if (a.bottom <= b.top) b else a
                val y1 = (topRect.bottom + 1).coerceIn(0, bitmap.height - 1)
                val y2 = (botRect.top - 1).coerceIn(0, bitmap.height - 1)
                if (y2 - y1 < 3) return false

                val xLeft = max(topRect.left, botRect.left)
                val xRight = min(topRect.right, botRect.right)
                if (xRight <= xLeft) return false

                val sampleX = ((xLeft + xRight) * 0.5f).roundToInt().coerceIn(0, bitmap.width - 1)
                val refColor = bitmap.getPixel(sampleX, y1)
                val refLum = lum(refColor)
                var maxDiff = 0.0
                for (y in y1..y2 step max(1, (y2 - y1) / 8)) {
                    val d = abs(lum(bitmap.getPixel(sampleX, y)) - refLum)
                    if (d > maxDiff) maxDiff = d
                }
                maxDiff > 32.0
            } else {
                val leftRect = if (a.right <= b.left) a else b
                val rightRect = if (a.right <= b.left) b else a
                val x1 = (leftRect.right + 1).coerceIn(0, bitmap.width - 1)
                val x2 = (rightRect.left - 1).coerceIn(0, bitmap.width - 1)
                if (x2 - x1 < 3) return false

                val yTop = max(leftRect.top, rightRect.top)
                val yBot = min(leftRect.bottom, rightRect.bottom)
                if (yBot <= yTop) return false

                val sampleY = ((yTop + yBot) * 0.5f).roundToInt().coerceIn(0, bitmap.height - 1)
                val refLum = lum(bitmap.getPixel(x1, sampleY))
                var maxDiff = 0.0
                for (x in x1..x2 step max(1, (x2 - x1) / 8)) {
                    val d = abs(lum(bitmap.getPixel(x, sampleY)) - refLum)
                    if (d > maxDiff) maxDiff = d
                }
                maxDiff > 32.0
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun lum(c: Int): Double =
        0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)

    private fun resolveOverlaps(rects: List<Rect>) {
        for (i in rects.indices) {
            for (j in i + 1 until rects.size) {
                val r1 = rects[i]
                val r2 = rects[j]
                if (!Rect.intersects(r1, r2)) continue

                val overlapX = min(r1.right, r2.right) - max(r1.left, r2.left)
                val overlapY = min(r1.bottom, r2.bottom) - max(r1.top, r2.top)
                if (overlapX <= 0 || overlapY <= 0) continue

                if (overlapY <= overlapX) {
                    val midY = if (r1.centerY() <= r2.centerY()) {
                        (r1.bottom + r2.top) / 2
                    } else {
                        (r2.bottom + r1.top) / 2
                    }
                    if (r1.centerY() <= r2.centerY()) {
                        if (midY - r1.top >= 18 && r2.bottom - midY >= 18) {
                            r1.bottom = midY - 1
                            r2.top = midY + 1
                        }
                    } else {
                        if (midY - r2.top >= 18 && r1.bottom - midY >= 18) {
                            r2.bottom = midY - 1
                            r1.top = midY + 1
                        }
                    }
                } else {
                    val midX = if (r1.centerX() <= r2.centerX()) {
                        (r1.right + r2.left) / 2
                    } else {
                        (r2.right + r1.left) / 2
                    }
                    if (r1.centerX() <= r2.centerX()) {
                        if (midX - r1.left >= 24 && r2.right - midX >= 24) {
                            r1.right = midX - 1
                            r2.left = midX + 1
                        }
                    } else {
                        if (midX - r2.left >= 24 && r1.right - midX >= 24) {
                            r2.right = midX - 1
                            r1.left = midX + 1
                        }
                    }
                }
            }
        }
    }

    /**
     * Crops the exact bubble region and creates a heavily blurred low-res patch
     * so original comic text underneath is completely obscured with a smooth frosted-blur look.
     */
    fun createBlurredPatch(source: Bitmap, rect: Rect): Bitmap? {
        val x = rect.left.coerceIn(0, source.width - 1)
        val y = rect.top.coerceIn(0, source.height - 1)
        val w = (rect.right.coerceAtMost(source.width) - x).coerceAtLeast(1)
        val h = (rect.bottom.coerceAtMost(source.height) - y).coerceAtLeast(1)
        if (w < 8 || h < 8) return null

        return try {
            val crop = Bitmap.createBitmap(source, x, y, w, h)
            val smallW = (w / 8).coerceIn(6, 48)
            val smallH = (h / 8).coerceIn(6, 48)
            val small = Bitmap.createScaledBitmap(crop, smallW, smallH, true)
            if (crop !== small) crop.recycle()

            val pixels = IntArray(smallW * smallH)
            small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH)
            val blurred = boxBlur3x3(pixels, smallW, smallH)
            small.setPixels(blurred, 0, smallW, 0, 0, smallW, smallH)
            small
        } catch (_: Exception) {
            null
        }
    }

    private fun boxBlur3x3(src: IntArray, w: Int, h: Int): IntArray {
        val dst = IntArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                for (dy in -1..1) {
                    val ny = (y + dy).coerceIn(0, h - 1)
                    val rowOff = ny * w
                    for (dx in -1..1) {
                        val nx = (x + dx).coerceIn(0, w - 1)
                        val c = src[rowOff + nx]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                        count++
                    }
                }
                dst[y * w + x] = Color.rgb(r / count, g / count, b / count)
            }
        }
        return dst
    }

    private fun joinClusterText(cluster: List<OcrLine>, verticalMode: Boolean): String {
        if (verticalMode) {
            // Japanese manga vertical columns: right-to-left, top-to-bottom
            val ordered = cluster.sortedWith(
                compareBy<OcrLine> { -(it.bounds.centerX() / 22) }.thenBy { it.bounds.top }
            )
            return ordered.joinToString("") { it.text.trim() }
        }

        val avgH = max(12, cluster.sumOf { it.bounds.height() } / cluster.size)
        val rowBucket = max(8, (avgH * 0.6f).roundToInt())
        val ordered = cluster.sortedWith(
            compareBy<OcrLine> { it.bounds.centerY() / rowBucket }.thenBy { it.bounds.left }
        )

        val sb = StringBuilder()
        for (line in ordered) {
            val t = line.text.trim()
            if (t.isEmpty()) continue
            if (sb.isEmpty()) {
                sb.append(t)
            } else {
                val lastChar = sb[sb.length - 1]
                val firstChar = t[0]
                if (lastChar == '-' && sb.length > 1 && sb[sb.length - 2].code < 128) {
                    sb.deleteCharAt(sb.length - 1)
                    sb.append(t)
                } else if (lastChar.code > 0x2E80 && firstChar.code > 0x2E80) {
                    sb.append(t)
                } else {
                    sb.append(' ').append(t)
                }
            }
        }
        return sb.toString().trim()
    }

    fun sampleBubbleColors(bitmap: Bitmap, rect: Rect): Triple<Int, Int, Boolean> {
        val pad = 4
        val x1 = (rect.left - pad).coerceIn(0, bitmap.width - 1)
        val y1 = (rect.top - pad).coerceIn(0, bitmap.height - 1)
        val x2 = (rect.right + pad).coerceIn(x1 + 1, bitmap.width)
        val y2 = (rect.bottom + pad).coerceIn(y1 + 1, bitmap.height)

        val rs = ArrayList<Int>(64)
        val gs = ArrayList<Int>(64)
        val bs = ArrayList<Int>(64)

        val stepX = max(1, (x2 - x1) / 14)
        val stepY = max(1, (y2 - y1) / 14)

        fun addPixel(px: Int, py: Int) {
            val c = bitmap.getPixel(px.coerceIn(0, bitmap.width - 1), py.coerceIn(0, bitmap.height - 1))
            rs.add(Color.red(c))
            gs.add(Color.green(c))
            bs.add(Color.blue(c))
        }

        var x = x1
        while (x < x2) {
            addPixel(x, y1)
            addPixel(x, y2 - 1)
            x += stepX
        }
        var y = y1
        while (y < y2) {
            addPixel(x1, y)
            addPixel(x2 - 1, y)
            y += stepY
        }

        if (rs.isEmpty()) return Triple(Color.WHITE, Color.rgb(20, 20, 24), false)

        rs.sort()
        gs.sort()
        bs.sort()
        val mid = rs.size / 2
        var bgR = rs[mid]
        var bgG = gs[mid]
        var bgB = bs[mid]

        val std = (channelStd(rs) + channelStd(gs) + channelStd(bs)) / 3.0
        val isComplex = std > 44.0

        if (!isComplex && bgR > 234 && bgG > 234 && bgB > 234) {
            bgR = 255; bgG = 255; bgB = 255
        } else if (!isComplex && bgR < 30 && bgG < 30 && bgB < 30) {
            bgR = 18; bgG = 18; bgB = 22
        }

        val luminance = 0.299 * bgR + 0.587 * bgG + 0.114 * bgB
        val textColor = if (luminance >= 135.0) Color.rgb(18, 18, 22) else Color.rgb(248, 248, 252)
        return Triple(Color.rgb(bgR, bgG, bgB), textColor, isComplex)
    }

    private fun channelStd(values: List<Int>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        var sumSq = 0.0
        for (v in values) {
            val d = v - mean
            sumSq += d * d
        }
        return sqrt(sumSq / values.size)
    }
}
