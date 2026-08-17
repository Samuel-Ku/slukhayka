package com.slukhayka.audiobooks.data.metadata

/**
 * Spec-30 T3 (#218) — the curated-asset seed: pours the bundled curated
 * canonical covers into the shared base at startup, so the most visible
 * Works carry a stable, human-picked cover from day one instead of waiting
 * for per-Work source claims.
 *
 * Idempotent by construction: ONE document per curated Work, keyed by the
 * Work **mergeKey** — the SAME identity the read path ([SharedBookMetaStore.getCover])
 * uses, so the app's own reads see the curated value (unlike the universe
 * seed, whose `seed:` documents the app never reads). A re-seed on a later
 * launch writes the same documents (the store's put replaces a document
 * key, never duplicates). Best-effort and silent: no store (no Firebase
 * keys), a failing store, or a failing write contributes nothing — one bad
 * document never aborts the rest, and the local curated asset keeps working
 * either way.
 */
object CuratedCoverSeed {

    /**
     * Pours [covers] into the shared base. [now] injectable so tests pin the
     * provenance stamp.
     */
    suspend fun seed(
        store: SharedBookMetaStore?,
        covers: List<CuratedCover>,
        now: () -> Long = System::currentTimeMillis
    ) {
        val shared = store ?: return
        val stamp = now()
        for (cover in covers) {
            // Defense-in-depth: a blank key or an implausible URL is never
            // written (the decoder already filters, but the seam is the
            // write gate), and a throwing implementation must never surface
            // past the seed either — one bad document cannot abort the rest.
            if (cover.mergeKey.isBlank() || !CoverSanity.isPlausible(cover.coverUrl)) continue
            runCatching {
                shared.putCover(
                    cover.mergeKey,
                    cover.coverUrl,
                    CoverProvenance(
                        source = CoverProvenance.SOURCE_CURATED,
                        resolvedAt = stamp
                    )
                )
            }
        }
    }
}

/**
 * One curated cover: the Work [mergeKey] and its canonical cover URL. The
 * maintainer curates these; the app never fabricates one.
 */
data class CuratedCover(
    val mergeKey: String,
    val coverUrl: String
)