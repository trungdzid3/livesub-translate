package com.livesub.translate.model

import com.google.mlkit.nl.translate.TranslateLanguage

const val WHISPER_FOLDER = "sherpa-onnx-whisper-base"
const val WHISPER_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2"
const val WHISPER_SIZE_MB = 197

enum class AsrMode {
    WHISPER_ACCURATE,
    STREAMING_FAST
}

enum class FilmLanguage(
    val displayName: String,
    val googleLangCode: String,
    val whisperCode: String,
    val speechLocale: String,
    val mlKitCode: String,
    val modelFolderName: String,
    val downloadUrl: String,
    val approxSizeMb: Int
) {
    ENGLISH(
        displayName = "Tiếng Anh",
        googleLangCode = "en",
        whisperCode = "en",
        speechLocale = "en-US",
        mlKitCode = TranslateLanguage.ENGLISH,
        modelFolderName = "sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06",
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06.tar.bz2",
        approxSizeMb = 55
    ),
    CHINESE(
        displayName = "Tiếng Trung",
        googleLangCode = "zh-CN",
        whisperCode = "zh",
        speechLocale = "zh-CN",
        mlKitCode = TranslateLanguage.CHINESE,
        modelFolderName = "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2",
        approxSizeMb = 127
    ),
    JAPANESE(
        displayName = "Tiếng Nhật",
        googleLangCode = "ja",
        whisperCode = "ja",
        speechLocale = "ja-JP",
        mlKitCode = TranslateLanguage.JAPANESE,
        modelFolderName = "sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10",
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10.tar.bz2",
        approxSizeMb = 247
    ),
    RUSSIAN(
        displayName = "Tiếng Nga",
        googleLangCode = "ru",
        whisperCode = "ru",
        speechLocale = "ru-RU",
        mlKitCode = TranslateLanguage.RUSSIAN,
        modelFolderName = "sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16",
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16.tar.bz2",
        approxSizeMb = 23
    )
}

data class TranslationConfig(
    val language: FilmLanguage = FilmLanguage.ENGLISH,
    val enableOverlay: Boolean = true,
    val enableTts: Boolean = true,
    val showOriginalText: Boolean = true
)
