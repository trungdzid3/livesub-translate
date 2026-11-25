package com.livesub.translate.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.livesub.translate.R
import com.livesub.translate.databinding.ActivityMainBinding
import com.livesub.translate.engine.AppUpdater
import com.livesub.translate.engine.ModelManager
import com.livesub.translate.engine.UpdateInfo
import com.livesub.translate.model.AsrMode
import com.livesub.translate.model.FilmLanguage
import com.livesub.translate.service.LiveTranslateService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var modelManager: ModelManager
    private var selectedLanguage = FilmLanguage.ENGLISH
    private var selectedAsrMode = AsrMode.WHISPER_ACCURATE
    private var isDownloadingModel = false

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioGranted = results[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        if (audioGranted) {
            checkOverlayAndStartProjection()
        } else {
            Toast.makeText(this, R.string.msg_need_audio_permission, Toast.LENGTH_LONG).show()
        }
    }

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            requestMediaProjection()
        } else {
            Toast.makeText(this, R.string.msg_need_overlay_permission, Toast.LENGTH_LONG).show()
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, LiveTranslateService::class.java).apply {
                action = LiveTranslateService.ACTION_START
                putExtra(LiveTranslateService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(LiveTranslateService.EXTRA_RESULT_DATA, result.data)
                putExtra(LiveTranslateService.EXTRA_LANGUAGE, selectedLanguage.name)
                putExtra(LiveTranslateService.EXTRA_ENABLE_OVERLAY, binding.switchOverlay.isChecked)
                putExtra(LiveTranslateService.EXTRA_ENABLE_TTS, binding.switchTts.isChecked)
                putExtra(LiveTranslateService.EXTRA_SHOW_ORIGINAL, binding.switchShowOriginal.isChecked)
                putExtra(LiveTranslateService.EXTRA_ASR_MODE, selectedAsrMode.name)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            updateUiState(isRunning = true)
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LiveTranslateService.BROADCAST_STATUS) return
            val running = intent.getBooleanExtra(LiveTranslateService.EXTRA_IS_RUNNING, false)
            updateUiState(isRunning = running)

            val audioLevel = intent.getIntExtra(LiveTranslateService.EXTRA_AUDIO_LEVEL, -1)
            if (audioLevel >= 0) {
                binding.progressAudioLevel.setProgress(audioLevel, true)
            }

            val orig = intent.getStringExtra(LiveTranslateService.EXTRA_LAST_ORIGINAL)
            val trans = intent.getStringExtra(LiveTranslateService.EXTRA_LAST_TRANSLATED)
            val isFinal = intent.getBooleanExtra(LiveTranslateService.EXTRA_IS_FINAL, true)

            if (!trans.isNullOrBlank()) {
                val showOrig = binding.switchShowOriginal.isChecked && !orig.isNullOrBlank()
                binding.tvLiveOriginal.visibility = if (showOrig) View.VISIBLE else View.GONE
                if (showOrig) {
                    binding.tvLiveOriginal.text = orig
                    binding.tvLiveOriginal.alpha = if (isFinal) 1.0f else 0.75f
                    binding.tvLiveOriginal.setTypeface(null, if (isFinal) Typeface.NORMAL else Typeface.ITALIC)
                }
                binding.tvLiveTranslated.text = trans
                binding.tvLiveTranslated.alpha = if (isFinal) 1.0f else 0.85f
                binding.tvLiveTranslated.setTypeface(null, if (isFinal) Typeface.BOLD else Typeface.ITALIC)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyEdgeToEdgeInsets()

        modelManager = ModelManager(this)
        selectedAsrMode = AsrMode.WHISPER_ACCURATE

        setupLanguageSelector()
        setupControls()
        setupVersionAndUpdater()
        refreshModelStatus()
        updateUiState(LiveTranslateService.isRunning)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(LiveTranslateService.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
        updateUiState(LiveTranslateService.isRunning)
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(statusReceiver) }
    }

    private fun applyEdgeToEdgeInsets() {
        val scrollBasePaddingBottom = binding.scrollContent.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.appBarLayout.updatePadding(top = bars.top)
            binding.scrollContent.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = scrollBasePaddingBottom + bars.bottom
            )
            windowInsets
        }
    }

    private fun setupLanguageSelector() {
        binding.toggleGroupLang.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedLanguage = when (checkedId) {
                R.id.btnLangEn -> FilmLanguage.ENGLISH
                R.id.btnLangZh -> FilmLanguage.CHINESE
                R.id.btnLangJa -> FilmLanguage.JAPANESE
                R.id.btnLangRu -> FilmLanguage.RUSSIAN
                else -> FilmLanguage.ENGLISH
            }
            if (!isDownloadingModel) {
                refreshModelStatus()
            }
        }
    }

    private fun setupControls() {
        binding.btnDownloadModel.setOnClickListener {
            downloadSelectedLanguageModel()
        }

        binding.btnToggleService.setOnClickListener {
            if (LiveTranslateService.isRunning) {
                startService(Intent(this, LiveTranslateService::class.java).apply {
                    action = LiveTranslateService.ACTION_STOP
                })
                updateUiState(isRunning = false)
            } else {
                startLiveTranslateFlow()
            }
        }

        binding.btnOpenEdge.setOnClickListener {
            openEdgeCanary()
        }

        binding.rowOverlay.setOnClickListener { binding.switchOverlay.toggle() }
        binding.rowTts.setOnClickListener { binding.switchTts.toggle() }
        binding.rowShowOriginal.setOnClickListener { binding.switchShowOriginal.toggle() }
        binding.rowWhisperMode.setOnClickListener { binding.switchWhisperMode.toggle() }

        binding.switchWhisperMode.isChecked = true
        binding.switchWhisperMode.setOnCheckedChangeListener { _, isChecked ->
            selectedAsrMode = if (isChecked) {
                AsrMode.WHISPER_ACCURATE
            } else {
                AsrMode.STREAMING_FAST
            }
            if (!isDownloadingModel) {
                refreshModelStatus()
            }
        }

        val outputListener = { _: View, _: Boolean ->
            if (!binding.switchShowOriginal.isChecked) {
                binding.tvLiveOriginal.visibility = View.GONE
            }
            if (LiveTranslateService.isRunning) {
                startService(Intent(this, LiveTranslateService::class.java).apply {
                    action = LiveTranslateService.ACTION_UPDATE_OUTPUT
                    putExtra(LiveTranslateService.EXTRA_ENABLE_OVERLAY, binding.switchOverlay.isChecked)
                    putExtra(LiveTranslateService.EXTRA_ENABLE_TTS, binding.switchTts.isChecked)
                    putExtra(LiveTranslateService.EXTRA_SHOW_ORIGINAL, binding.switchShowOriginal.isChecked)
                })
            }
        }
        binding.switchOverlay.setOnCheckedChangeListener(outputListener)
        binding.switchTts.setOnCheckedChangeListener(outputListener)
        binding.switchShowOriginal.setOnCheckedChangeListener(outputListener)
    }

    private fun refreshModelStatus() {
        binding.progressModelCircle.visibility = View.GONE
        binding.progressDownload.visibility = View.GONE
        binding.tvDownloadPercent.visibility = View.GONE
        binding.ivModelState.visibility = View.VISIBLE

        val ready = if (selectedAsrMode == AsrMode.WHISPER_ACCURATE) {
            binding.tvModelTitle.text = getString(R.string.whisper_package_title, 197)
            modelManager.isWhisperReady()
        } else {
            binding.tvModelTitle.text = getString(
                R.string.model_package_title,
                selectedLanguage.displayName,
                selectedLanguage.approxSizeMb
            )
            modelManager.isAsrModelReady(selectedLanguage)
        }

        if (ready) {
            binding.tvModelStatus.text = getString(R.string.model_ready_offline)
            binding.ivModelState.setImageResource(R.drawable.ic_check_circle)
            binding.cardModelIconBg.setCardBackgroundColor(
                MaterialColors.getColor(binding.cardModelIconBg, com.google.android.material.R.attr.colorPrimaryContainer)
            )
            binding.ivModelState.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(binding.ivModelState, com.google.android.material.R.attr.colorOnPrimaryContainer)
            )
            binding.btnDownloadModel.visibility = View.GONE
        } else {
            binding.tvModelStatus.text = getString(R.string.model_not_downloaded)
            binding.ivModelState.setImageResource(R.drawable.ic_download)
            binding.cardModelIconBg.setCardBackgroundColor(
                MaterialColors.getColor(binding.cardModelIconBg, com.google.android.material.R.attr.colorSurfaceContainerHighest)
            )
            binding.ivModelState.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(binding.ivModelState, com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
            binding.btnDownloadModel.visibility = View.VISIBLE
            binding.btnDownloadModel.isEnabled = true
        }
    }

    private fun downloadSelectedLanguageModel() {
        isDownloadingModel = true
        binding.btnDownloadModel.visibility = View.GONE
        binding.btnToggleService.isEnabled = false
        binding.ivModelState.visibility = View.GONE
        binding.progressModelCircle.visibility = View.VISIBLE
        binding.tvDownloadPercent.visibility = View.VISIBLE
        binding.tvDownloadPercent.text = getString(R.string.update_percent_format, 0)
        binding.progressDownload.visibility = View.VISIBLE
        binding.progressDownload.progress = 0
        binding.tvModelStatus.text = getString(R.string.model_downloading_percent, 0)

        lifecycleScope.launch {
            try {
                val onProgress: (String, Int) -> Unit = { status, percent ->
                    runOnUiThread {
                        val clamped = percent.coerceIn(0, 100)
                        binding.tvModelStatus.text = status
                        binding.tvDownloadPercent.text = getString(R.string.update_percent_format, clamped)
                        binding.progressDownload.setProgress(clamped, true)
                    }
                }
                if (selectedAsrMode == AsrMode.WHISPER_ACCURATE) {
                    modelManager.ensureWhisperReady(selectedLanguage, onProgress)
                } else {
                    modelManager.ensureModelsReady(selectedLanguage, onProgress)
                }
                Toast.makeText(this@MainActivity, R.string.download_complete, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                val msg = getString(R.string.download_error, e.message ?: "")
                binding.tvModelStatus.text = msg
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            } finally {
                isDownloadingModel = false
                binding.btnToggleService.isEnabled = true
                refreshModelStatus()
            }
        }
    }

    private fun startLiveTranslateFlow() {
        if (selectedAsrMode == AsrMode.WHISPER_ACCURATE && !modelManager.isWhisperReady()) {
            Toast.makeText(this, R.string.msg_download_whisper_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedAsrMode == AsrMode.STREAMING_FAST && !modelManager.isAsrModelReady(selectedLanguage)) {
            Toast.makeText(
                this,
                getString(R.string.msg_download_first, selectedLanguage.displayName),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (!binding.switchOverlay.isChecked && !binding.switchTts.isChecked) {
            Toast.makeText(this, R.string.msg_enable_one_output, Toast.LENGTH_SHORT).show()
            return
        }

        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        } else {
            checkOverlayAndStartProjection()
        }
    }

    private fun checkOverlayAndStartProjection() {
        if (binding.switchOverlay.isChecked && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlaySettingsLauncher.launch(intent)
        } else {
            requestMediaProjection()
        }
    }

    private fun requestMediaProjection() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun openEdgeCanary() {
        val canaryIntent = packageManager.getLaunchIntentForPackage("com.microsoft.emmx.canary")
        val stableIntent = packageManager.getLaunchIntentForPackage("com.microsoft.emmx")
        when {
            canaryIntent != null -> startActivity(canaryIntent)
            stableIntent != null -> startActivity(stableIntent)
            else -> Toast.makeText(this, R.string.msg_edge_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUiState(isRunning: Boolean) {
        if (isRunning) {
            binding.tvStatusBadge.text = getString(R.string.status_translating)
            binding.tvHeroStatusTitle.text = getString(R.string.hero_live_running)

            val containerBg = MaterialColors.getColor(binding.badgeStatusCard, com.google.android.material.R.attr.colorPrimaryContainer)
            val onContainer = MaterialColors.getColor(binding.tvStatusBadge, com.google.android.material.R.attr.colorOnPrimaryContainer)
            binding.badgeStatusCard.setCardBackgroundColor(containerBg)
            binding.tvStatusBadge.setTextColor(onContainer)
            binding.ivStatusDot.imageTintList = ColorStateList.valueOf(onContainer)

            val errorColor = MaterialColors.getColor(binding.btnToggleService, com.google.android.material.R.attr.colorError)
            val onErrorColor = MaterialColors.getColor(binding.btnToggleService, com.google.android.material.R.attr.colorOnError)
            binding.btnToggleService.text = getString(R.string.btn_stop_translate)
            binding.btnToggleService.setIconResource(R.drawable.ic_stop)
            binding.btnToggleService.backgroundTintList = ColorStateList.valueOf(errorColor)
            binding.btnToggleService.setTextColor(onErrorColor)
            binding.btnToggleService.iconTint = ColorStateList.valueOf(onErrorColor)
        } else {
            binding.tvStatusBadge.text = getString(R.string.status_ready)
            binding.tvHeroStatusTitle.text = getString(R.string.hero_live_idle)

            val surfaceHigh = MaterialColors.getColor(binding.badgeStatusCard, com.google.android.material.R.attr.colorSurfaceContainerHigh)
            val onSurfaceVar = MaterialColors.getColor(binding.tvStatusBadge, com.google.android.material.R.attr.colorOnSurfaceVariant)
            binding.badgeStatusCard.setCardBackgroundColor(surfaceHigh)
            binding.tvStatusBadge.setTextColor(onSurfaceVar)
            binding.ivStatusDot.imageTintList = ColorStateList.valueOf(onSurfaceVar)

            val primaryColor = MaterialColors.getColor(binding.btnToggleService, com.google.android.material.R.attr.colorPrimary)
            val onPrimaryColor = MaterialColors.getColor(binding.btnToggleService, com.google.android.material.R.attr.colorOnPrimary)
            binding.btnToggleService.text = getString(R.string.btn_start_translate)
            binding.btnToggleService.setIconResource(R.drawable.ic_play_arrow)
            binding.btnToggleService.backgroundTintList = ColorStateList.valueOf(primaryColor)
            binding.btnToggleService.setTextColor(onPrimaryColor)
            binding.btnToggleService.iconTint = ColorStateList.valueOf(onPrimaryColor)

            binding.progressAudioLevel.setProgress(0, true)
        }
    }

    private fun setupVersionAndUpdater() {
        val currentVersion = getCurrentVersionName()
        binding.tvAppVersion.text = getString(R.string.app_version_format, currentVersion)

        binding.btnCheckUpdate.setOnClickListener {
            checkForUpdates(silent = false)
        }

        checkForUpdates(silent = true)
    }

    private fun getCurrentVersionName(): String {
        return runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            info.versionName ?: "1.0.0"
        }.getOrDefault("1.0.0")
    }

    private fun checkForUpdates(silent: Boolean) {
        if (!silent) {
            binding.btnCheckUpdate.isEnabled = false
            binding.btnCheckUpdate.alpha = 0.5f
        }

        lifecycleScope.launch {
            try {
                val currentVersion = getCurrentVersionName()
                val updateInfo = AppUpdater.checkForUpdate(currentVersion)
                if (isFinishing || isDestroyed) return@launch
                if (updateInfo != null) {
                    showUpdateDialog(updateInfo)
                } else if (!silent) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_up_to_date, currentVersion),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                if (!silent && !isFinishing && !isDestroyed) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_error, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                if (!silent && !isFinishing && !isDestroyed) {
                    binding.btnCheckUpdate.isEnabled = true
                    binding.btnCheckUpdate.alpha = 1.0f
                }
            }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        val notes = info.releaseNotes.ifBlank { getString(R.string.update_available_title, info.versionName) }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_available_title, info.versionName))
            .setMessage(notes)
            .setNegativeButton(R.string.update_later, null)
            .setPositiveButton(R.string.update_now) { _, _ ->
                downloadAndInstallUpdate(info)
            }
            .show()
    }

    private fun downloadAndInstallUpdate(info: UpdateInfo) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_update_progress, null)
        val tvPercent = dialogView.findViewById<TextView>(R.id.tvUpdateProgressPercent)
        val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.progressUpdateBar)

        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_downloading_title)
            .setView(dialogView)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                val apkFile = AppUpdater.downloadApk(this@MainActivity, info.apkDownloadUrl) { percent ->
                    runOnUiThread {
                        val clamped = percent.coerceIn(0, 100)
                        tvPercent.text = getString(R.string.update_percent_format, clamped)
                        progressBar.setProgress(clamped, true)
                    }
                }
                progressDialog.dismiss()
                AppUpdater.installApk(this@MainActivity, apkFile)
            } catch (e: Exception) {
                progressDialog.dismiss()
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_error, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
