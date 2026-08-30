package com.slukhayka.audiobooks.data.personbookmarks

import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.db.PersonRole

/** #404 wire contract. Unknown remote fields and absent server stamps are misses. */
data class RemotePersonBookmark(
    val kind: String,
    val personId: String,
    val displayName: String,
    val notifyEnabled: Boolean,
    val updatedAtServerMs: Long
)

interface PersonBookmarksSyncStore {
    suspend fun fetch(uid: String): List<Map<String, Any>>
    suspend fun write(documentId: String, fields: Map<String, Any>): Boolean
    suspend fun delete(documentId: String): Boolean

    suspend fun pull(uid: String): List<RemotePersonBookmark> = runCatching { fetch(uid) }
        .getOrDefault(emptyList()).mapNotNull(PersonBookmarksSyncCodec::fromDocument)

    suspend fun push(uid: String, bookmark: PersonBookmarkEntity): Boolean = runCatching {
        write(PersonBookmarksSyncCodec.documentId(uid, bookmark.kind, bookmark.id),
            PersonBookmarksSyncCodec.toDocument(uid, bookmark))
    }.getOrDefault(false)

    suspend fun remove(uid: String, kind: String, personId: String): Boolean = runCatching {
        delete(PersonBookmarksSyncCodec.documentId(uid, kind, personId))
    }.getOrDefault(false)
}

object PersonBookmarksSyncCodec {
    const val COLLECTION = "person_bookmarks"
    fun documentId(uid: String, kind: String, personId: String) = "${uid}_${kind}_$personId"
    fun toDocument(uid: String, bookmark: PersonBookmarkEntity): Map<String, Any> = mapOf(
        "uid" to uid, "kind" to bookmark.kind, "personId" to bookmark.id,
        "displayName" to bookmark.displayName, "notifyEnabled" to bookmark.notifyEnabled
    )
    fun fromDocument(document: Map<String, Any>): RemotePersonBookmark? {
        val allowed = setOf("uid", "kind", "personId", "displayName", "notifyEnabled", "updatedAt")
        if (document.keys.any { it !in allowed }) return null
        val kind = document["kind"] as? String ?: return null
        if (PersonRole.fromStorage(kind) == null) return null
        val id = document["personId"] as? String ?: return null
        val name = document["displayName"] as? String ?: return null
        val notify = document["notifyEnabled"] as? Boolean ?: return null
        val updated = document["updatedAt"] as? Long ?: return null
        if (id.isBlank() || id.length > 300 || name.isBlank() || name.length > 200 || updated <= 0) return null
        return RemotePersonBookmark(kind, id, name, notify, updated)
    }
}
