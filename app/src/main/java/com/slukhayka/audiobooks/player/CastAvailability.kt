package com.slukhayka.audiobooks.player

/**
 * ADR-0043 §5 / #1185 T6 — what the receiver can play. The cast proxy fetches
 * its upstreams over HTTP ([PlaybackProxy]); a LOCAL address is a copied file
 * or a SAF document URI on this phone, so there is nothing for the receiver to
 * fetch. A local chapter must therefore refuse the takeover with an
 * explanation instead of starting a session that can never play — and a mixed
 * book is refused WHOLE, because silently skipping its local chapters would
 * lie about what is being cast.
 */
object CastAvailability {

    /** The honest refusal: the book plays from the phone until it has a stream. */
    const val LOCAL_BOOK_EXPLANATION =
        "Кастинг не підтримує локальні файли — ця книга грає з телефона."

    /**
     * Null means the receiver can address every chapter (or the empty/null
     * case is owned by  [CastPlaybackController.NO_STREAM_EXPLANATION]).
     */
    fun refusal(chapterUrls: List<String?>): String? =
        if (chapterUrls.any { it != null && !it.startsWith("http", ignoreCase = true) }) {
            LOCAL_BOOK_EXPLANATION
        } else {
            null
        }
}
