package com.slukhayka.audiobooks.data.collective

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Shared sync admits a genuinely newer whole observation through real Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoomCollectiveSyncNewerAdmissionTest {

    @Test(timeout = 90_000L)
    fun `a genuinely newer shared page replaces an intervening Room block regardless of local version`() = runBlocking {
        var ownedDatabase: AudiobookDatabase? = null
        var ownedSync: Deferred<Int>? = null
        var bodyFailure: Throwable? = null
        val resumeAdmission = CompletableDeferred<Unit>()
        try {
            withTimeout(45_000L) {
                val context = ApplicationProvider.getApplicationContext<Application>()
                assertEquals(Application::class.java, context.javaClass)
                val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
                    .build().also { ownedDatabase = it }
                val store = RoomCollectiveFeedBlockStore(database.audiobookDao())
                val key = "audiobookmp3|NEW_ARRIVALS"
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
                    name = "Нові спільні новинки A",
                    provenanceUrl = "https://audiobook-mp3.com/uk?observation=shared-a400",
                    cards = listOf(
                        CollectiveBlockCard(
                            "audiobookmp3", "https://audiobook-mp3.com/uk-audio-newer-a-second", "Друга нова книга A", "Другий автор A",
                            "https://audiobook-mp3.com/covers/newer-a-second.jpg"
                        ),
                        CollectiveBlockCard(
                            "audiobookmp3", "https://audiobook-mp3.com/uk-audio-newer-a-first", "Перша нова книга A", "Перший автор A",
                            "https://audiobook-mp3.com/covers/newer-a-first.jpg"
                        )
                    ),
                    fetchedAt = 400L,
                    staleAfter = 21_600_400L,
                    version = 4L,
                    lastAttempt = CollectiveAttempt(400L, CollectiveAttemptStatus.SUCCESS)
                )
                val committedB = CollectiveFeedBlock(
                    blockKey = key,
                    sourceId = "audiobookmp3",
                    kind = CollectiveBlockKind.NEW_ARRIVALS,
                    name = "Збережені новинки B",
                    provenanceUrl = "https://audiobook-mp3.com/uk?observation=committed-b300",
                    cards = listOf(
                        CollectiveBlockCard(
                            "audiobookmp3", "https://audiobook-mp3.com/uk-audio-older-b-first", "Перша збережена книга B", "Перший автор B",
                            "https://audiobook-mp3.com/covers/older-b-first.jpg"
                        ),
                        CollectiveBlockCard(
                            "audiobookmp3", "https://audiobook-mp3.com/uk-audio-older-b-second", "Друга збережена книга B", "Другий автор B",
                            "https://audiobook-mp3.com/covers/older-b-second.jpg"
                        )
                    ),
                    fetchedAt = 300L,
                    staleAfter = 21_600_300L,
                    version = 77L,
                    lastAttempt = CollectiveAttempt(300L, CollectiveAttemptStatus.SUCCESS)
                )
                assertTrue(store.activate(initial))
                assertEquals(initial, store.active(key))
                val heldAdmission = HeldAdmission(store, resumeAdmission)
                val consumedCursor = CollectiveBlockCursor(400L, "shared-page-a400")
                val cursorStore = InMemoryCollectiveBlockSyncCursorStore(CollectiveBlockCursor(50L, "before-a"))
                val remote = object : CollectiveBlockStore {
                    override suspend fun getBlocksPage(after: CollectiveBlockCursor?, limit: Int) =
                        CollectiveBlockPage(listOf(incomingA), consumedCursor)
                }
                val syncing = async {
                    CollectiveBlockSync(remote, heldAdmission, cursorStore).syncOnce(pageSize = 50)
                }.also { ownedSync = it }
                heldAdmission.entered.await()
                assertEquals(initial, store.active(key))

                assertTrue(store.activate(committedB))
                assertEquals("full B is committed before A reaches Room admission", committedB, store.active(key))
                resumeAdmission.complete(Unit)

                val applied = syncing.await()

                assertEquals("full genuinely newer A is persisted with its observed payload", incomingA, store.active(key))
                assertEquals("one shared block was actually admitted", 1, applied)
                assertEquals("the successfully consumed page advances its public cursor", consumedCursor, cursorStore.load())
            }
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            resumeAdmission.complete(Unit)
            var cleanupFailure: Throwable? = null
            suspend fun cleanup(action: suspend () -> Unit) {
                try {
                    action()
                } catch (failure: Throwable) {
                    val primary = bodyFailure ?: cleanupFailure
                    if (primary == null) cleanupFailure = failure else primary.addSuppressed(failure)
                }
            }
            withContext(NonCancellable) {
                cleanup { withTimeout(10_000L) { ownedSync?.cancelAndJoin() } }
                cleanup { withTimeout(10_000L) { withContext(Dispatchers.IO) { ownedDatabase?.close() } } }
            }
            if (bodyFailure == null) cleanupFailure?.let { throw it }
        }
    }

    /** Holds admission before the real Room transaction; public reads stay live. */
    private class HeldAdmission(
        private val backing: RoomCollectiveFeedBlockStore,
        private val resume: CompletableDeferred<Unit>
    ) : CollectiveFeedBlockStore by backing {
        val entered = CompletableDeferred<Unit>()

        override suspend fun activateIfNewer(block: CollectiveFeedBlock): Boolean {
            entered.complete(Unit)
            resume.await()
            return backing.activateIfNewer(block)
        }
    }
}
