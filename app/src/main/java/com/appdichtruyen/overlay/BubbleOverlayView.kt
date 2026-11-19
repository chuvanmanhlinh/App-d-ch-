package com.appdichtruyen.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import com.appdichtruyen.model.SpeechBubble
import kotlin.math.max
import kotlin.math.min

/**
 * Click-through transparent overlay View that paints translated Vietnamese text
 * directly onto each detected speech bubble with adaptive background color and auto-fit sizing.
 */
class BubbleOverlayView(context: Context) : View(context) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private var bubbles: List<SpeechBubble> = emptyList()
    private var overlayAlpha: Int = 245
    private var overlayEnabled: Boolean = true
    private val cornerRadius = dp(8f)
    private val minSp = 9f
    private val maxSp = 22f

    fun setOverlayAlpha(alpha: Int) {
        overlayAlpha = alpha.coerceIn(140, 255)
        postInvalidate()
    }

    fun setOverlayEnabled(enabled: Boolean) {
        overlayEnabled = enabled
        postInvalidate()
    }

    fun updateBubbles(newBubbles: List<SpeechBubble>) {
        bubbles = newBubbles
        postInvalidate()
    }

    fun shiftBubbles(dy: Int) {
        if (bubbles.isEmpty() || dy == 0) return
        val h = height.takeIf { it > 0 } ?: 2400
        bubbles = bubbles.map { it.shifted(dy) }.filter {
            it.bounds.bottom > -40 && it.bounds.top < h + 40
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!overlayEnabled || bubbles.isEmpty()) return

        val pad = dp(5f)
        for (bubble in bubbles) {
            val text = bubble.translatedText.trim()
            if (text.isEmpty()) continue

            val b = bubble.bounds
            val expandX = max(pad, b.width() * 0.08f)
            val expandY = max(pad, b.height() * 0.08f)

            val left = max(4f, b.left - expandX)
            val top = max(4f, b.top - expandY)
            val right = min(width - 4f, b.right + expandX)
            val bottom = min(height - 4f, b.bottom + expandY)
            val boxW = right - left
            val boxH = bottom - top
            if (boxW < dp(24f) || boxH < dp(16f)) continue

            val rectF = RectF(left, top, right, bottom)
            bgPaint.color = bubble.bgColor
            bgPaint.alpha = if (bubble.isComplexBg) min(230, overlayAlpha) else overlayAlpha
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, bgPaint)

            if (bubble.isComplexBg) {
                borderPaint.color = bubble.textColor
                borderPaint.alpha = 70
                canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, borderPaint)
            }

            val innerPadX = dp(4f)
            val innerPadY = dp(2f)
            val availW = max(16, (boxW - innerPadX * 2).toInt())
            val availH = max(12, (boxH - innerPadY * 2).toInt())

            textPaint.color = bubble.textColor
            val layout = fitTextLayout(text, availW, availH)

            val drawX = left + innerPadX
            val drawY = top + (boxH - layout.height) / 2f

            canvas.save()
            canvas.clipRect(rectF)
            canvas.translate(drawX, max(top + innerPadY, drawY))
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun fitTextLayout(text: String, maxW: Int, maxH: Int): StaticLayout {
        var lo = sp(minSp)
        var hi = sp(maxSp)
        var best = lo

        repeat(7) {
            val mid = (lo + hi) * 0.5f
            textPaint.textSize = mid
            val candidate = buildLayout(text, maxW)
            if (candidate.height <= maxH) {
                best = mid
                lo = mid
            } else {
                hi = mid
            }
        }
        textPaint.textSize = best
        return buildLayout(text, maxW)
    }

    private fun buildLayout(text: String, widthPx: Int): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.05f)
            .build()
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}
