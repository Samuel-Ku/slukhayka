package com.slukhayka.audiobooks.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * #916 — the local storage of the social layer's two facts: the state of a
 * friendship request and the blocks (§5, §2).
 *
 * Deliberately narrow: it stores what the listener's own device knows, and
 * hands the rows back verbatim. Which state counts as a friend, and which
 * direction hides whom, is the pure policy's call
 * ([com.slukhayka.audiobooks.data.social.SocialStore] does the mapping) — the
 * DAO never decides a social outcome.
 */
@Dao
interface SocialDao {

    // --- friendship state -------------------------------------------------

    @Query("SELECT * FROM friendship_states ORDER BY updatedAt DESC")
    fun observeFriendshipStates(): Flow<List<FriendshipStateEntity>>

    @Query("SELECT * FROM friendship_states ORDER BY updatedAt DESC")
    suspend fun friendshipStates(): List<FriendshipStateEntity>

    /** One row per peer: a repeated write replaces the previous state. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFriendshipState(row: FriendshipStateEntity)

    @Query("DELETE FROM friendship_states WHERE pseudonym = :pseudonym")
    suspend fun deleteFriendshipState(pseudonym: String): Int

    // --- blocks -----------------------------------------------------------

    @Query("SELECT * FROM social_blocks ORDER BY blockedAt DESC")
    fun observeBlocks(): Flow<List<BlockEntity>>

    @Query("SELECT * FROM social_blocks ORDER BY blockedAt DESC")
    suspend fun blocks(): List<BlockEntity>

    /**
     * IGNORE is the honest duplicate rule: blocking an already-blocked
     * pseudonym is a no-op, reported as -1 instead of a throw.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBlock(row: BlockEntity): Long

    /** Scoped to one direction: unblocking me is never unblocking them. */
    @Query("DELETE FROM social_blocks WHERE pseudonym = :pseudonym AND direction = :direction")
    suspend fun deleteBlock(pseudonym: String, direction: String): Int
}
