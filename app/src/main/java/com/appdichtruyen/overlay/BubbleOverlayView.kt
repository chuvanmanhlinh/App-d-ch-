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
    private val minSp = 4.5f
    private val readableSp = 9.5f
    private val maxSp = 13.5f

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
            val rawLeft = (b.left * sx - offsetX).coerceIn(0f, width.toFloat())
            val rawTop = (b.top * sy - offsetY).coerceIn(0f, height.toFloat())
            val rawRight = (b.right * sx - offsetX).coerceIn(0f, width.toFloat())
            val rawBottom = (b.bottom * sy - offsetY).coerceIn(0f, height.toFloat())
            val rawW = rawRight - rawLeft
            val rawH = rawBottom - rawTop
            if (rawW < 10f || rawH < 8f) continue

            // Prepare text style before fitting so measurements match actual draw
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

            // Try original box first; expand up to 150% (1.5x) only if needed for readable text
            var chosenLeft = rawLeft
            var chosenTop = rawTop
            var chosenRight = rawRight
            var chosenBottom = rawBottom
            var chosenLayout: StaticLayout? = null
            var chosenPadX = 0f
            var chosenPadY = 0f

            val scales = floatArrayOf(1.0f, 1.18f, 1.34f, 1.50f)
            val centerX = (rawLeft + rawRight) * 0.5f
            val centerY = (rawTop + rawBottom) * 0.5f
            val targetSize = sp(readableSp)

            for (scale in scales) {
                val halfW = (rawW * scale) * 0.5f
                val halfH = (rawH * scale) * 0.5f
                val cLeft = (centerX - halfW).coerceIn(0f, width.toFloat())
                val cTop = (centerY - halfH).coerceIn(0f, height.toFloat())
                val cRight = (centerX + halfW).coerceIn(0f, width.toFloat())
                val cBottom = (centerY + halfH).coerceIn(0f, height.toFloat())
                val cW = cRight - cLeft
                val cH = cBottom - cTop

                val padX = min(dp(4f), cW * 0.06f)
                val padY = min(dp(2f), cH * 0.06f)
                val availW = max(8, (cW - padX * 2).toInt())
                val availH = max(8, (cH - padY * 2).toInt())

                val candidate = fitTextLayout(text, availW, availH)
                chosenLeft = cLeft
                chosenTop = cTop
                chosenRight = cRight
                chosenBottom = cBottom
                chosenPadX = padX
                chosenPadY = padY
                chosenLayout = candidate

                if (textPaint.textSize >= targetSize && candidate.height <= availH) {
                    break
                }
            }

            val boxW = chosenRight - chosenLeft
            val boxH = chosenBottom - chosenTop
            val layout = chosenLayout ?: continue

            rectF.set(chosenLeft, chosenTop, chosenRight, chosenBottom)
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

            // 4. Draw full translated text without ellipsis
            val drawX = chosenLeft + chosenPadX
            val drawY = chosenTop + max(chosenPadY, (boxH - layout.height) * 0.5f)

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

        repeat(9) {
            val mid = (lo + hi) * 0.5f
            textPaint.textSize = mid
            val candidate = buildLayout(text, maxW, lineMult = 0.96f)
            if (candidate.height <= maxH) {
                best = mid
                lo = mid
            } else {
                hi = mid
            }
        }

        textPaint.textSize = best
        var result = buildLayout(text, maxW, lineMult = 0.96f)
        if (result.height <= maxH) return result

        // Compress horizontally and tighten line spacing, never adding "..."
        textPaint.textScaleX = 0.88f
        result = buildLayout(text, maxW, lineMult = 0.88f)
        if (result.height <= maxH) return result

        // Final fallback: shrink textSize down as much as needed so full text fits without ellipsis
        var shrinkSp = minSp - 0.5f
        while (shrinkSp >= 3.0f) {
            textPaint.textSize = sp(shrinkSp)
            result = buildLayout(text, maxW, lineMult = 0.85f)
            if (result.height <= maxH) return result
            shrinkSp -= 0.5f
        }
        return result
    }

    private fun buildLayout(
        text: String,
        widthPx: Int,
        lineMult: Float
    ): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, lineMult)
            .build()
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}
