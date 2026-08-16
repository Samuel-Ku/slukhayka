package com.slukhayka.audiobooks.ui.library

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed [ListenPrefs] (wayfinder #62). Persists the
 * user's block order, hidden blocks and dismissed works locally — a taste
 * preference, never synced (see [ListenPrefs]). Every write is synchronous
 * and immediate, so a process death loses nothing.
 */
class ListenPrefsStore(context: Context) : ListenPrefs {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("listen_prefs", Context.MODE_PRIVATE)

    override val order: List<ListenComposer.BlockId>
        get() = prefs.getString(KEY_ORDER, null)
            ?.split(DELIMITER)
            ?.mapNotNull { name -> ListenComposer.BlockId.entries.firstOrNull { it.name == name } }
            .orEmpty()

    override val hiddenBlockIds: Set<ListenComposer.BlockId>
        get() = prefs.getStringSet(KEY_HIDDEN, emptySet())
            .orEmpty()
            .mapNotNull { name -> ListenComposer.BlockId.entries.firstOrNull { it.name == name } }
            .toSet()

    override val dismissedBookIds: Set<String>
        get() = prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty()

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
