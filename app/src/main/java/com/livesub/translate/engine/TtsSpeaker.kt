package com.livesub.translate.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class TtsSpeaker(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    @Volatile
    private var isReady = false
    private val pendingQueueCount = AtomicInteger(0)

    private var lastSpokenNormalized = ""
    private var lastSpokenTimeMs = 0L

    private val ttsAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(ttsAudioAttributes)
            .build()
    }

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val viLocale = Locale("vi", "VN")
                val res = engine.setLanguage(viLocale)
                if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setAudioAttributes(ttsAudioAttributes)
                    engine.setSpeechRate(1.15f)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            if (pendingQueueCount.decrementAndGet() <= 0) {
                                pendingQueueCount.set(0)
                                audioManager.abandonAudioFocusRequest(focusRequest)
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (pendingQueueCount.decrementAndGet() <= 0) {
                                pendingQueueCount.set(0)
                                audioManager.abandonAudioFocusRequest(focusRequest)
                            }
                        }
                    })
                    isReady = true
                }
            }
        }
    }

    @Synchronized
    fun speak(text: String) {
        if (!isReady || text.isBlank()) return
        val engine = tts ?: return

        val normalized = text.trim().lowercase(Locale.getDefault())
        val now = System.currentTimeMillis()
        if (normalized == lastSpokenNormalized && now - lastSpokenTimeMs < 5000L) {
            return
        }
        lastSpokenNormalized = normalized
        lastSpokenTimeMs = now

        val queueMode = if (pendingQueueCount.get() >= 2) {
            pendingQueueCount.set(1)
            TextToSpeech.QUEUE_FLUSH
        } else {
            pendingQueueCount.incrementAndGet()
            TextToSpeech.QUEUE_ADD
        }

        audioManager.requestAudioFocus(focusRequest)
        val id = "sub_${now}"
        val result = engine.speak(text, queueMode, null, id)
        if (result == TextToSpeech.ERROR) {
            if (pendingQueueCount.decrementAndGet() <= 0) {
                pendingQueueCount.set(0)
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
        }
    }

    fun stopSpeaking() {
        pendingQueueCount.set(0)
        runCatching { tts?.stop() }
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    fun release() {
        stopSpeaking()
        runCatching { tts?.shutdown() }
        tts = null
        isReady = false
    }
}
