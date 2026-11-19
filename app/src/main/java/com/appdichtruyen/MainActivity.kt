package com.appdichtruyen

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.appdichtruyen.config.AppPreferences
import com.appdichtruyen.engine.TranslationRepository
import com.appdichtruyen.model.SourceLanguage
import com.appdichtruyen.model.TranslateMode
import com.appdichtruyen.service.ScreenTranslateService
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var translationRepo: TranslationRepository
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var langSpinner: Spinner
    private lateinit var modeSpinner: Spinner
    private lateinit var geminiKeyInput: EditText
    private lateinit var fallbackSwitch: SwitchMaterial
    private lateinit var statusText: TextView
    private lateinit var downloadOfflineBtn: Button
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* optional notification permission on Android 13+ */ }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val svcIntent = Intent(this, ScreenTranslateService::class.java).apply {
                action = ScreenTranslateService.ACTION_START
                putExtra(ScreenTranslateService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenTranslateService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, svcIntent)
            statusText.text = "Đang chạy! Hãy mở app đọc truyện và lướt thôi."
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "Cần cấp quyền ghi màn hình để quét chữ truyện.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = AppPreferences(this)
        translationRepo = TranslationRepository(prefs)

        langSpinner = findViewById(R.id.spinnerLanguage)
        modeSpinner = findViewById(R.id.spinnerMode)
        geminiKeyInput = findViewById(R.id.editGeminiKey)
        fallbackSwitch = findViewById(R.id.switchFallbackOffline)
        statusText = findViewById(R.id.textStatus)
        downloadOfflineBtn = findViewById(R.id.btnDownloadOffline)
        startBtn = findViewById(R.id.btnStartOverlay)
        stopBtn = findViewById(R.id.btnStopOverlay)

        setupControls()
        requestNotificationPermissionIfNeeded()
    }

    private fun setupControls() {
        val langs = SourceLanguage.entries
        langSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            langs.map { it.label }
        )
        langSpinner.setSelection(langs.indexOf(prefs.sourceLanguage).coerceAtLeast(0))

        val modes = TranslateMode.entries
        modeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            modes.map { it.label }
        )
        modeSpinner.setSelection(modes.indexOf(prefs.translateMode).coerceAtLeast(0))

        geminiKeyInput.setText(prefs.geminiApiKey)
        fallbackSwitch.isChecked = prefs.autoFallbackOffline

        downloadOfflineBtn.setOnClickListener {
            downloadOfflineBtn.isEnabled = false
            statusText.text = "Đang tải gói dịch Offline (Anh/Nhật/Hàn/Trung → Việt)..."
            uiScope.launch {
                runCatching {
                    translationRepo.downloadAllOfflineModels { progress ->
                        launch(Dispatchers.Main) { statusText.text = progress }
                    }
                }.onFailure { err ->
                    withContext(Dispatchers.Main) {
                        statusText.text = "Lỗi tải gói Offline: ${err.message}"
                    }
                }
                withContext(Dispatchers.Main) {
                    downloadOfflineBtn.isEnabled = true
                }
            }
        }

        startBtn.setOnClickListener {
            savePreferences()
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(
                    this,
                    "Hãy bật quyền 'Hiển thị trên ứng dụng khác' cho App Dịch Truyện",
                    Toast.LENGTH_LONG
                ).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return@setOnClickListener
            }

            if (prefs.translateMode == TranslateMode.ONLINE_GEMINI && prefs.geminiApiKey.isBlank()) {
                Toast.makeText(
                    this,
                    "Chưa nhập Gemini API Key — tự dùng Online Nhanh (Google)",
                    Toast.LENGTH_SHORT
                ).show()
            }

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }

        stopBtn.setOnClickListener {
            val stopIntent = Intent(this, ScreenTranslateService::class.java).setAction(
                ScreenTranslateService.ACTION_STOP
            )
            startService(stopIntent)
            statusText.text = "Đã tắt dịch màn hình."
        }
    }

    private fun savePreferences() {
        prefs.sourceLanguage = SourceLanguage.entries[langSpinner.selectedItemPosition]
        prefs.translateMode = TranslateMode.entries[modeSpinner.selectedItemPosition]
        prefs.geminiApiKey = geminiKeyInput.text.toString().trim()
        prefs.autoFallbackOffline = fallbackSwitch.isChecked
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        translationRepo.close()
        super.onDestroy()
    }
}
