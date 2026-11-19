package com.appdichtruyen.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import com.appdichtruyen.model.SpeechBubble
import kotlin.math.max
import kotlin.math.min

/**
 * Click-through transparent overlay View that obscures original text with a frosted
 * blurred patch inside each exact bubble bounds, then renders fitted Vietnamese text on top
 * without ever overflowing outside the bubble.
 */
class BubbleOverlayView(context: Context) : View(context) {

    private val blurBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val clipPath = Path()
    private val rectF = RectF()
    private val screenLoc = IntArray(2)

    private var bubbles: List<SpeechBubble> = emptyList()
    private var sourceW: Int = 0
    private var sourceH: Int = 0
    private var overlayAlpha: Int = 245
    private var overlayEnabled: Boolean = true
    private val maxCornerRadius = dp(8f)
    private val minSp = 7f
    private val maxSp = 20f

    fun setOverlayAlpha(alpha: Int) {
        overlayAlpha = alpha.coerceIn(140, 255)
        postInvalidate()
    }

    fun setOverlayEnabled(enabled: Boolean) {
        overlayEnabled = enabled
        postInvalidate()
    }

    fun updateBubbles(newBubbles: List<SpeechBubble>, captureWidth: Int = 0, captureHeight: Int = 0) {
        bubbles = newBubbles
        if (captureWidth > 0) sourceW = captureWidth
        if (captureHeight > 0) sourceH = captureHeight
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
        if (!overlayEnabled || bubbles.isEmpty() || width <= 0 || height <= 0) return

        // Compensate for any status bar / display cutout offset applied by WindowManager
        getLocationOnScreen(screenLoc)
        val offsetX = screenLoc[0].toFloat()
        val offsetY = screenLoc[1].toFloat()

        // Scale from captured bitmap coordinates to screen coordinates if they differ
        val sx = if (sourceW > 0 && sourceW != width) width.toFloat() / sourceW.toFloat() else 1f
        val sy = if (sourceH > 0 && sourceH != height) height.toFloat() / sourceH.toFloat() else 1f

        for (bubble in bubbles) {
            val text = bubble.translatedText.trim()
            if (text.isEmpty()) continue

            val b = bubble.bounds
            val left = (b.left * sx - offsetX).coerceIn(0f, width.toFloat())
            val top = (b.top * sy - offsetY).coerceIn(0f, height.toFloat())
            val right = (b.right * sx - offsetX).coerceIn(0f, width.toFloat())
            val bottom = (b.bottom * sy - offsetY).coerceIn(0f, height.toFloat())
            val boxW = right - left
            val boxH = bottom - top
            if (boxW < dp(16f) || boxH < dp(12f)) continue

            rectF.set(left, top, right, bottom)
            val radius = min(maxCornerRadius, min(boxW, boxH) * 0.24f)

            clipPath.reset()
            clipPath.addRoundRect(rectF, radius, radius, Path.Direction.CW)

            canvas.save()
            canvas.clipPath(clipPath)

            // 1. Draw frosted blurred patch of original region to obscure original text smoothly
            val patch = bubble.blurPatch
            val hasPatch = patch != null && !patch.isRecycled
            if (hasPatch) {
                blurBitmapPaint.alpha = 255
                canvas.drawBitmap(patch!!, null, rectF, blurBitmapPaint)
            }

            // 2. Draw semi-transparent frosted tint layer on top of blur to guarantee readability
            bgPaint.color = bubble.bgColor
            bgPaint.alpha = if (hasPatch) {
                val base = if (bubble.isComplexBg) (overlayAlpha * 0.72f) else (overlayAlpha * 0.78f)
                base.toInt().coerceIn(135, 220)
            } else {
                if (bubble.isComplexBg) min(225, overlayAlpha) else overlayAlpha
            }
            canvas.drawRoundRect(rectF, radius, radius, bgPaint)

            // 3. Soft subtle border around the frosted bubble
            borderPaint.color = bubble.textColor
            borderPaint.alpha = if (bubble.isComplexBg) 85 else 45
            canvas.drawRoundRect(rectF, radius, radius, borderPaint)

            // 4. Auto-fit translated text strictly inside the bubble bounds
            val innerPadX = min(dp(4f), boxW * 0.08f)
            val innerPadY = min(dp(2f), boxH * 0.08f)
            val availW = max(8, (boxW - innerPadX * 2).toInt())
            val availH = max(8, (boxH - innerPadY * 2).toInt())

            textPaint.color = bubble.textColor
            if (bubble.isComplexBg) {
                val shadowColor = if (Color.luminance(bubble.textColor) > 0.5f) {
                    Color.argb(150, 0, 0, 0)
                } else {
                    Color.argb(130, 255, 255, 255)
                }
                textPaint.setShadowLayer(dp(1.5f), 0f, dp(0.5f), shadowColor)
            } else {
                textPaint.clearShadowLayer()
            }

            val layout = fitTextLayout(text, availW, availH)
            val drawX = left + innerPadX
            val drawY = top + max(innerPadY, (boxH - layout.height) * 0.5f)

            canvas.translate(drawX, drawY)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun fitTextLayout(text: String, maxW: Int, maxH: Int): StaticLayout {
        textPaint.textScaleX = 1.0f
        var lo = sp(minSp)
        var hi = sp(maxSp)
        var best = lo

        repeat(8) {
            val mid = (lo + hi) * 0.5f
            textPaint.textSize = mid
            val candidate = buildLayout(text, maxW, lineMult = 1.0f, maxLines = Int.MAX_VALUE)
            if (candidate.height <= maxH) {
                best = mid
                lo = mid
            } else {
                hi = mid
            }
        }

        textPaint.textSize = best
        var result = buildLayout(text, maxW, lineMult = 1.0f, maxLines = Int.MAX_VALUE)
        if (result.height <= maxH) return result

        // Tighter line spacing + slight horizontal condense before truncating
        textPaint.textScaleX = 0.90f
        result = buildLayout(text, maxW, lineMult = 0.90f, maxLines = Int.MAX_VALUE)
        if (result.height <= maxH) return result

        val lineH = max(1, result.getLineBottom(0) - result.getLineTop(0))
        val allowedLines = max(1, maxH / lineH)
        return buildLayout(text, maxW, lineMult = 0.90f, maxLines = allowedLines)
    }

    private fun buildLayout(
        text: String,
        widthPx: Int,
        lineMult: Float,
        maxLines: Int
    ): StaticLayout {
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, lineMult)
        if (maxLines < Int.MAX_VALUE) {
            builder.setMaxLines(maxLines)
            builder.setEllipsize(TextUtils.TruncateAt.END)
        }
        return builder.build()
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}
