package com.appdichtruyen.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Draggable floating mini bubble on screen edge for 1-tap Pause/Resume,
 * Translate Now, Show/Hide overlay, and Stop.
 */
class FloatingDockController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onToggleAuto: (Boolean) -> Unit,
    private val onTranslateNow: () -> Unit,
    private val onToggleVisible: (Boolean) -> Unit,
    private val onStopService: () -> Unit
) {
    private var rootView: LinearLayout? = null
    private var menuContainer: LinearLayout? = null
    private var toggleBtn: TextView? = null
    private var eyeBtn: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var autoRunning = true
    private var overlayVisible = true
    private var expanded = false

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (rootView != null) return

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.argb(232, 17, 24, 39))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.rgb(56, 189, 248))
            }
        }

        val mainBadge = makePillButton("DỊCH", Color.rgb(22, 163, 74))
        toggleBtn = mainBadge

        val menu = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }

        val nowBtn = makePillButton("Quét", Color.rgb(37, 99, 235)).apply {
            setOnClickListener { onTranslateNow() }
        }
        val eye = makePillButton("Ẩn", Color.rgb(51, 65, 85)).apply {
            setOnClickListener {
                overlayVisible = !overlayVisible
                text = if (overlayVisible) "Ẩn" else "Hiện"
                onToggleVisible(overlayVisible)
            }
        }
        eyeBtn = eye

        val pauseBtn = makePillButton("Dừng", Color.rgb(217, 119, 6)).apply {
            setOnClickListener {
                autoRunning = !autoRunning
                text = if (autoRunning) "Dừng" else "Chạy"
                updateBadgeColor()
                onToggleAuto(autoRunning)
            }
        }

        val closeBtn = makePillButton("✕", Color.rgb(185, 28, 28)).apply {
            setOnClickListener { onStopService() }
        }

        menu.addView(nowBtn)
        menu.addView(pauseBtn)
        menu.addView(eye)
        menu.addView(closeBtn)
        menuContainer = menu

        container.addView(mainBadge)
        container.addView(menu)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(160)
        }

        // Dragging + tap to expand/collapse
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        mainBadge.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        moved = true
                        params.x = startX + dx
                        params.y = startY + dy
                        runCatching { windowManager.updateViewLayout(container, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        expanded = !expanded
                        menu.visibility = if (expanded) View.VISIBLE else View.GONE
                    }
                    true
                }
                else -> false
            }
        }

        rootView = container
        layoutParams = params
        windowManager.addView(container, params)
    }

    fun getScreenBounds(): Rect {
        val v = rootView ?: return Rect()
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    fun setVisibleForCapture(visible: Boolean) {
        rootView?.post {
            rootView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
    }

    fun destroy() {
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        menuContainer = null
    }

    private fun updateBadgeColor() {
        val bg = toggleBtn?.background as? GradientDrawable ?: return
        bg.setColor(if (autoRunning) Color.rgb(22, 163, 74) else Color.rgb(100, 116, 139))
    }

    private fun makePillButton(label: String, bgColor: Int): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dp(18).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(4)
            }
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
}
