package com.example.data.imports

import android.content.Context
import androidx.core.content.edit

/**
 * Durable record of SAF tree-uri grants (wayfinder #48). The OS-level
 * persistable permission is taken by the UI at pick time; this store keeps
 * the app's own list of *which* trees were granted and successfully imported
 * from, so a future rescan feature can re-open them without re-picking.
 */
class ImportGrantStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Records a successfully imported tree uri (append-only, deduped). */
    fun addTreeUri(treeUri: String) {
        prefs.edit { putStringSet(KEY_TREE_URIS, grantedTreeUris() + treeUri) }
    }

    /** All tree uris the listener has imported from so far. */
    fun grantedTreeUris(): Set<String> =
        prefs.getStringSet(KEY_TREE_URIS, emptySet()) ?: emptySet()

    private companion object {
        const val PREFS_NAME = "import_grants"
        const val KEY_TREE_URIS = "granted_tree_uris"
    }
}