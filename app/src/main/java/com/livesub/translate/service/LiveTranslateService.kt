package com.livesub.translate.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.livesub.translate.R
import com.livesub.translate.engine.GoogleTranslator
import com.livesub.translate.engine.ModelManager
import com.livesub.translate.engine.StreamingAsrEngine
import com.livesub.translate.engine.TtsSpeaker
import com.livesub.translate.model.FilmLanguage
import com.livesub.translate.overlay.SubtitleOverlayManager
import com.livesub.translate.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.math.sqrt

class LiveTranslateService : Service() {

    companion object {
        const val ACTION_START = "com.livesub.translate.ACTION_START"
        const val ACTION_STOP = "com.livesub.translate.ACTION_STOP"
        const val ACTION_UPDATE_OUTPUT = "com.livesub.translate.ACTION_UPDATE_OUTPUT"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_LANGUAGE = "extra_language"
        const val EXTRA_ENABLE_OVERLAY = "extra_enable_overlay"
        const val EXTRA_ENABLE_TTS = "extra_enable_tts"
        const val EXTRA_SHOW_ORIGINAL = "extra_show_original"

        const val BROADCAST_STATUS = "com.livesub.translate.BROADCAST_STATUS"
        const val EXTRA_IS_RUNNING = "extra_is_running"
        const val EXTRA_LAST_ORIGINAL = "extra_last_original"
        const val EXTRA_LAST_TRANSLATED = "extra_last_translated"
        const val EXTRA_IS_FINAL = "extra_is_final"
        const val EXTRA_AUDIO_LEVEL = "extra_audio_level"

        private const val CHANNEL_ID = "live_translate_channel"
        private const val NOTIFICATION_ID = 1001
        private const val INTERIM_DEBOUNCE_MS = 350L

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureJob: Job? = null

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var asrEngine: StreamingAsrEngine? = null
    private var translator: GoogleTranslator? = null
    private var overlayManager: SubtitleOverlayManager? = null
    private var ttsSpeaker: TtsSpeaker? = null

    @Volatile
    private var enableOverlay = true
    @Volatile
    private var enableTts = true
    @Volatile
    private var showOriginal = true
    private var currentLanguage = FilmLanguage.ENGLISH

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_OUTPUT -> {
                enableOverlay = intent.getBooleanExtra(EXTRA_ENABLE_OVERLAY, enableOverlay)
                enableTts = intent.getBooleanExtra(EXTRA_ENABLE_TTS, enableTts)
                showOriginal = intent.getBooleanExtra(EXTRA_SHOW_ORIGINAL, showOriginal)
                mainHandler.post {
                    if (enableOverlay) {
                        overlayManager?.show()
                    } else {
                        overlayManager?.hide()
                    }
                }
                if (!enableTts) {
                    ttsSpeaker?.stopSpeaking()
                }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val langName = intent.getStringExtra(EXTRA_LANGUAGE) ?: FilmLanguage.ENGLISH.name
                currentLanguage = runCatching { FilmLanguage.valueOf(langName) }
                    .getOrDefault(FilmLanguage.ENGLISH)
                enableOverlay = intent.getBooleanExtra(EXTRA_ENABLE_OVERLAY, true)
                enableTts = intent.getBooleanExtra(EXTRA_ENABLE_TTS, true)
                showOriginal = intent.getBooleanExtra(EXTRA_SHOW_ORIGINAL, true)

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Đang khởi động Live Translate (${currentLanguage.displayName})...")
                )
                startPipeline(resultCode, resultData)
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startPipeline(resultCode: Int, resultData: Intent) {
        stopAll(keepForeground = true)
        isRunning = true
        broadcastStatus(running = true)

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        mediaProjection = projection

        val overlay = SubtitleOverlayManager(this)
        overlayManager = overlay
        mainHandler.post {
            if (enableOverlay) overlay.show()
        }
        ttsSpeaker = TtsSpeaker(this)

        captureJob = serviceScope.launch {
            val rawAudioChannel = Channel<FloatArray>(capacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            val finalTranslateChannel = Channel<String>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            val interimTranslateChannel = Channel<String>(capacity = Channel.CONFLATED)

            try {
                val modelDir = ModelManager(applicationContext).getModelDir(currentLanguage)
                val streamingAsr = StreamingAsrEngine(modelDir)
                asrEngine = streamingAsr
                val googleTranslator = GoogleTranslator(currentLanguage)
                translator = googleTranslator

                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val sampleRate = 16000
                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()

                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = maxOf(minBuf * 4, 32000)

                val recorder = AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .build()
                audioRecord = recorder
                recorder.startRecording()

                updateNotification("Đang dịch live (${currentLanguage.displayName} -> Việt)")

                // Luồng 2: Nhận diện streaming liên tục, giữ nguyên mạch câu cho tới khi endpoint
                val asrJob = launch(Dispatchers.Default) {
                    var lastInterimText = ""
                    var lastInterimTime = 0L

                    for (chunk in rawAudioChannel) {
                        val update = streamingAsr.processAudio(chunk) ?: continue
                        if (update.isFinal) {
                            lastInterimText = ""
                            lastInterimTime = 0L
                            finalTranslateChannel.trySend(update.text)
                        } else {
                            val now = System.currentTimeMillis()
                            if (update.text != lastInterimText && now - lastInterimTime >= INTERIM_DEBOUNCE_MS) {
                                lastInterimText = update.text
                                lastInterimTime = now
                                interimTranslateChannel.trySend(update.text)
                            }
                        }
                    }
                }

                // Luồng 3: Dịch ưu tiên câu đã chốt (final) trước interim; chỉ đọc TTS khi chốt câu
                val translateJob = launch(Dispatchers.IO) {
                    while (isActive) {
                        val (textToTranslate, isFinal) = select<Pair<String, Boolean>> {
                            finalTranslateChannel.onReceive { it to true }
                            interimTranslateChannel.onReceive { it to false }
                        }

                        val normalizedOriginal = googleTranslator.normalizeCase(textToTranslate)
                        val translated = googleTranslator.translate(normalizedOriginal)
                        if (translated.isBlank()) continue

                        if (enableOverlay) {
                            mainHandler.post {
                                overlayManager?.updateSubtitle(
                                    original = normalizedOriginal,
                                    translated = translated,
                                    showOriginal = showOriginal,
                                    isFinal = isFinal
                                )
                            }
                        }

                        if (isFinal && enableTts) {
                            ttsSpeaker?.speak(translated)
                        }

                        broadcastStatus(
                            running = true,
                            original = normalizedOriginal,
                            translated = translated,
                            isFinal = isFinal
                        )
                    }
                }

                // Luồng 1 (Producer): Đọc AudioRecord liên tục 16kHz Mono, không bao giờ block
                val chunkShorts = ShortArray(1024) // 64ms mỗi chunk
                var lastLevelBroadcast = 0L

                while (isActive && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = recorder.read(chunkShorts, 0, chunkShorts.size)
                    if (read > 0) {
                        val floatSlice = FloatArray(read)
                        var sumSq = 0.0
                        for (i in 0 until read) {
                            val v = chunkShorts[i] / 32768.0f
                            floatSlice[i] = v
                            sumSq += v * v
                        }
                        rawAudioChannel.trySend(floatSlice)

                        val now = System.currentTimeMillis()
                        if (now - lastLevelBroadcast > 200L) {
                            lastLevelBroadcast = now
                            val rms = sqrt(sumSq / read).toFloat()
                            val levelPct = (rms * 400f).toInt().coerceIn(0, 100)
                            broadcastAudioLevel(levelPct)
                        }
                    }
                }

                rawAudioChannel.close()
                finalTranslateChannel.close()
                interimTranslateChannel.close()
                asrJob.cancel()
                translateJob.cancel()
            } catch (e: Exception) {
                updateNotification("Lỗi: ${e.message ?: "Không thể thu âm thanh"}")
            } finally {
                rawAudioChannel.close()
                finalTranslateChannel.close()
                interimTranslateChannel.close()
                stopAll()
                stopSelf()
            }
        }
    }

    private fun stopAll(keepForeground: Boolean = false) {
        isRunning = false
        captureJob?.cancel()
        captureJob = null

        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null

        runCatching { asrEngine?.release() }
        asrEngine = null

        runCatching { translator?.release() }
        translator = null

        val overlayToHide = overlayManager
        overlayManager = null
        mainHandler.post {
            runCatching { overlayToHide?.hide() }
        }

        runCatching { ttsSpeaker?.release() }
        ttsSpeaker = null

        runCatching { mediaProjection?.stop() }
        mediaProjection = null

        if (!keepForeground) {
            broadcastStatus(running = false)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun broadcastStatus(
        running: Boolean,
        original: String? = null,
        translated: String? = null,
        isFinal: Boolean = true
    ) {
        val intent = Intent(BROADCAST_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_RUNNING, running)
            if (original != null) putExtra(EXTRA_LAST_ORIGINAL, original)
            if (translated != null) putExtra(EXTRA_LAST_TRANSLATED, translated)
            putExtra(EXTRA_IS_FINAL, isFinal)
        }
        sendBroadcast(intent)
    }

    private fun broadcastAudioLevel(levelPct: Int) {
        val intent = Intent(BROADCAST_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_RUNNING, true)
            putExtra(EXTRA_AUDIO_LEVEL, levelPct)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Dịch phim trực tiếp",
            NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(content: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LiveTranslateService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LiveSub Translate")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_subtitles)
            .setContentIntent(openIntent)
            .addAction(0, "Dừng dịch", stopIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopAll()
        serviceScope.cancel()
        super.onDestroy()
    }
}
