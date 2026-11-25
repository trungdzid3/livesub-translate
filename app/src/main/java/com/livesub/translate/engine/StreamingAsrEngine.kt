package com.livesub.translate.engine

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import kotlin.math.abs

data class AsrUpdate(
    val text: String,
    val isFinal: Boolean
)

/**
 * Engine nhận diện giọng nói streaming liên tục bằng Sherpa-ONNX OnlineRecognizer.
 * Giữ ngữ cảnh câu liên tục, cập nhật interim từng từ và chỉ reset khi endpoint.
 */
class StreamingAsrEngine(private val modelDir: File) {

    private val recognizer: OnlineRecognizer
    private val stream: OnlineStream
    private var smoothedGain = 1.0f

    init {
        val tokensFile = File(modelDir, "tokens.txt")
        if (!tokensFile.exists()) {
            throw IllegalStateException("Không tìm thấy tokens.txt trong ${modelDir.name}")
        }
        val files = modelDir.listFiles().orEmpty()
        val encoder = findOnnxFile(files, "encoder")
        val decoder = findOnnxFile(files, "decoder")
        val joiner = findOnnxFile(files, "joiner")

        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = encoder.absolutePath,
                decoder = decoder.absolutePath,
                joiner = joiner.absolutePath
            ),
            tokens = tokensFile.absolutePath,
            numThreads = 4,
            debug = false,
            provider = "cpu"
        )

        val endpointConfig = EndpointConfig(
            rule1 = EndpointRule(
                mustContainNonSilence = false,
                minTrailingSilence = 2.4f,
                minUtteranceLength = 0.0f
            ),
            rule2 = EndpointRule(
                mustContainNonSilence = true,
                minTrailingSilence = 0.8f,
                minUtteranceLength = 0.0f
            ),
            rule3 = EndpointRule(
                mustContainNonSilence = false,
                minTrailingSilence = 0.0f,
                minUtteranceLength = 12.0f
            )
        )

        val recognizerConfig = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = modelConfig,
            endpointConfig = endpointConfig,
            enableEndpoint = true,
            decodingMethod = "greedy_search",
            maxActivePaths = 4
        )

        recognizer = OnlineRecognizer(assetManager = null, config = recognizerConfig)
        stream = recognizer.createStream()
    }

    private fun findOnnxFile(files: Array<out File>, prefix: String): File {
        val candidates = files.filter { it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
        return candidates.firstOrNull { it.name.contains("int8") }
            ?: candidates.firstOrNull()
            ?: throw IllegalStateException("Không tìm thấy file $prefix*.onnx trong ${modelDir.name}")
    }

    fun processAudio(samples: FloatArray): AsrUpdate? {
        if (samples.isEmpty()) return null
        val normalized = applySoftAgc(samples)
        stream.acceptWaveform(normalized, 16000)

        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }

        val rawText = recognizer.getResult(stream).text
        val text = filterRepetitionHallucination(cleanText(rawText))
        val endpointReached = recognizer.isEndpoint(stream) || text.length > 120

        if (endpointReached) {
            recognizer.reset(stream)
            return if (text.isNotBlank()) AsrUpdate(text = text, isFinal = true) else null
        }

        return if (text.isNotBlank()) AsrUpdate(text = text, isFinal = false) else null
    }

    private fun applySoftAgc(samples: FloatArray): FloatArray {
        var maxPeak = 0f
        for (s in samples) {
            val a = abs(s)
            if (a > maxPeak) maxPeak = a
        }

        // Không khuếch đại nhiễu nền/im lặng dưới ngưỡng 0.015f để tránh ảo giác lặp từ
        val targetGain = if (maxPeak < 0.015f) {
            1.0f
        } else {
            (0.70f / maxPeak).coerceIn(1.0f, 4.5f)
        }
        smoothedGain = smoothedGain * 0.75f + targetGain * 0.25f

        if (smoothedGain <= 1.05f) return samples

        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            out[i] = (samples[i] * smoothedGain).coerceIn(-1.0f, 1.0f)
        }
        return out
    }

    private fun cleanText(raw: String): String {
        return raw.replace(Regex("<\\|[^|]+\\|>"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Lọc ảo giác lặp từ/cụm từ khi nhạc nền hoặc im lặng kéo dài (vd: "the the the" hoặc lặp cụm >= 3 lần).
     */
    private fun filterRepetitionHallucination(text: String): String {
        if (text.isBlank()) return ""
        val words = text.split(" ").filter { it.isNotBlank() }
        if (words.size >= 3) {
            // Thu gọn từ lặp liên tiếp quá 2 lần (vd: "no no no no" -> "no")
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
            // Nếu toàn bộ câu chỉ là 1 từ lặp lại nhiều lần -> bỏ qua
            if (collapsed.size == 2 && collapsed[0].equals(collapsed[1], ignoreCase = true) && words.size >= 4) {
                return ""
            }
            return collapsed.joinToString(" ")
        }
        return text
    }

    fun release() {
        runCatching { stream.release() }
        runCatching { recognizer.release() }
    }
}
