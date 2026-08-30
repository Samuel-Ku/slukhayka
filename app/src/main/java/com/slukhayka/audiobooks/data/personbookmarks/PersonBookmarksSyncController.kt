package com.slukhayka.audiobooks.data.personbookmarks

import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.identity.ListenerIdentity
import com.slukhayka.audiobooks.data.identity.LocalOnlyIdentity
import kotlinx.coroutines.flow.first

/** Local-first best-effort #404 LWW pass. Pull never infers deletion. */
class PersonBookmarksSyncController(
    private val bookmarks: PersonBookmarks,
    private val identity: ListenerIdentity,
    private val store: PersonBookmarksSyncStore?
) {
    suspend fun sync() {
        val remote = store ?: return
        val uid = runCatching { identity.ensure().uid }.getOrNull()
            ?.takeUnless { it.startsWith(LocalOnlyIdentity.LOCAL_UID_PREFIX) } ?: return
        val local = bookmarks.allBookmarks().first()
        val remoteRows = remote.pull(uid)
        val remoteByKey = remoteRows.associateBy { it.kind to it.personId }
        for (row in remoteRows) {
            val existing = local.firstOrNull { it.kind == row.kind && it.id == row.personId }
            if (existing == null || row.updatedAtServerMs > existing.updatedAt) {
                bookmarks.upsertRemote(PersonBookmarkEntity(
                    kind = row.kind, id = row.personId, displayName = row.displayName,
                    normalizedName = bookmarks.identity(com.slukhayka.audiobooks.data.db.PersonRole.fromStorage(row.kind)!!, row.displayName).normalizedName,
                    createdAt = existing?.createdAt ?: row.updatedAtServerMs,
                    notifyEnabled = row.notifyEnabled, updatedAt = row.updatedAtServerMs
                ))
            }
        }
        // Explicit local rows are queued by Firestore even while offline. A
        // remote row with a newer server stamp wins on the next pass.
        bookmarks.allBookmarks().first().forEach { bookmark ->
            if (remoteByKey[bookmark.kind to bookmark.id]?.updatedAtServerMs ?: Long.MIN_VALUE <= bookmark.updatedAt) {
                remote.push(uid, bookmark)
            }
        }
    }
}
