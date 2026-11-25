package com.livesub.translate.engine

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import com.livesub.translate.model.FilmLanguage
import java.io.IOException
import java.io.OutputStream
import kotlin.math.abs

/**
 * Engine nhận diện giọng nói trực tiếp bằng Google SpeechRecognizer qua ParcelFileDescriptor pipe (Android 13+).
 * Cho độ chính xác cao khi phim có nhạc nền và tự động xoay vòng pipe liên tục.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class GoogleSpeechPipeEngine(
    private val context: Context,
    private val language: FilmLanguage,
    private val onResult: (text: String, isFinal: Boolean) -> Unit,
    private val onFatalUnsupported: (reason: String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pipeLock = Any()

    private var speechRecognizer: SpeechRecognizer? = null
    private var readFd: ParcelFileDescriptor? = null
    private var writeFd: ParcelFileDescriptor? = null
    private var writeStream: OutputStream? = null

    @Volatile
    private var isReleased = false
    private var pendingRestartRunnable: Runnable? = null

    fun start() {
        mainHandler.post {
            if (isReleased) return@post
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onFatalUnsupported("Thiết bị không hỗ trợ Google SpeechRecognizer.")
                return@post
            }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(recognitionListener)
            }
            startListeningWithNewPipe()
        }
    }

    fun restartPipe() {
        scheduleRestart(delayMs = 50L)
    }

    fun writePcm16(shorts: ShortArray, length: Int) {
        if (isReleased || length <= 0) return

        var maxPeak = 0
        for (i in 0 until length) {
            val a = abs(shorts[i].toInt())
            if (a > maxPeak) maxPeak = a
        }

        // Khuếch đại nhẹ nếu âm thanh phim hơi nhỏ, giữ nguyên nếu là im lặng
        val gain = if (maxPeak in 250..10000) {
            (18000f / maxPeak).coerceIn(1.0f, 3.0f)
        } else {
            1.0f
        }

        val bytes = ByteArray(length * 2)
        for (i in 0 until length) {
            val sample = if (gain > 1.05f) {
                (shorts[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            } else {
                shorts[i].toInt()
            }
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample ushr 8) and 0xFF).toByte()
        }

        synchronized(pipeLock) {
            val out = writeStream ?: return
            try {
                out.write(bytes)
            } catch (_: IOException) {
                // Pipe đang xoay vòng restart, bỏ qua chunk này
            }
        }
    }

    fun writePcm16(bytes: ByteArray, length: Int) {
        if (isReleased || length <= 0) return
        synchronized(pipeLock) {
            val out = writeStream ?: return
            try {
                out.write(bytes, 0, length)
            } catch (_: IOException) {
                // Pipe đang xoay vòng restart
            }
        }
    }

    private fun startListeningWithNewPipe() {
        if (isReleased) return
        val recognizer = speechRecognizer ?: return

        closePipes()
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (e: IOException) {
            onFatalUnsupported("Không thể tạo audio pipe: ${e.message}")
            return
        }

        val rFd = pipe[0]
        val wFd = pipe[1]
        synchronized(pipeLock) {
            readFd = rFd
            writeFd = wFd
            writeStream = ParcelFileDescriptor.AutoCloseOutputStream(wFd)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, rFd)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.speechLocale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        runCatching { recognizer.startListening(intent) }
            .onFailure { scheduleRestart(delayMs = 250L) }
    }

    private fun scheduleRestart(delayMs: Long) {
        mainHandler.post {
            if (isReleased) return@post
            pendingRestartRunnable?.let { mainHandler.removeCallbacks(it) }
            closePipes()
            runCatching { speechRecognizer?.cancel() }

            val runnable = Runnable {
                if (!isReleased) {
                    startListeningWithNewPipe()
                }
            }
            pendingRestartRunnable = runnable
            mainHandler.postDelayed(runnable, delayMs)
        }
    }

    private fun closePipes() {
        synchronized(pipeLock) {
            runCatching { writeStream?.close() }
            writeStream = null
            runCatching { writeFd?.close() }
            writeFd = null
            runCatching { readFd?.close() }
            readFd = null
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (isReleased) return
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isNotBlank()) {
                onResult(text, false)
            }
        }

        override fun onResults(results: Bundle?) {
            if (isReleased) return
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isNotBlank()) {
                onResult(text, true)
            }
            scheduleRestart(delayMs = 100L)
        }

        override fun onError(error: Int) {
            if (isReleased) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    onFatalUnsupported("Thiếu quyền nhận diện giọng nói Google Speech.")
                }
                else -> {
                    // ERROR_NO_MATCH, ERROR_SPEECH_TIMEOUT, ERROR_AUDIO, ERROR_RECOGNIZER_BUSY...
                    scheduleRestart(delayMs = 250L)
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun release() {
        isReleased = true
        closePipes()
        mainHandler.post {
            pendingRestartRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingRestartRunnable = null
            runCatching { speechRecognizer?.cancel() }
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
        }
    }
}
