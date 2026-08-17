package com.slukhayka.audiobooks.data.metadata

import android.content.Context

/**
 * Spec-30 T3 (#218) — the bundled curated-covers asset, loaded once at the
 * composition root through the context seam (same pattern as the collections
 * and universe assets). The asset ships with the covers the maintainer
 * curated ([CuratedCoverJson]'s shape); an absent or malformed asset
 * contributes nothing — the seed then simply pours nothing.
 */
object CuratedCoverAssets {

    private const val DIRECTORY = "covers"
    private const val FILE_NAME = "curated_covers.json"

    fun load(context: Context): List<CuratedCover> =
        runCatching {
            val text = context.assets
                .open("$DIRECTORY/$FILE_NAME")
                .bufferedReader()
                .use { it.readText() }
            CuratedCoverJson.decode(text)
        }.getOrDefault(emptyList())
}