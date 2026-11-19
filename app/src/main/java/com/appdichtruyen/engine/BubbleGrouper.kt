package com.appdichtruyen.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.appdichtruyen.model.OcrLine
import com.appdichtruyen.model.SourceLanguage
import com.appdichtruyen.model.SpeechBubble
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object BubbleGrouper {

    private val noiseRegex = Regex("^[\\s\\p{Punct}0-9_]+$")

    fun isMeaningfulText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.length == 1 && trimmed[0].code < 128) return false
        if (noiseRegex.matches(trimmed)) return false
        return true
    }

    fun groupLines(
        lines: List<OcrLine>,
        bitmap: Bitmap?,
        language: SourceLanguage
    ): List<SpeechBubble> {
        val valid = lines.filter {
            it.bounds.width() >= 10 && it.bounds.height() >= 10 && isMeaningfulText(it.text)
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

        val preferVertical = (language == SourceLanguage.JAPANESE)
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val verticalPair = preferVertical && (valid[i].isVertical || valid[j].isVertical)
                if (shouldMerge(valid[i], valid[j], verticalPair)) {
                    union(i, j)
                }
            }
        }

        val clusters = LinkedHashMap<Int, MutableList<OcrLine>>()
        for (i in 0 until n) {
            clusters.getOrPut(find(i)) { mutableListOf() }.add(valid[i])
        }

        val bubbles = mutableListOf<SpeechBubble>()
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

            val (bgColor, textColor, isComplex) = if (bitmap != null) {
                sampleBubbleColors(bitmap, rect)
            } else {
                Triple(Color.WHITE, Color.rgb(20, 20, 24), false)
            }

            val id = "${mergedText.hashCode()}_${left / 24}_${top / 24}"
            bubbles.add(
                SpeechBubble(
                    id = id,
                    sourceText = mergedText,
                    bounds = rect,
                    bgColor = bgColor,
                    textColor = textColor,
                    isComplexBg = isComplex
                )
            )
        }

        return bubbles.sortedWith(compareBy({ it.bounds.top / 48 }, { it.bounds.left }))
    }

    private fun shouldMerge(a: OcrLine, b: OcrLine, verticalMode: Boolean): Boolean {
        val padX: Int
        val padY: Int
        if (verticalMode) {
            val charW = max(14, min(a.bounds.width(), b.bounds.width()))
            padX = (charW * 0.95f).roundToInt()
            padY = (charW * 0.55f).roundToInt()
        } else {
            val lineH = max(14, min(a.bounds.height(), b.bounds.height()))
            padX = (lineH * 0.75f).roundToInt()
            padY = (lineH * 0.65f).roundToInt()
        }

        val expanded = Rect(
            a.bounds.left - padX,
            a.bounds.top - padY,
            a.bounds.right + padX,
            a.bounds.bottom + padY
        )
        if (!Rect.intersects(expanded, b.bounds)) return false

        val sizeA = if (a.isVertical) a.bounds.width() else a.bounds.height()
        val sizeB = if (b.isVertical) b.bounds.width() else b.bounds.height()
        val ratio = max(sizeA, sizeB).toFloat() / max(1, min(sizeA, sizeB)).toFloat()
        return ratio <= 2.4f
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
