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

/** Shared sync preserves a newer committed whole observation through real Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoomCollectiveSyncOlderAdmissionTest {

    @Test(timeout = 90_000L)
    fun `an older shared page preserves the intervening newer Room block and consumes its cursor`() = runBlocking {
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
                assertTrue(store.activate(initial))
                assertEquals(initial, store.active(key))
                val heldAdmission = HeldAdmission(store, resumeAdmission)
                val consumedCursor = CollectiveBlockCursor(200L, "shared-page-a200")
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

                assertEquals("full newer B remains active after older A admission", committedB, store.active(key))
                assertEquals("older A was consumed but not admitted", 0, applied)
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
