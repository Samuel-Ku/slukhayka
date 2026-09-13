package com.slukhayka.audiobooks.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Spec-51 (#689) — the local storage of a listener's own collections. */
@Dao
interface ListenerCollectionsDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCollection(collection: ListenerCollectionEntity)

    /**
     * IGNORE is the honest duplicate rule: the composite key makes a repeat a
     * no-op, and the returned rowId (-1) reports it instead of throwing.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: ListenerCollectionItemEntity): Long

    @Query(
        "DELETE FROM listener_collection_items " +
            "WHERE collectionId = :collectionId AND bookId = :bookId"
    )
    suspend fun deleteItem(collectionId: String, bookId: String): Int

    @Query("UPDATE listener_collections SET title = :title WHERE id = :collectionId")
    suspend fun updateTitle(collectionId: String, title: String): Int

    @Query("UPDATE listener_collections SET description = :description WHERE id = :collectionId")
    suspend fun updateDescription(collectionId: String, description: String): Int

    @Query("DELETE FROM listener_collections WHERE id = :collectionId")
    suspend fun deleteCollection(collectionId: String): Int

    @Query("DELETE FROM listener_collection_items WHERE collectionId = :collectionId")
    suspend fun deleteItemsOf(collectionId: String): Int

    @Query("SELECT * FROM listener_collections ORDER BY createdAt")
    suspend fun collections(): List<ListenerCollectionEntity>

    @Query("SELECT * FROM listener_collection_items ORDER BY addedAt")
    suspend fun items(): List<ListenerCollectionItemEntity>
}
