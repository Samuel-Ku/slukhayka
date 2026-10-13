package com.slukhayka.audiobooks.data.collective

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #525 — shared mirroring preserves the newest committed observation. */
class CollectiveBlockSyncConcurrentAdmissionTest {

    @Test
    fun `an older shared page cannot replace a newer block committed before admission`() = runBlocking {
        val key = collectiveBlockKey("audiobookmp3", CollectiveBlockKind.NEW_ARRIVALS)
        val initial = CollectiveFeedBlock(
            blockKey = key,
            sourceId = "audiobookmp3",
            kind = CollectiveBlockKind.NEW_ARRIVALS,
            name = "Початкові новинки",
            provenanceUrl = "https://audiobook-mp3.com/uk?observation=initial",
            cards = listOf(CollectiveBlockCard(
                "audiobookmp3", "https://audiobook-mp3.com/uk-audio-initial", "Початкова книга", "Перший автор"
            )),
            fetchedAt = 100L,
            staleAfter = 21_600_100L,
            version = 12L,
            lastAttempt = CollectiveAttempt(100L, CollectiveAttemptStatus.SUCCESS)
        )
        val incomingA = CollectiveFeedBlock(
            blockKey = key,
            sourceId = "audiobookmp3",
            kind = CollectiveBlockKind.NEW_ARRIVALS,
            name = "Старі спільні новинки A",
            provenanceUrl = "https://audiobook-mp3.com/uk?observation=shared-a",
            cards = listOf(CollectiveBlockCard(
                "audiobookmp3", "https://audiobook-mp3.com/uk-audio-older-a", "Стара книга A", "Автор A",
                "https://audiobook-mp3.com/covers/older-a.jpg"
            )),
            fetchedAt = 200L,
            staleAfter = 21_600_200L,
            version = 77L,
            lastAttempt = CollectiveAttempt(200L, CollectiveAttemptStatus.SUCCESS)
        )
        val committedB = CollectiveFeedBlock(
            blockKey = key,
            sourceId = "audiobookmp3",
            kind = CollectiveBlockKind.NEW_ARRIVALS,
            name = "Нові збережені новинки B",
            provenanceUrl = "https://audiobook-mp3.com/uk?observation=committed-b",
            cards = listOf(
                CollectiveBlockCard(
                    "audiobookmp3", "https://audiobook-mp3.com/uk-audio-newer-b-second", "Друга книга B", "Другий автор B",
                    "https://audiobook-mp3.com/covers/newer-b-second.jpg"
                ),
                CollectiveBlockCard(
                    "audiobookmp3", "https://audiobook-mp3.com/uk-audio-newer-b-first", "Перша книга B", "Перший автор B",
                    "https://audiobook-mp3.com/covers/newer-b-first.jpg"
                )
            ),
            fetchedAt = 300L,
            staleAfter = 21_600_300L,
            version = 4L,
            lastAttempt = CollectiveAttempt(300L, CollectiveAttemptStatus.SUCCESS)
        )
        val backing = InMemoryCollectiveFeedBlockStore()
        assertTrue(backing.activate(initial))
        val heldAdmission = HeldAdmission(backing)
        val consumedCursor = CollectiveBlockCursor(200L, "shared-page-a")
        val cursorStore = InMemoryCollectiveBlockSyncCursorStore(CollectiveBlockCursor(50L, "before-a"))
        val remote = object : CollectiveBlockStore {
            override suspend fun getBlocksPage(after: CollectiveBlockCursor?, limit: Int) =
                CollectiveBlockPage(listOf(incomingA), consumedCursor)
        }
        val syncing = async { CollectiveBlockSync(remote, heldAdmission, cursorStore).syncOnce(pageSize = 50) }
        var primaryFailure: Throwable? = null
        try {
            withTimeout(5_000L) {
                heldAdmission.entered.await()
                assertEquals(initial, backing.active(key))
                assertTrue(backing.activate(committedB))
                assertEquals(committedB, backing.active(key))
                heldAdmission.resume.complete(Unit)

                val applied = syncing.await()

                assertEquals(consumedCursor, cursorStore.load())
                assertEquals(committedB, backing.active(key))
                assertEquals(0, applied)
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            heldAdmission.resume.complete(Unit)
            withContext(NonCancellable) {
                try {
                    withTimeout(5_000L) { syncing.cancelAndJoin() }
                } catch (cleanupFailure: Throwable) {
                    val primary = primaryFailure
                    if (primary != null) primary.addSuppressed(cleanupFailure) else throw cleanupFailure
                }
            }
        }
    }

    /** Delays admission; all block reads and writes use the real backing store. */
    private class HeldAdmission(
        private val backing: InMemoryCollectiveFeedBlockStore
    ) : CollectiveFeedBlockStore by backing {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()

        override suspend fun activate(block: CollectiveFeedBlock): Boolean {
            entered.complete(Unit)
            resume.await()
            return backing.activate(block)
        }

        override suspend fun activateIfNewer(block: CollectiveFeedBlock): Boolean {
            entered.complete(Unit)
            resume.await()
            return backing.activateIfNewer(block)
        }
    }
}
