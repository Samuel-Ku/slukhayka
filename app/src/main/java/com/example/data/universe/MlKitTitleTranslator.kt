package com.example.data.universe

import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Spec-26 T1 (#175) — ML Kit Translation behind the [TitleTranslator] seam:
 * on-device, free, no API key, no request limits. The source language is
 * always Ukrainian (the catalog's language); the target is the Wikidata
 * search language (ru/en — [WikidataSeriesProvider]'s fallback order).
 *
 * The on-device model downloads once per language pair (uk→ru, uk→en) on
 * first use, then translates offline. Best-effort and silent by design: a
 * missing model, a failed download or a failed translation yields null —
 * the Wikidata provider degrades exactly like a failing fetch.
 *
 * This class is the only Android touch-point of the translation feature;
 * the provider and its fixture tests stay Android-free (they only see the
 * [TitleTranslator] seam).
 */
class MlKitTitleTranslator(
    private val source: String = TranslateLanguage.UKRAINIAN
) : TitleTranslator {

    private val translators = mutableMapOf<String, Translator>()

    override suspend fun translate(text: String, targetLanguage: String): String? {
        if (text.isBlank()) return null
        val target = when (targetLanguage) {
            "ru" -> TranslateLanguage.RUSSIAN
            "en" -> TranslateLanguage.ENGLISH
            else -> return null
        }
        return runCatching {
            val translator = translators.getOrPut(targetLanguage) {
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(source)
                        .setTargetLanguage(target)
                        .build()
                )
            }
            translator.downloadModelIfNeeded().await()
            translator.translate(text).await()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** Bridges a Google Tasks Task to a suspend call. */
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
    }
}
