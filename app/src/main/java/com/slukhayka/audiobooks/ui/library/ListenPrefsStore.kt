package com.slukhayka.audiobooks.ui.library

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map

internal data class ListenPrefsSnapshot(
    override val order: List<ListenComposer.BlockId>,
    override val hiddenBlockIds: Set<ListenComposer.BlockId>,
    override val dismissedBookIds: Set<String>,
) : ListenPrefs

/**
 * SharedPreferences-backed [ListenPrefs] (wayfinder #62). Persists the
 * user's block order, hidden blocks and dismissed works locally — a taste
 * preference, never synced (see [ListenPrefs]).
 */
class ListenPrefsStore(context: Context) : ListenPrefs {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("listen_prefs", Context.MODE_PRIVATE)

    private fun snapshot(): ListenPrefsSnapshot {
        val values = prefs.all
        fun blockId(name: String) = ListenComposer.BlockId.entries.firstOrNull { it.name == name }
        return ListenPrefsSnapshot(
            order = (values[KEY_ORDER] as? String)?.split(DELIMITER).orEmpty().mapNotNull(::blockId),
            hiddenBlockIds = (values[KEY_HIDDEN] as? Set<*>).orEmpty()
                .filterIsInstance<String>().mapNotNull(::blockId).toSet(),
            dismissedBookIds = (values[KEY_DISMISSED] as? Set<*>).orEmpty().filterIsInstance<String>().toSet(),
        )
    }

    internal val snapshots: Flow<ListenPrefsSnapshot> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { snapshot() }

    override val order get() = snapshot().order
    override val hiddenBlockIds get() = snapshot().hiddenBlockIds
    override val dismissedBookIds get() = snapshot().dismissedBookIds

    /** Moves a block one step up (towards the top). No-op when already first. */
    fun moveBlockUp(id: ListenComposer.BlockId) {
        val current = order.ifEmpty { ListenComposer.DEFAULT_ORDER }.toMutableList()
        val index = current.indexOf(id)
        if (index <= 0) return
        current.removeAt(index)
        current.add(index - 1, id)
        persistOrder(current)
    }

    /** Moves a block one step down (towards the bottom). No-op when already last. */
    fun moveBlockDown(id: ListenComposer.BlockId) {
        val current = order.ifEmpty { ListenComposer.DEFAULT_ORDER }.toMutableList()
        val index = current.indexOf(id)
        if (index < 0 || index >= current.lastIndex) return
        current.removeAt(index)
        current.add(index + 1, id)
        persistOrder(current)
    }

    /** Hides a block; it stays computed but unrendered. Restorable. */
    fun hideBlock(id: ListenComposer.BlockId) {
        prefs.edit().putStringSet(KEY_HIDDEN, hiddenBlockIds.map { it.name }.toSet() + id.name).apply()
    }

    /** Restores one hidden block (the sheet's per-row show toggle). */
    fun unhideBlock(id: ListenComposer.BlockId) {
        prefs.edit().putStringSet(KEY_HIDDEN, hiddenBlockIds.map { it.name }.toSet() - id.name).apply()
    }

    /** Restores every hidden block. */
    fun restoreHiddenBlocks() {
        prefs.edit().remove(KEY_HIDDEN).apply()
    }

    /** «Не цікаво» — the work is filtered from every block. Reversible. */
    fun dismissBook(bookId: String) {
        prefs.edit().putStringSet(KEY_DISMISSED, dismissedBookIds + bookId).apply()
    }

    /** Reverses a «Не цікаво». */
    fun undismissBook(bookId: String) {
        prefs.edit().putStringSet(KEY_DISMISSED, dismissedBookIds - bookId).apply()
    }

    private fun persistOrder(order: List<ListenComposer.BlockId>) {
        prefs.edit().putString(KEY_ORDER, order.joinToString(DELIMITER) { it.name }).apply()
    }

    private companion object {
        const val KEY_ORDER = "block_order"
        const val KEY_HIDDEN = "hidden_blocks"
        const val KEY_DISMISSED = "dismissed_works"
        const val DELIMITER = ","
    }
}
