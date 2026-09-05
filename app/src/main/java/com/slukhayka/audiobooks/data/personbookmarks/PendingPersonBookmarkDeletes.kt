package com.slukhayka.audiobooks.data.personbookmarks

import android.content.Context
import com.slukhayka.audiobooks.data.db.PersonRole

/** Durable local-first queue for explicit bookmark removals (#404). */
interface PendingPersonBookmarkDeletes {
    fun keys(): Set<Pair<String, String>>
    fun add(kind: String, personId: String)
    fun remove(kind: String, personId: String)
}

object NoopPendingPersonBookmarkDeletes : PendingPersonBookmarkDeletes {
    override fun keys(): Set<Pair<String, String>> = emptySet()
    override fun add(kind: String, personId: String) = Unit
    override fun remove(kind: String, personId: String) = Unit
}

class SharedPreferencesPendingPersonBookmarkDeletes(context: Context) : PendingPersonBookmarkDeletes {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun keys(): Set<Pair<String, String>> = prefs.getStringSet(KEYS, emptySet())
        .orEmpty().mapNotNull { encoded ->
            val split = encoded.indexOf(SEPARATOR)
            if (split <= 0) return@mapNotNull null
            val kind = encoded.take(split)
            val id = encoded.drop(split + 1)
            (PersonRole.fromStorage(kind) != null && id.isNotBlank()).takeIf { it }?.let { kind to id }
        }.toSet()

    override fun add(kind: String, personId: String) = update { it + encode(kind, personId) }

    override fun remove(kind: String, personId: String) = update { it - encode(kind, personId) }

    private fun update(transform: (Set<String>) -> Set<String>) {
        prefs.edit().putStringSet(KEYS, transform(prefs.getStringSet(KEYS, emptySet()).orEmpty())).apply()
    }

    private fun encode(kind: String, id: String) = "$kind$SEPARATOR$id"

    private companion object {
        const val PREFS_NAME = "person_bookmark_pending_deletes"
        const val KEYS = "keys"
        const val SEPARATOR = "|"
    }
}
