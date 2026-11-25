package com.livesub.translate.engine

import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.livesub.translate.model.FilmLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Bộ dịch chuẩn Google Dịch miễn phí (gtx endpoint) với fallback sang Google ML Kit Offline.
 */
class GoogleTranslator(private val language: FilmLanguage) {

    private val mlKitTranslator: Translator
    @Volatile
    private var isMlKitReady = false
    private val cache = LruCache<String, String>(64)

    init {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(language.mlKitCode)
            .setTargetLanguage(TranslateLanguage.VIETNAMESE)
            .build()
        mlKitTranslator = Translation.getClient(options)
        mlKitTranslator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { isMlKitReady = true }
    }

    fun normalizeCase(text: String): String {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return cleaned
        if (language == FilmLanguage.ENGLISH || language == FilmLanguage.RUSSIAN) {
            val letters = cleaned.filter { it.isLetter() }
            val upperCount = letters.count { it.isUpperCase() }
            if (letters.isNotEmpty() && upperCount.toFloat() / letters.length > 0.7f) {
                val lower = cleaned.lowercase(Locale.getDefault())
                return lower.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                }
            }
        }
        return cleaned
    }

    suspend fun translate(rawText: String): String {
        val normalized = normalizeCase(rawText)
        if (normalized.isBlank()) return ""

        cache.get(normalized)?.let { return it }

        val onlineResult = translateViaGoogleFree(normalized)
        if (!onlineResult.isNullOrBlank()) {
            cache.put(normalized, onlineResult)
            return onlineResult
        }

        val offlineResult = translateViaMlKit(normalized)
        if (offlineResult.isNotBlank()) {
            cache.put(normalized, offlineResult)
            return offlineResult
        }

        return normalized
    }

    private suspend fun translateViaGoogleFree(text: String): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val endpoint = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=${language.googleLangCode}&tl=vi&dt=t&q=$encoded"
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2500
                readTimeout = 2500
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext null
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONArray(body)
            val sentences = root.optJSONArray(0) ?: return@withContext null
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val item = sentences.optJSONArray(i) ?: continue
                val chunk = item.optString(0, "")
                sb.append(chunk)
            }
            sb.toString().trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun translateViaMlKit(text: String): String {
        if (!isMlKitReady) {
            ensureMlKitReady()
        }
        return suspendCancellableCoroutine { cont ->
            mlKitTranslator.translate(text)
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result.trim())
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume("")
                }
        }
    }

    private suspend fun ensureMlKitReady() {
        suspendCancellableCoroutine { cont ->
            mlKitTranslator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener {
                    isMlKitReady = true
                    if (cont.isActive) cont.resume(Unit)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(Unit)
                }
        }
    }

    fun release() {
        runCatching { mlKitTranslator.close() }
    }
}
