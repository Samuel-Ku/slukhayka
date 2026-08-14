package com.example.data.collection

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Spec-16 T1 (#107) — reads the curated collection lists shipped as static
 * JSON assets under `assets/collections/`: an index of collection ids, each
 * expanded from `assets/collections/<id>.json`.
 *
 * Loading is deliberately failure-tolerant: a missing or malformed file
 * contributes nothing instead of breaking the catalog.
 */
object SmartCollectionAssets {

    private const val COLLECTIONS_DIR = "collections"

    private val MOSHI = Moshi.Builder().build()
    private val indexAdapter =
        MOSHI.adapter<List<String>>(Types.newParameterizedType(List::class.java, String::class.java))
    private val specAdapter = MOSHI.adapter(CollectionSpec::class.java)

    fun load(context: Context): List<CollectionSpec> {
        val ids = readJson(context, "$COLLECTIONS_DIR/index.json", indexAdapter) ?: return emptyList()
        return ids.mapNotNull { id ->
            readJson(context, "$COLLECTIONS_DIR/$id.json", specAdapter)
        }
    }

    private fun <T> readJson(context: Context, path: String, adapter: com.squareup.moshi.JsonAdapter<T>): T? =
        try {
            context.assets.open(path).bufferedReader().use { it.readText() }
                .let { text -> adapter.fromJson(text) }
        } catch (_: Exception) {
            null
        }
}