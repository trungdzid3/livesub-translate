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
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioPlaybackConfiguration
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
import com.livesub.translate.engine.WhisperVadEngine
import com.livesub.translate.model.AsrMode
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.math.abs
import kotlin.math.sqrt

class LiveTranslateService : Service() {

    companion object {
        const val ACTION_START = "com.livesub.translate.ACTION_START"
        const val ACTION_STOP = "com.livesub.translate.ACTION_STOP"
        const val ACTION_UPDATE_OUTPUT = "com.livesub.translate.ACTION_UPDATE_OUTPUT"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_LANGUAGE = "extra_language"
        const val EXTRA_ASR_MODE = "extra_asr_mode"
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
        private const val MAX_AUDIO_RESTART_ATTEMPTS = 5

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureJob: Job? = null

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var audioManager: AudioManager? = null
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private var audioRecord: AudioRecord? = null
    private var whisperEngine: WhisperVadEngine? = null
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
    @Volatile
    private var needAsrReset = false
    @Volatile
    private var lastPlaybackChangeMs = 0L
    private var currentLanguage = FilmLanguage.ENGLISH
    private var currentAsrMode = AsrMode.WHISPER_ACCURATE

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

                val modeName = intent.getStringExtra(EXTRA_ASR_MODE) ?: AsrMode.WHISPER_ACCURATE.name
                currentAsrMode = runCatching { AsrMode.valueOf(modeName) }
                    .getOrDefault(AsrMode.WHISPER_ACCURATE)

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
    private fun createAudioRecord(projection: MediaProjection): AudioRecord {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(android.os.Process.myUid())
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

        return AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
    }

    private fun startPipeline(resultCode: Int, resultData: Intent) {
        stopAll(keepForeground = true)
        isRunning = true
        needAsrReset = false
        lastPlaybackChangeMs = 0L
        broadcastStatus(running = true)

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        mediaProjection = projection

        val projCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                stopAll()
                stopSelf()
            }
        }
        projectionCallback = projCallback
        projection.registerCallback(projCallback, mainHandler)

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am
        val pbCallback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                needAsrReset = true
                lastPlaybackChangeMs = System.currentTimeMillis()
            }
        }
        playbackCallback = pbCallback
        am.registerAudioPlaybackCallback(pbCallback, mainHandler)

        val overlay = SubtitleOverlayManager(this)
        overlayManager = overlay
        mainHandler.post {
            if (enableOverlay) overlay.show()
        }
        ttsSpeaker = TtsSpeaker(this)

        captureJob = serviceScope.launch {
            val rawAudioChannel = Channel<FloatArray>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            val segmentChannel = Channel<FloatArray>(capacity = 12, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            val finalTranslateChannel = Channel<String>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            val interimTranslateChannel = Channel<String>(capacity = Channel.CONFLATED)

            try {
                val modelManager = ModelManager(applicationContext)
                val googleTranslator = GoogleTranslator(currentLanguage)
                translator = googleTranslator

                var whisperVad: WhisperVadEngine? = null
                var streamingAsr: StreamingAsrEngine? = null

                if (currentAsrMode == AsrMode.WHISPER_ACCURATE) {
                    whisperVad = WhisperVadEngine(
                        context = applicationContext,
                        language = currentLanguage,
                        modelDir = modelManager.getWhisperDir()
                    ).also { whisperEngine = it }
                } else {
                    streamingAsr = StreamingAsrEngine(modelManager.getModelDir(currentLanguage))
                        .also { asrEngine = it }
                }

                var recorder = createAudioRecord(projection)
                audioRecord = recorder
                recorder.startRecording()

                val modeLabel = if (whisperVad != null) "Whisper" else "Streaming"
                updateNotification("Đang dịch $modeLabel (${currentLanguage.displayName} -> Việt)")

                var vadJob: Job? = null
                var decodeJob: Job? = null

                if (whisperVad != null) {
                    // Luồng 2 (Whisper): Chạy Silero VAD tách câu thoại từ rawAudioChannel
                    vadJob = launch(Dispatchers.Default) {
                        for (chunk in rawAudioChannel) {
                            if (needAsrReset) {
                                needAsrReset = false
                                whisperVad.resetVad()
                            }
                            val segments = whisperVad.feedVad(chunk)
                            for (seg in segments) {
                                segmentChannel.trySend(seg)
                            }
                        }
                    }

                    // Luồng 3 (Whisper): Giải mã segment -> Dịch Google -> Overlay + TTS + Broadcast
                    decodeJob = launch(Dispatchers.IO) {
                        var lastFinalNorm = ""
                        var lastFinalTime = 0L

                        for (segment in segmentChannel) {
                            val original = whisperVad.transcribeSegment(segment)
                            val norm = original.trim().lowercase()
                            val now = System.currentTimeMillis()
                            if (norm.isEmpty() || (norm == lastFinalNorm && now - lastFinalTime < 4000L)) {
                                continue
                            }

                            val normalizedOriginal = googleTranslator.normalizeCase(original)
                            val translated = try {
                                googleTranslator.translate(normalizedOriginal)
                            } catch (_: Exception) {
                                ""
                            }
                            if (translated.isBlank()) continue

                            lastFinalNorm = norm
                            lastFinalTime = now

                            if (enableOverlay) {
                                mainHandler.post {
                                    overlayManager?.updateSubtitle(
                                        original = normalizedOriginal,
                                        translated = translated,
                                        showOriginal = showOriginal,
                                        isFinal = true
                                    )
                                }
                            }
                            if (enableTts) {
                                ttsSpeaker?.speak(translated)
                            }
                            broadcastStatus(
                                running = true,
                                original = normalizedOriginal,
                                translated = translated,
                                isFinal = true
                            )
                        }
                    }
                } else if (streamingAsr != null) {
                    // Luồng 2 (Streaming Fast): Nhận diện streaming liên tục
                    vadJob = launch(Dispatchers.Default) {
                        var lastInterimNorm = ""
                        var lastInterimTime = 0L
                        var lastFinalNorm = ""
                        var lastFinalTime = 0L

                        for (chunk in rawAudioChannel) {
                            if (needAsrReset) {
                                needAsrReset = false
                                streamingAsr.resetStream()
                                lastInterimNorm = ""
                                lastInterimTime = 0L
                                interimTranslateChannel.tryReceive()
                            }

                            val update = streamingAsr.processAudio(chunk) ?: continue
                            val norm = update.text.trim().lowercase()
                            val now = System.currentTimeMillis()

                            if (update.isFinal) {
                                lastInterimNorm = ""
                                lastInterimTime = 0L
                                if (norm.isEmpty() || (norm == lastFinalNorm && now - lastFinalTime < 4000L)) {
                                    continue
                                }
                                lastFinalNorm = norm
                                lastFinalTime = now
                                interimTranslateChannel.tryReceive()
                                finalTranslateChannel.trySend(update.text)
                            } else {
                                val isDupInterim = norm.isEmpty() ||
                                    norm == lastInterimNorm ||
                                    (norm == lastFinalNorm && now - lastFinalTime < 4000L)
                                if (!isDupInterim && now - lastInterimTime >= INTERIM_DEBOUNCE_MS) {
                                    lastInterimNorm = norm
                                    lastInterimTime = now
                                    interimTranslateChannel.trySend(update.text)
                                }
                            }
                        }
                    }

                    // Luồng 3 (Streaming Fast): Dịch ưu tiên final trước interim
                    decodeJob = launch(Dispatchers.IO) {
                        var lastFinalDisplayTime = 0L
                        var lastPublishedOriginal = ""
                        var lastPublishedFinal = false

                        while (isActive) {
                            val (textToTranslate, isFinal) = select<Pair<String, Boolean>> {
                                finalTranslateChannel.onReceive { it to true }
                                interimTranslateChannel.onReceive { it to false }
                            }

                            val normalizedOriginal = googleTranslator.normalizeCase(textToTranslate)
                            val translated = try {
                                googleTranslator.translate(normalizedOriginal)
                            } catch (_: Exception) {
                                ""
                            }
                            if (translated.isBlank()) continue

                            val now = System.currentTimeMillis()
                            if (!isFinal && now - lastFinalDisplayTime < 300L) continue
                            if (isFinal && normalizedOriginal.equals(lastPublishedOriginal, ignoreCase = true) && lastPublishedFinal && now - lastFinalDisplayTime < 4000L) {
                                continue
                            }

                            if (isFinal) lastFinalDisplayTime = now
                            lastPublishedOriginal = normalizedOriginal
                            lastPublishedFinal = isFinal

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
                }

                // Luồng 1 (Producer): Đọc AudioRecord liên tục 16kHz Mono, tự phục hồi khi seek làm treo AudioRecord
                val chunkShorts = ShortArray(1024) // 64ms mỗi chunk
                var lastLevelBroadcast = 0L
                var consecutiveRestartAttempts = 0
                var zeroReadStartMs = 0L
                var zeroPeakAfterChangeStartMs = 0L

                fun resetActiveEngine() {
                    whisperVad?.resetVad()
                    streamingAsr?.resetStream()
                }

                while (isActive) {
                    val isRecording = recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
                    val read = if (isRecording) recorder.read(chunkShorts, 0, chunkShorts.size) else -1

                    if (!isRecording || read < 0) {
                        consecutiveRestartAttempts++
                        if (consecutiveRestartAttempts > MAX_AUDIO_RESTART_ATTEMPTS) break
                        delay(200L)
                        runCatching {
                            recorder.stop()
                            recorder.release()
                        }
                        recorder = createAudioRecord(projection)
                        audioRecord = recorder
                        recorder.startRecording()
                        resetActiveEngine()
                        zeroReadStartMs = 0L
                        zeroPeakAfterChangeStartMs = 0L
                        continue
                    }

                    val now = System.currentTimeMillis()
                    if (read == 0) {
                        if (zeroReadStartMs == 0L) zeroReadStartMs = now
                        if (now - zeroReadStartMs >= 2000L) {
                            consecutiveRestartAttempts++
                            if (consecutiveRestartAttempts > MAX_AUDIO_RESTART_ATTEMPTS) break
                            runCatching {
                                recorder.stop()
                                recorder.release()
                            }
                            delay(200L)
                            recorder = createAudioRecord(projection)
                            audioRecord = recorder
                            recorder.startRecording()
                            resetActiveEngine()
                            zeroReadStartMs = 0L
                        }
                        delay(20L)
                        continue
                    }

                    zeroReadStartMs = 0L
                    val floatSlice = FloatArray(read)
                    var sumSq = 0.0
                    var maxPeak = 0f
                    for (i in 0 until read) {
                        val v = chunkShorts[i] / 32768.0f
                        floatSlice[i] = v
                        sumSq += v * v
                        val a = abs(v)
                        if (a > maxPeak) maxPeak = a
                    }

                    if (maxPeak > 0f) {
                        consecutiveRestartAttempts = 0
                        zeroPeakAfterChangeStartMs = 0L
                    } else if (lastPlaybackChangeMs > 0L && now - lastPlaybackChangeMs in 100L..5000L) {
                        if (zeroPeakAfterChangeStartMs == 0L) zeroPeakAfterChangeStartMs = now
                        if (now - zeroPeakAfterChangeStartMs >= 1500L) {
                            consecutiveRestartAttempts++
                            if (consecutiveRestartAttempts > MAX_AUDIO_RESTART_ATTEMPTS) break
                            runCatching {
                                recorder.stop()
                                recorder.release()
                            }
                            delay(200L)
                            recorder = createAudioRecord(projection)
                            audioRecord = recorder
                            recorder.startRecording()
                            resetActiveEngine()
                            zeroPeakAfterChangeStartMs = 0L
                            lastPlaybackChangeMs = 0L
                            continue
                        }
                    } else {
                        zeroPeakAfterChangeStartMs = 0L
                    }

                    rawAudioChannel.trySend(floatSlice)

                    if (now - lastLevelBroadcast > 200L) {
                        lastLevelBroadcast = now
                        val rms = sqrt(sumSq / read).toFloat()
                        val levelPct = (rms * 400f).toInt().coerceIn(0, 100)
                        broadcastAudioLevel(levelPct)
                    }
                }

                rawAudioChannel.close()
                segmentChannel.close()
                finalTranslateChannel.close()
                interimTranslateChannel.close()
                vadJob?.cancel()
                decodeJob?.cancel()
            } catch (e: Exception) {
                updateNotification("Lỗi: ${e.message ?: "Không thể thu âm thanh"}")
            } finally {
                rawAudioChannel.close()
                segmentChannel.close()
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

        playbackCallback?.let { cb ->
            runCatching { audioManager?.unregisterAudioPlaybackCallback(cb) }
        }
        playbackCallback = null
        audioManager = null

        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null

        runCatching { whisperEngine?.release() }
        whisperEngine = null

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

        projectionCallback?.let { cb ->
            runCatching { mediaProjection?.unregisterCallback(cb) }
        }
        projectionCallback = null

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
            .setContentTitle("Origin Sub")
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
