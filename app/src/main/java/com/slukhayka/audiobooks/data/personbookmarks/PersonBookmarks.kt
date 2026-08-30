package com.slukhayka.audiobooks.data.personbookmarks

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkKey
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.authors.AuthorIdentity
import com.slukhayka.audiobooks.data.facets.FacetIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class PersonIdentity private constructor(
    val role: PersonRole,
    val id: String,
    val displayName: String,
    val normalizedName: String
) {
    companion object {
        fun from(role: PersonRole, rawName: String): PersonIdentity {
            val canonical = AuthorIdentity.fromWorkName(rawName)
            return PersonIdentity(
                role = role,
                id = FacetIdentity.boundedId(role.idPrefix, canonical.normalizedName),
                displayName = canonical.displayName,
                normalizedName = canonical.normalizedName
            )
        }
    }
}

/**
 * #399 — the deep module that owns person bookmarks (author / narrator).
 *
 * Public API:
 *  - Flows: [bookmarkedAuthors], [bookmarkedNarrators], [counts]
 *  - Suspend: [toggle], [setNotifyEnabled], [markSeen]
 *
 * ADR-0008: screens read the Flows directly; MainViewModel only composes.
 * The module constructs with the DAO only — no repository graph, no network,
 * no Context. Local-first: works without network / without Firebase keys;
 * sync degrades to no-op (#404 will add the transport seam).
 */
class PersonBookmarks(
    private val dao: AudiobookDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    // --- Deterministic person identity (shared normaliser) ----------------

    /**
     * Deterministic author id — the same `AuthorIdentity.fromWorkName` key.
     * `boundedId("author", normalizedName)` so `author:шевченко` ≠
     * `narrator:шевченко` by construction.
     */
    fun authorId(rawName: String): String = identity(PersonRole.AUTHOR, rawName).id

    /**
     * Deterministic narrator id — the same normaliser as the author but
     * prefixed with `"narrator"` so two people with the identical name but
     * different roles never share an id.
     */
    fun narratorId(rawName: String): String = identity(PersonRole.NARRATOR, rawName).id

    fun identity(role: PersonRole, rawName: String): PersonIdentity = PersonIdentity.from(role, rawName)

    // --- Flows (read directly by screens, ADR-0008) -----------------------

    /** Observe a single person bookmark by kind + id. Null when not bookmarked. */
    fun observePersonBookmark(kind: String, id: String): Flow<PersonBookmarkEntity?> =
        dao.observePersonBookmark(kind, id)

    /** Every bookmarked author, newest first. */
    fun bookmarkedAuthors(): Flow<List<PersonBookmarkEntity>> =
        dao.getPersonBookmarksByKind(PersonRole.AUTHOR.storageValue)

    /** Every bookmarked narrator, newest first. */
    fun bookmarkedNarrators(): Flow<List<PersonBookmarkEntity>> =
        dao.getPersonBookmarksByKind(PersonRole.NARRATOR.storageValue)

    /** Every bookmarked person; consumers keep the role boundary in [PersonIdentity]. */
    fun allBookmarks(): Flow<List<PersonBookmarkEntity>> = dao.getAllPersonBookmarks()

    /** Total bookmark count per kind: {AUTHOR → N, NARRATOR → M}. */
    fun counts(): Flow<Map<PersonRole, Int>> =
        dao.getAllPersonBookmarks().map { bookmarks ->
            bookmarks.mapNotNull { bookmark ->
                PersonRole.fromStorage(bookmark.kind)?.let { it to bookmark }
            }.groupBy({ it.first }, { it.second }).mapValues { it.value.size }
        }

    // --- Actions ----------------------------------------------------------

    /**
     * Toggle a person bookmark. If the person is not yet bookmarked, creates
     * one (with [notifyEnabled] = true). If already bookmarked, removes it.
     *
     * Returns `true` if the person is now bookmarked (added), `false` if
     * removed. The Flows update synchronously since the DAO is the single
     * source of truth.
     */
    suspend fun toggle(
        person: PersonIdentity,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean = withContext(ioDispatcher) {
        dao.togglePersonBookmark(
            PersonBookmarkEntity(
                kind = person.role.storageValue,
                id = person.id,
                displayName = person.displayName,
                normalizedName = person.normalizedName,
                createdAt = nowMs,
                updatedAt = nowMs
            )
        )
    }

    /** Convenience overload for author bookmark toggle. */
    suspend fun toggleAuthor(
        displayName: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        return toggle(identity(PersonRole.AUTHOR, displayName), nowMs)
    }

    /** Convenience overload for narrator bookmark toggle. */
    suspend fun toggleNarrator(
        displayName: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        return toggle(identity(PersonRole.NARRATOR, displayName), nowMs)
    }

    /**
     * Update the per-person notification toggle without removing the bookmark.
     * No-op when the person is not bookmarked.
     */
    suspend fun setNotifyEnabled(
        key: PersonBookmarkKey,
        enabled: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ) = withContext(ioDispatcher) {
        dao.updatePersonBookmarkNotifyEnabled(key.role.storageValue, key.id, enabled, nowMs)
    }

    /**
     * Mark a person's new books as "seen" — resets the new-count badge.
     * Called when the listener opens the person's page or taps the badge.
     * [lastSeenAt] advances to [nowMs]; no-op when the person is not bookmarked.
     */
    suspend fun markSeen(
        key: PersonBookmarkKey,
        nowMs: Long = System.currentTimeMillis()
    ) = withContext(ioDispatcher) {
        dao.updatePersonBookmarkLastSeen(key.role.storageValue, key.id, nowMs, nowMs)
    }

    /** Records the exact aggregate that was shown in a grouped notification. */
    suspend fun markNotified(
        keys: Collection<PersonBookmarkKey>,
        count: Int,
        nowMs: Long = System.currentTimeMillis()
    ) = withContext(ioDispatcher) {
        keys.forEach { key ->
            dao.updatePersonBookmarkLastNotified(key.role.storageValue, key.id, nowMs, count)
        }
    }
}
