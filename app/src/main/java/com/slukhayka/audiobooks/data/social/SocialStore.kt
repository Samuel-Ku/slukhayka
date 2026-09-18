package com.slukhayka.audiobooks.data.social

import com.slukhayka.audiobooks.data.db.BlockEntity
import com.slukhayka.audiobooks.data.db.FriendshipStateEntity
import com.slukhayka.audiobooks.data.db.SocialDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * #916 — the social layer's locally stored state, shaped for the «Друзі» feed.
 *
 * [friendsNow] is the accepted set at the moment of reading (§3: the audience
 * is a rule applied *now*, not a snapshot), and [blocks] carries both directions
 * of §2 so the feed can compose [BlockPolicy]. Nothing here is invented: an
 * empty store yields an empty snapshot, and the screen keeps its honest empty
 * state (§6.4).
 */
data class SocialSnapshot(
    /** Accepted, unblocked pseudonyms — the feed's `friendsNow`. */
    val friendsNow: Set<String> = emptySet(),
    /** Peers whose request this listener has not answered yet. */
    val incomingRequests: Set<String> = emptySet(),
    /** Peers this listener has asked and is waiting on. */
    val outgoingRequests: Set<String> = emptySet(),
    /** Both block directions, ready for [BlockPolicy]. */
    val blocks: BlockState = BlockState()
)

/**
 * #916 — the deep module over [SocialDao]: the local half of §5.
 *
 * Public API:
 *  - [snapshot] — observe the whole state the feed reads (ADR-0008: the screen
 *    reads the flow, the ViewModel only composes);
 *  - [read] — the same state once, for tests and one-shot reads;
 *  - [setState], [unfriend], [block], [recordBlockedBy], [unblock] — writes.
 *
 * Local-first: constructed from the DAO only, no network, no Firebase, no
 * Context. The shared base's accepted link (and the public block needed to
 * refuse a request, §7) has no transport here yet — when it lands it becomes
 * another writer of these same two facts, not a second store.
 */
class SocialStore(
    private val dao: SocialDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** Observe the feed's whole state; the two facts are read atomically. */
    fun snapshot(): Flow<SocialSnapshot> =
        combine(dao.observeFriendshipStates(), dao.observeBlocks()) { states, blocks ->
            snapshotOf(states, blocks)
        }.flowOn(ioDispatcher)

    /** The state once — the same mapping [snapshot] applies. */
    suspend fun read(): SocialSnapshot = withContext(ioDispatcher) {
        snapshotOf(dao.friendshipStates(), dao.blocks())
    }

    // --- friendship -------------------------------------------------------

    /**
     * Records the state of one friendship fact. A blank pseudonym is refused:
     * §5 makes the pseudonym the whole identity of the fact, so a blank one is
     * not a person and must never become a friend (§6.4).
     */
    suspend fun setState(pseudonym: String, state: FriendshipState): Boolean {
        if (pseudonym.isBlank()) return false
        return withContext(ioDispatcher) {
            dao.upsertFriendshipState(
                FriendshipStateEntity(pseudonym = pseudonym, state = state.name, updatedAt = clock())
            )
            true
        }
    }

    /**
     * §3 — either side can end the friendship alone. Only the local fact is
     * removed: posts are neither deleted nor rewritten, and nothing here makes
     * anything visible again.
     */
    suspend fun unfriend(pseudonym: String): Boolean = withContext(ioDispatcher) {
        dao.deleteFriendshipState(pseudonym) > 0
    }

    // --- blocks -----------------------------------------------------------

    /**
     * §2 — this listener blocks [pseudonym]. The block is stored one-sided,
     * and it cancels the friendship: what was cancelled stays cancelled after a
     * later unblock until a NEW explicit request is accepted.
     */
    suspend fun block(pseudonym: String): Boolean {
        if (pseudonym.isBlank()) return false
        return withContext(ioDispatcher) {
            dao.insertBlock(BlockEntity(pseudonym, BlockDirection.BY_ME.name, clock()))
            dao.deleteFriendshipState(pseudonym)
            true
        }
    }

    /**
     * §2 — records that [pseudonym] blocked this listener. This is the local
     * mirror of the one public block the shared base may carry (§7, still open);
     * it hides the same way as [block], but it does NOT cancel a friendship the
     * way an action by this listener does — that pair fact is the other side's
     * to cancel, and inventing a local cancellation would be fiction.
     */
    suspend fun recordBlockedBy(pseudonym: String): Boolean {
        if (pseudonym.isBlank()) return false
        return withContext(ioDispatcher) {
            dao.insertBlock(BlockEntity(pseudonym, BlockDirection.BY_OTHER.name, clock()))
            true
        }
    }

    /**
     * §2 — removes THIS listener's block only. It never touches the other
     * direction and never re-creates the friendship.
     */
    suspend fun unblock(pseudonym: String): Boolean = withContext(ioDispatcher) {
        dao.deleteBlock(pseudonym, BlockDirection.BY_ME.name) > 0
    }

    // --- mapping ----------------------------------------------------------

    private fun snapshotOf(
        states: List<FriendshipStateEntity>,
        blockRows: List<BlockEntity>
    ): SocialSnapshot {
        val decoded = states.mapNotNull { row ->
            // §5 — the pseudonym IS the identity of the fact. A blank one is a
            // corrupt or unresolved row, not a person: it is dropped rather
            // than admitted (the #898 blank-pseudonym leak, at the store edge).
            if (row.pseudonym.isBlank()) return@mapNotNull null
            // Strict on purpose: an unknown state name is not guessed into the
            // nearest one (ADR-0014), it is simply not a fact this build knows.
            val state = decodeOrNull<FriendshipState>(row.state) ?: return@mapNotNull null
            row.pseudonym to state
        }

        val blocks = BlockState(
            blocked = blockRows
                .filter { it.direction == BlockDirection.BY_ME.name && it.pseudonym.isNotBlank() }
                .map { it.pseudonym }
                .toSet(),
            blockedBy = blockRows
                .filter { it.direction == BlockDirection.BY_OTHER.name && it.pseudonym.isNotBlank() }
                .map { it.pseudonym }
                .toSet()
        )

        val accepted = decoded.filter { it.second == FriendshipState.ACCEPTED }.map { it.first }.toSet()

        return SocialSnapshot(
            // §2 — a block cancels the friendship; the defensive subtraction
            // also keeps a row written by the future shared-base sync honest.
            friendsNow = accepted - blocks.blocked - blocks.blockedBy,
            incomingRequests = decoded.filter { it.second == FriendshipState.INCOMING }.map { it.first }.toSet(),
            outgoingRequests = decoded.filter { it.second == FriendshipState.OUTGOING }.map { it.first }.toSet(),
            blocks = blocks
        )
    }

    private inline fun <reified T : Enum<T>> decodeOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }
}
