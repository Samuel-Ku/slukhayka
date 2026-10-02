package com.slukhayka.audiobooks.data.ingest

/**
 * #831 AC1 — where a catalogue entry came from, as a PURE classifier.
 *
 * Deliberately not a quality score: "from listeners" is not better or worse
 * than "from the shared library", it is a different provenance, and the
 * listener is entitled to know which one they are looking at.
 */
enum class CommunityOrigin {
    /** A verified listener submission — someone played it and it held up. */
    FROM_LISTENERS,

    /** A source whose own link IS the registered community group. */
    FROM_SHARED_LIBRARY
}

/**
 * #831 AC1 — the classifier, with no I/O and no state.
 *
 * ## The precedence question the ticket left open
 *
 * The ticket says two things that can both be true of one entry:
 * «кожне Verified Submission отримує "Від слухачів"» and «"Зі спільної
 * бібліотеки" — лише Source, чий лінк веде на зареєстровану групу». It does
 * not say what to do when an entry is BOTH verified AND points at the group.
 *
 * **Decision (delegated by the owner): the more specific fact wins.** A link
 * that resolves to the registered group states where the audio actually lives;
 * "verified" only states that it was checked. If the app showed "Від слухачів"
 * over an entry that came straight from the community group, the label would be
 * true but less informative than the one the listener needs.
 *
 * The opposite order would also be defensible, so it is written down here
 * rather than left as an accident of the `if` order.
 */
object CommunityOriginClassifier {

    /**
     * @param verifiedAt the moment a REAL playback verdict fired, or null when
     *   the entry was never verified.
     * @param sourceUrl the source's own link, or null when it has none.
     * @return the origin, or null when neither fact holds — an unverified entry
     *   with an unrelated link carries no origin claim, and inventing one would
     *   be exactly the kind of guess ADR-0035 forbids.
     */
    fun classify(verifiedAt: Long?, sourceUrl: String?): CommunityOrigin? {
        if (sourceUrl != null && isRegisteredGroupLink(sourceUrl)) {
            return CommunityOrigin.FROM_SHARED_LIBRARY
        }
        if (verifiedAt != null && verifiedAt > 0L) {
            return CommunityOrigin.FROM_LISTENERS
        }
        return null
    }

    /**
     * True when [url] points at the registered community group.
     *
     * Compared on the canonical shape (lower-case, no trailing slash) because
     * the same group is written several ways in the wild — «t.me/slukhayka»,
     * «https://t.me/slukhayka/», «HTTPS://T.ME/Slukhayka» — and a classifier
     * that only recognises one spelling would silently label the rest as
     * "from listeners".
     */
    fun isRegisteredGroupLink(url: String): Boolean =
        canonical(url) == canonical(TelegramMembershipPolicy.GROUP_URL)

    private fun canonical(url: String): String =
        url.trim().lowercase().removeSuffix("/").removePrefix("https://").removePrefix("http://")
}
