package com.example.data.universe

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spec-26 T1 (#175) — the translation seam behind the Wikidata title-search
 * fallback: when a direct search of the book's title finds nothing (the
 * source titles are Ukrainian, but Wikidata items often carry only ru/en
 * labels), the provider translates the title and searches again.
 *
 * Best-effort and silent like the provider: a failure to download the model,
 * a translation error, or an unknown target language all yield null — the
 * fallback just does not fire. The seam is a pure JVM interface, so the
 * provider and its fixture tests stay Android-free; only this implementation
 * touches the SDK.
 */
interface TitleTranslator {
    /**
     * Translates [title] into [targetLang] (ISO 639-1 codes, e.g. "ru"), or
     * null on any failure. Never throws.
     */
    suspend fun translate(title: String, targetLang: String): String?
}

/**
 * On-device translator via Google ML Kit (spec-26: the user's choice over
 * paid/keyed services) — the same models as Google Translate, fully free, no
 * API key, no limits, private (text never leaves the device). Ukrainian is
 * the source; ru and en are the targets. The translation model downloads
 * once per language pair (preferring Wi-Fi) and is then cached by ML Kit.
 *
 * Android glue only — no fixture tests here (like the adapter transports);
 * the pure [TitleTranslator] seam keeps the provider itself fully tested.
 */
class MlKitTranslator : TitleTranslator {

    private val clients = ConcurrentHashMap<String, Client>()

    override suspend fun translate(title: String, targetLang: String): String? {
        if (title.isBlank()) return null
        val target = when (targetLang) {
            "ru" -> TranslateLanguage.RUSSIAN
            "en" -> TranslateLanguage.ENGLISH
            else -> return null
        }
        val client = clients.getOrPut(targetLang) { Client(ukrainianTo(target)) }
        return withContext(Dispatchers.IO) {
            try {
                if (!client.modelReady) {
                    Tasks.await(client.translator.downloadModelIfNeeded(DOWNLOAD_CONDITIONS))
                    client.modelReady = true
                }
                Tasks.await(client.translator.translate(title)).takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun ukrainianTo(target: String): Translator =
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.UKRAINIAN)
                .setTargetLanguage(target)
                .build()
        )

    private class Client(val translator: Translator) {
        @Volatile
        var modelReady: Boolean = false
    }

    companion object {
        /** Prefer Wi-Fi for the one-time model download (models are tens of MB). */
        private val DOWNLOAD_CONDITIONS = DownloadConditions.Builder().requireWifi().build()
    }
}
