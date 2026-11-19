package com.appdichtruyen.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.appdichtruyen.MainActivity
import com.appdichtruyen.R
import com.appdichtruyen.config.AppPreferences
import com.appdichtruyen.engine.BubbleGrouper
import com.appdichtruyen.engine.OcrProcessor
import com.appdichtruyen.engine.ScrollDeltaTracker
import com.appdichtruyen.engine.TranslationRepository
import com.appdichtruyen.overlay.BubbleOverlayView
import com.appdichtruyen.overlay.FloatingDockController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Foreground Service managing MediaProjection screen capture, real-time scroll tracking,
 * offline ML Kit OCR, hybrid Online/Offline translation, and transparent bubble overlay.
 */
class ScreenTranslateService : Service() {

    private lateinit var prefs: AppPreferences
    private lateinit var windowManager: WindowManager
    private lateinit var ocrProcessor: OcrProcessor
    private lateinit var translationRepo: TranslationRepository
    private val scrollTracker = ScrollDeltaTracker()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var overlayView: BubbleOverlayView? = null
    private var dockController: FloatingDockController? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val autoRunning = AtomicBoolean(true)
    private val forceOcr = AtomicBoolean(true)
    private val isOcrBusy = AtomicBoolean(false)

    private var screenW = 1080
    private var screenH = 2400
    private var screenDpi = 420

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ocrProcessor = OcrProcessor()
        translationRepo = TranslationRepository(prefs)
        resolveDisplayMetrics()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        setupOverlays()
        setupMediaProjection(resultCode, resultData)
        startTranslationLoop()
        return START_NOT_STICKY
    }

    private fun resolveDisplayMetrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenW = bounds.width().coerceAtLeast(360)
            screenH = bounds.height().coerceAtLeast(640)
            screenDpi = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels.coerceAtLeast(360)
            screenH = dm.heightPixels.coerceAtLeast(640)
            screenDpi = dm.densityDpi
        }
    }

    private fun startForegroundCompat() {
        val channelId = "app_dich_truyen_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(
                channelId,
                "App Dịch Truyện",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenTranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_comic)
            .setContentTitle("App Dịch Truyện đang chạy")
            .setContentText("Lướt truyện để tự động dịch và gắn chữ lên khung thoại")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Tắt dịch", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupOverlays() {
        if (overlayView == null) {
            val view = BubbleOverlayView(this)
            view.setOverlayAlpha(prefs.overlayAlpha)
            val params = WindowManager.LayoutParams(
                screenW,
                screenH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            windowManager.addView(view, params)
            overlayView = view
        }

        if (dockController == null) {
            dockController = FloatingDockController(
                context = this,
                windowManager = windowManager,
                onToggleAuto = { running ->
                    autoRunning.set(running)
                    if (running) {
                        scrollTracker.reset()
                        forceOcr.set(true)
                    }
                },
                onTranslateNow = {
                    scrollTracker.reset()
                    forceOcr.set(true)
                },
                onToggleVisible = { visible ->
                    overlayView?.setOverlayEnabled(visible)
                },
                onStopService = {
                    stopSelf()
                }
            ).also { it.show() }
        }
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, data) ?: return
        mediaProjection = mp

        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, mainHandler)

        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mp.createVirtualDisplay(
            "AppDichTruyenDisplay",
            screenW,
            screenH,
            screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun grabLatestBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenW

            val paddedBitmap = Bitmap.createBitmap(
                screenW + rowPadding / pixelStride,
                screenH,
                Bitmap.Config.ARGB_8888
            )
            paddedBitmap.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) {
                paddedBitmap
            } else {
                val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, screenW, screenH)
                paddedBitmap.recycle()
                cropped
            }
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun startTranslationLoop() {
        loopJob?.cancel()
        loopJob = serviceScope.launch {
            var lastChangeTime = System.currentTimeMillis()
            var pendingOcr = true
            var accumulatedScroll = 0

            while (isActive) {
                if (!autoRunning.get() && !forceOcr.get()) {
                    delay(120)
                    continue
                }

                if (forceOcr.compareAndSet(true, false)) {
                    runCleanOcrAndTranslate()
                    pendingOcr = false
                    accumulatedScroll = 0
                    delay(120)
                    continue
                }

                val frame = grabLatestBitmap()
                if (frame != null) {
                    val delta = scrollTracker.analyze(frame)
                    frame.recycle()

                    val now = System.currentTimeMillis()
                    if (delta.changed) {
                        lastChangeTime = now
                        pendingOcr = true
                        if (abs(delta.dyPixels) >= 3 && delta.confidence >= 0.65f) {
                            accumulatedScroll += abs(delta.dyPixels)
                            overlayView?.shiftBubbles(delta.dyPixels)
                        } else if (delta.confidence < 0.40f && delta.diffScore > 22f) {
                            // Page switched abruptly -> clear stale bubbles immediately
                            overlayView?.updateBubbles(emptyList())
                            accumulatedScroll = screenH
                        }
                    }

                    val settled = pendingOcr && (now - lastChangeTime >= prefs.scrollDebounceMs)
                    val scrolledFar = pendingOcr && (accumulatedScroll >= (screenH * 0.35f).toInt())

                    if ((settled || scrolledFar) && !isOcrBusy.get()) {
                        pendingOcr = false
                        accumulatedScroll = 0
                        runCleanOcrAndTranslate()
                    }
                }

                delay(90)
            }
        }
    }

    /**
     * Briefly hides our own overlay for ~45ms so MediaProjection captures the raw
     * comic page underneath without reading our own translated Vietnamese text.
     */
    private suspend fun runCleanOcrAndTranslate() {
        if (!isOcrBusy.compareAndSet(false, true)) return
        try {
            val dockBounds = dockController?.getScreenBounds() ?: Rect()
            withContext(Dispatchers.Main) {
                overlayView?.visibility = View.INVISIBLE
                dockController?.setVisibleForCapture(false)
            }
            delay(85)

            val cleanBitmap = grabLatestBitmap()

            withContext(Dispatchers.Main) {
                overlayView?.visibility = View.VISIBLE
                dockController?.setVisibleForCapture(true)
            }

            if (cleanBitmap == null) return
            try {
                scrollTracker.reset()
                scrollTracker.analyze(cleanBitmap)

                val lang = prefs.sourceLanguage
                val rawLines = ocrProcessor.detectLines(cleanBitmap, lang)
                // Ignore any text overlapping the floating dock area
                val filteredLines = rawLines.filter { !Rect.intersects(it.bounds, dockBounds) }
                val bubbles = BubbleGrouper.groupLines(
                    lines = filteredLines,
                    bitmap = cleanBitmap,
                    language = lang,
                    screenWidth = screenW,
                    screenHeight = screenH
                )
                if (bubbles.isNotEmpty()) {
                    translationRepo.translateBubbles(bubbles)
                }
                overlayView?.updateBubbles(bubbles, cleanBitmap.width, cleanBitmap.height)
            } finally {
                cleanBitmap.recycle()
            }
        } catch (_: Exception) {
            withContext(Dispatchers.Main) {
                overlayView?.visibility = View.VISIBLE
                dockController?.setVisibleForCapture(true)
            }
        } finally {
            isOcrBusy.set(false)
        }
    }

    override fun onDestroy() {
        loopJob?.cancel()
        serviceScope.cancel()
        dockController?.destroy()
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        ocrProcessor.close()
        translationRepo.close()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.appdichtruyen.ACTION_START"
        const val ACTION_STOP = "com.appdichtruyen.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val NOTIFICATION_ID = 1001
    }
}
