package com.livesub.translate.engine

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.livesub.translate.model.FilmLanguage
import com.livesub.translate.model.WHISPER_FOLDER
import com.livesub.translate.model.WHISPER_SIZE_MB
import com.livesub.translate.model.WHISPER_URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ModelManager(private val context: Context) {

    private val modelsRoot: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    fun getModelDir(language: FilmLanguage): File =
        File(modelsRoot, language.modelFolderName)

    fun getWhisperDir(): File =
        File(modelsRoot, WHISPER_FOLDER)

    fun isWhisperReady(): Boolean {
        val dir = getWhisperDir()
        if (!dir.exists() || !dir.isDirectory) return false
        val encoder = File(dir, "base-encoder.int8.onnx")
        val decoder = File(dir, "base-decoder.int8.onnx")
        val tokens = File(dir, "base-tokens.txt")
        return encoder.exists() && encoder.length() > 18L * 1024L * 1024L &&
            decoder.exists() && decoder.length() > 45L * 1024L * 1024L &&
            tokens.exists() && tokens.length() > 0L
    }

    fun isAsrModelReady(language: FilmLanguage): Boolean {
        val dir = getModelDir(language)
        if (!dir.exists() || !dir.isDirectory) return false
        if (!File(dir, "tokens.txt").exists()) return false

        val files = dir.listFiles().orEmpty()
        val hasEncoder = files.any { it.name.startsWith("encoder") && it.name.endsWith(".onnx") }
        val hasDecoder = files.any { it.name.startsWith("decoder") && it.name.endsWith(".onnx") }
        val hasJoiner = files.any { it.name.startsWith("joiner") && it.name.endsWith(".onnx") }
        return hasEncoder && hasDecoder && hasJoiner
    }

    suspend fun ensureWhisperReady(
        language: FilmLanguage,
        onProgress: (status: String, percent: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (!isWhisperReady()) {
            downloadAndExtractArchive(
                archiveName = "$WHISPER_FOLDER.tar.bz2",
                targetDir = getWhisperDir(),
                downloadUrl = WHISPER_URL,
                label = "Whisper Đa Ngôn Ngữ",
                approxSizeMb = WHISPER_SIZE_MB,
                onProgress = onProgress
            )
            if (!isWhisperReady()) {
                throw IllegalStateException("Giải nén hoàn tất nhưng thiếu file model Whisper trong ${getWhisperDir().name}")
            }
        }
        onProgress("Đang chuẩn bị gói dịch offline dự phòng (${language.displayName} -> Việt)...", 92)
        runCatching { downloadMlKitModel(language) }
        onProgress("Whisper Đa Ngôn Ngữ đã sẵn sàng.", 100)
    }

    suspend fun ensureModelsReady(
        language: FilmLanguage,
        onProgress: (status: String, percent: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (!isAsrModelReady(language)) {
            downloadAndExtractArchive(
                archiveName = "${language.modelFolderName}.tar.bz2",
                targetDir = getModelDir(language),
                downloadUrl = language.downloadUrl,
                label = "Streaming ${language.displayName}",
                approxSizeMb = language.approxSizeMb,
                onProgress = onProgress
            )
            if (!isAsrModelReady(language)) {
                throw IllegalStateException("Giải nén hoàn tất nhưng thiếu file model ONNX trong ${getModelDir(language).name}")
            }
        }
        onProgress("Đang chuẩn bị gói dịch offline dự phòng (${language.displayName} -> Việt)...", 92)
        runCatching { downloadMlKitModel(language) }
        onProgress("Dữ liệu ${language.displayName} đã sẵn sàng.", 100)
    }

    private fun downloadAndExtractArchive(
        archiveName: String,
        targetDir: File,
        downloadUrl: String,
        label: String,
        approxSizeMb: Int,
        onProgress: (status: String, percent: Int) -> Unit
    ) {
        val archiveFile = File(modelsRoot, archiveName)
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }

        try {
            onProgress("Đang kết nối tải $label (~$approxSizeMb MB)...", 2)
            downloadFileWithRedirect(downloadUrl, archiveFile) { downloaded, total ->
                val pct = if (total > 0) ((downloaded * 75L) / total).toInt().coerceIn(2, 75) else 25
                val mb = downloaded / (1024 * 1024)
                val totalMb = if (total > 0) total / (1024 * 1024) else approxSizeMb.toLong()
                onProgress("Đang tải $label: ${mb}MB / ${totalMb}MB", pct)
            }

            onProgress("Đang giải nén $label...", 78)
            extractTarBz2(archiveFile, modelsRoot)
            onProgress("Giải nén hoàn tất.", 90)
        } finally {
            if (archiveFile.exists()) {
                archiveFile.delete()
            }
        }
    }

    private fun downloadFileWithRedirect(
        urlStr: String,
        dest: File,
        onBytes: (Long, Long) -> Unit
    ) {
        var currentUrl = urlStr
        var connection: HttpURLConnection
        var redirects = 0

        while (true) {
            connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "LiveSubTranslate-Android")
            }
            val code = connection.responseCode
            if (code in 300..399 && redirects < 6) {
                val next = connection.getHeaderField("Location") ?: break
                connection.disconnect()
                currentUrl = next
                redirects++
            } else {
                break
            }
        }

        if (connection.responseCode !in 200..299) {
            val code = connection.responseCode
            connection.disconnect()
            throw IllegalStateException("Tải model thất bại (HTTP $code)")
        }

        val totalBytes = connection.contentLengthLong
        connection.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(64 * 1024)
                var downloaded = 0L
                var lastReport = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (downloaded - lastReport > 256 * 1024) {
                        lastReport = downloaded
                        onBytes(downloaded, totalBytes)
                    }
                }
                onBytes(downloaded, totalBytes)
            }
        }
        connection.disconnect()
    }

    private fun extractTarBz2(archiveFile: File, destRoot: File) {
        val canonicalRoot = destRoot.canonicalPath
        BufferedInputStream(archiveFile.inputStream(), 64 * 1024).use { bis ->
            BZip2CompressorInputStream(bis).use { bzip ->
                TarArchiveInputStream(bzip).use { tar ->
                    var entry = tar.nextEntry
                    val buffer = ByteArray(64 * 1024)
                    while (entry != null) {
                        val outFile = File(destRoot, entry.name)
                        if (!outFile.canonicalPath.startsWith(canonicalRoot)) {
                            throw SecurityException("Invalid tar entry: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                while (true) {
                                    val len = tar.read(buffer)
                                    if (len <= 0) break
                                    out.write(buffer, 0, len)
                                }
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }

    private suspend fun downloadMlKitModel(language: FilmLanguage) {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(language.mlKitCode)
            .setTargetLanguage(TranslateLanguage.VIETNAMESE)
            .build()
        val translator = Translation.getClient(options)
        val conditions = DownloadConditions.Builder().build()

        suspendCancellableCoroutine { cont ->
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    translator.close()
                    if (cont.isActive) cont.resume(Unit)
                }
                .addOnFailureListener { ex ->
                    translator.close()
                    if (cont.isActive) cont.resumeWithException(ex)
                }
        }
    }
}
