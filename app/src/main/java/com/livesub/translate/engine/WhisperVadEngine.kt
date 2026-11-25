package com.livesub.translate.engine

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.livesub.translate.model.FilmLanguage
import java.io.File
import kotlin.math.abs

/**
 * Engine nhận diện chuẩn xác cao cho phim có nhạc nền:
 * Silero VAD tách câu thoại sạch + Whisper Base (Sherpa-ONNX OfflineRecognizer).
 */
class WhisperVadEngine(
    context: Context,
    language: FilmLanguage,
    modelDir: File
) {
    private val vad: Vad
    private val recognizer: OfflineRecognizer

    init {
        val vadConfig = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = 0.35f,
                minSilenceDuration = 0.25f,
                minSpeechDuration = 0.20f,
                windowSize = 512,
                maxSpeechDuration = 5.5f
            ),
            sampleRate = 16000,
            numThreads = 2,
            provider = "cpu",
            debug = false
        )
        vad = Vad(assetManager = context.assets, config = vadConfig)

        val encoderPath = File(modelDir, "base-encoder.int8.onnx").absolutePath
        val decoderPath = File(modelDir, "base-decoder.int8.onnx").absolutePath
        val tokensPath = File(modelDir, "base-tokens.txt").absolutePath

        val whisperConfig = OfflineWhisperModelConfig(
            encoder = encoderPath,
            decoder = decoderPath,
            language = language.whisperCode,
            task = "transcribe"
        )

        val modelConfig = OfflineModelConfig(
            whisper = whisperConfig,
            tokens = tokensPath,
            numThreads = 4,
            debug = false,
            provider = "cpu"
        )

        recognizer = OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                modelConfig = modelConfig,
                decodingMethod = "greedy_search"
            )
        )
    }

    @Synchronized
    fun feedVad(samples: FloatArray): List<FloatArray> {
        if (samples.isEmpty()) return emptyList()
        val processed = applySoftGainIfNeeded(samples)
        vad.acceptWaveform(processed)

        val segments = mutableListOf<FloatArray>()
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            if (segment.samples.size >= 3200) {
                segments.add(segment.samples)
            }
        }
        return segments
    }

    @Synchronized
    fun transcribeSegment(segmentSamples: FloatArray): String {
        if (segmentSamples.size < 3200) return ""
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(segmentSamples, 16000)
            recognizer.decode(stream)
            val rawText = recognizer.getResult(stream).text
            cleanWhisperHallucinations(rawText)
        } finally {
            runCatching { stream.release() }
        }
    }

    @Synchronized
    fun resetVad() {
        runCatching { vad.reset() }
    }

    @Synchronized
    fun release() {
        runCatching { vad.release() }
        runCatching { recognizer.release() }
    }

    private fun applySoftGainIfNeeded(samples: FloatArray): FloatArray {
        var maxPeak = 0f
        for (s in samples) {
            val a = abs(s)
            if (a > maxPeak) maxPeak = a
        }
        if (maxPeak !in 0.01f..0.45f) return samples
        val gain = (0.65f / maxPeak).coerceIn(1.0f, 4.0f)
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            out[i] = (samples[i] * gain).coerceIn(-1.0f, 1.0f)
        }
        return out
    }

    private fun cleanWhisperHallucinations(raw: String): String {
        var text = raw
            .replace(Regex("<\\|[^|]+\\|>"), " ")
            .replace(Regex("\\[[^\\]]*]"), " ")
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace("♪", " ")
            .replace("♫", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (text.length < 2) return ""

        val lower = text.lowercase()
        val blockedExact = setOf(
            "thank you for watching",
            "thank you for watching!",
            "thanks for watching",
            "thanks for watching!",
            "thank you.",
            "thank you",
            "subscribe",
            "you",
            "bye.",
            "bye"
        )
        if (lower in blockedExact || lower.startsWith("subtitles by")) {
            return ""
        }

        // Lọc chuỗi lặp từ vô nghĩa >= 3 lần liên tiếp
        val words = text.split(" ").filter { it.isNotBlank() }
        if (words.size >= 3) {
            val collapsed = mutableListOf<String>()
            var repeatCount = 1
            for (i in words.indices) {
                if (i > 0 && words[i].equals(words[i - 1], ignoreCase = true)) {
                    repeatCount++
                    if (repeatCount <= 2) {
                        collapsed.add(words[i])
                    }
                } else {
                    repeatCount = 1
                    collapsed.add(words[i])
                }
            }
            if (collapsed.size == 2 && collapsed[0].equals(collapsed[1], ignoreCase = true) && words.size >= 4) {
                return ""
            }
            text = collapsed.joinToString(" ")
        }

        return text
    }
}
