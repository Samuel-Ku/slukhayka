package com.slukhayka.audiobooks.data.collections

import android.content.Context

/**
 * Spec-16 T1 — loads the curated collection assets through the context seam
 * (the same `context.assets` seam the E5 model uses).
 *
 * Adding a new collection later is one new JSON file in this directory — no
 * code change. Loading is best-effort per file: a missing or malformed asset
 * contributes no collection (the matcher then simply has nothing to show);
 * the app never crashes on a bad list.
 */
object CollectionAssets {

    /** Asset sub-directory of the curated collections. */
    const val DIRECTORY = "collections"

    /** The shipped collections, in display order. */
    private val FILE_NAMES = listOf("nobel.json", "shevchenko.json", "booker.json")

    fun load(context: Context): List<CollectionList> =
        FILE_NAMES.mapNotNull { name ->
            runCatching {
                val text = context.assets
                    .open("$DIRECTORY/$name")
                    .bufferedReader()
                    .use { it.readText() }
                CollectionJson.decode(text)
            }.getOrNull()
        }
}
