package com.slukhayka.audiobooks.data.diagnostics

/**
 * #597 — the local, user-shared playback problem report. It is deliberately
 * an ALLOWLIST of fields: no cookies, tokens, page or audio URLs, book
 * titles, positions, listening history, listener ids or raw logs ever enter
 * it. Nothing is uploaded automatically — the listener sees the whole text
 * and shares it through an explicit action (ADR-0025 privacy rules hold).
 *
 * The composer is pure JVM; the platform facts (version, Android/WebView)
 * are supplied by the caller so the allowlist stays testable.
 */
data class PlaybackProblemReport(
    val category: String,
    val stage: String,
    val appVersion: String,
    val appVariant: String,
    val androidRelease: String,
    val androidSdk: Int,
    /** Present only when the platform reports one — never fabricated. */
    val webViewVersion: String? = null,
    /** Present only when a real HTTP status was observed — never fabricated. */
    val httpStatus: Int? = null,
    /** Present only when the source returned one — never fabricated. */
    val cloudflareRayId: String? = null
) {

    /** The exact text the listener reviews before sharing. */
    fun toPlainText(): String = buildString {
        appendLine("Слухайка — звіт про проблему з відтворенням")
        appendLine("Категорія: $category")
        appendLine("Етап: $stage")
        httpStatus?.let { appendLine("HTTP: $it") }
        appendLine("Застосунок: $appVersion ($appVariant)")
        appendLine("Android: $androidRelease (SDK $androidSdk)")
        webViewVersion?.let { appendLine("WebView: $it") }
        cloudflareRayId?.let { appendLine("Cloudflare Ray ID: $it") }
    }

    companion object {
        /** Builds a report, normalizing blanks to honest "unknown"s (never guessing). */
        fun build(
            category: String,
            stage: String,
            appVersion: String,
            appVariant: String,
            androidRelease: String,
            androidSdk: Int,
            webViewVersion: String? = null,
            httpStatus: Int? = null,
            cloudflareRayId: String? = null
        ): PlaybackProblemReport = PlaybackProblemReport(
            category = category.takeIf { it.isNotBlank() } ?: "UNKNOWN",
            stage = stage.takeIf { it.isNotBlank() } ?: "PLAYBACK",
            appVersion = appVersion.takeIf { it.isNotBlank() } ?: "unknown",
            appVariant = appVariant.takeIf { it.isNotBlank() } ?: "unknown",
            androidRelease = androidRelease.takeIf { it.isNotBlank() } ?: "unknown",
            androidSdk = androidSdk,
            webViewVersion = webViewVersion?.takeIf { it.isNotBlank() },
            httpStatus = httpStatus?.takeIf { it > 0 },
            cloudflareRayId = cloudflareRayId?.takeIf { it.isNotBlank() }
        )
    }
}
