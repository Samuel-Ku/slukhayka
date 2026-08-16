package com.slukhayka.audiobooks.data.universe

import android.content.Context

/**
 * Spec-25 (#171) — loads the curated universe assets through the context
 * seam (the same `context.assets` seam the collections module uses).
 *
 * Adding a new universe later is one new JSON file in this directory — no
 * code change. Loading is best-effort per file: a missing or malformed asset
 * contributes no universe (resolution then simply finds nothing); the app
 * never crashes on a bad asset.
 */
object UniverseAssets {

    /** Asset sub-directory of the curated universes. */
    const val DIRECTORY = "universes"

    /** The shipped universes, in display order. */
    private val FILE_NAMES = listOf(
        "first-law.json",
        "witcher.json",
        "drizzt.json",
        "hyperion.json"
    )

    fun load(context: Context): List<UniverseList> =
        FILE_NAMES.mapNotNull { name ->
            runCatching {
                val text = context.assets
                    .open("$DIRECTORY/$name")
                    .bufferedReader()
                    .use { it.readText() }
                UniverseJson.decode(text)
            }.getOrNull()
        }
}
