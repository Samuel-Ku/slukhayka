package com.slukhayka.audiobooks.data.collective

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.FeedSnapshotEntity
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #525: one real Room observer keeps each complete last-good block independently. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CollectiveOverviewBlocksRoomLastGoodTest {
    @Test(timeout = 90_000L)
    fun brokenUpdatesRetainCompleteBlocksPerKeyUntilAValidReplacementArrives() = runBlocking {
        var database: AudiobookDatabase? = null
        var collecting: Job? = null
        val received = Channel<List<CollectiveFeedBlock>>(Channel.UNLIMITED)
        val emissions = CopyOnWriteArrayList<List<CollectiveFeedBlock>>()
        var bodyFailure: Throwable? = null
        try {
            withTimeout(60_000L) {
                val context = ApplicationProvider.getApplicationContext<Application>()
                assertEquals(Application::class.java, context.javaClass)
                val room = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java).build()
                database = room
                val dao = room.audiobookDao()
                val store = RoomCollectiveFeedBlockStore(dao)
                val reader = CollectiveOverviewBlocks(store)
                val sources = listOf("sluhayua", "soundbooks")
                val a = CollectiveFeedBlock(
                    blockKey = "sluhayua|RECOMMENDATIONS", sourceId = "sluhayua",
                    kind = CollectiveBlockKind.RECOMMENDATIONS, name = "Рекомендації A",
                    provenanceUrl = "https://sluhay.com.ua/room-observer-a",
                    cards = listOf(
                        CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/kobzar", "Кобзар", "Тарас Шевченко", "https://fixture.invalid/a1.jpg"),
                        CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/eneida", "Енеїда", "Іван Котляревський", "https://fixture.invalid/a2.jpg")
                    ),
                    fetchedAt = 1_000L, staleAfter = 86_401_000L, version = 1L,
                    lastAttempt = CollectiveAttempt(1_000L, CollectiveAttemptStatus.SUCCESS)
                )
                val c = CollectiveFeedBlock(
                    blockKey = "soundbooks|NEW_ARRIVALS", sourceId = "soundbooks",
                    kind = CollectiveBlockKind.NEW_ARRIVALS, name = "Новинки C",
                    provenanceUrl = "https://sound-books.net/room-observer-c",
                    cards = listOf(
                        CollectiveBlockCard("soundbooks", "https://sound-books.net/zakhar-berkut", "Захар Беркут", "Іван Франко", "https://fixture.invalid/c1.jpg"),
                        CollectiveBlockCard("soundbooks", "https://sound-books.net/lisova-pisnia", "Лісова пісня", "Леся Українка", "https://fixture.invalid/c2.jpg")
                    ),
                    fetchedAt = 1_500L, staleAfter = 21_601_500L, version = 1L,
                    lastAttempt = CollectiveAttempt(1_500L, CollectiveAttemptStatus.SUCCESS)
                )
                val failedA = a.copy(lastAttempt = CollectiveAttempt(2_000L, CollectiveAttemptStatus.TIMEOUT))
                // A second distinct healthy commit after its first acknowledgement forces the next
                // sequential A read to occur while the same fault is still persisted.
                val c1 = c.copy(cards = listOf(c.cards[1], c.cards[0]), fetchedAt = 2_500L,
                    staleAfter = 21_602_500L, version = 2L, lastAttempt = CollectiveAttempt(2_500L, CollectiveAttemptStatus.SUCCESS))
                val c1Next = c1.copy(fetchedAt = 2_750L, staleAfter = 21_602_750L, version = 3L,
                    lastAttempt = CollectiveAttempt(2_750L, CollectiveAttemptStatus.SUCCESS))
                val c2 = c.copy(fetchedAt = 3_500L, staleAfter = 21_603_500L, version = 4L,
                    lastAttempt = CollectiveAttempt(3_500L, CollectiveAttemptStatus.SUCCESS))
                val c2Next = c2.copy(fetchedAt = 3_750L, staleAfter = 21_603_750L, version = 5L,
                    lastAttempt = CollectiveAttempt(3_750L, CollectiveAttemptStatus.SUCCESS))
                val restoredA = a.copy(fetchedAt = 4_000L, staleAfter = 86_404_000L, version = 2L,
                    lastAttempt = CollectiveAttempt(4_000L, CollectiveAttemptStatus.SUCCESS))
                val c3 = c1.copy(fetchedAt = 5_000L, staleAfter = 21_605_000L, version = 6L,
                    lastAttempt = CollectiveAttempt(5_000L, CollectiveAttemptStatus.SUCCESS))
                val c3Next = c3.copy(fetchedAt = 5_250L, staleAfter = 21_605_250L, version = 7L,
                    lastAttempt = CollectiveAttempt(5_250L, CollectiveAttemptStatus.SUCCESS))
                val b = CollectiveFeedBlock(
                    blockKey = "sluhayua|RECOMMENDATIONS", sourceId = "sluhayua",
                    kind = CollectiveBlockKind.RECOMMENDATIONS, name = "Рекомендації B",
                    provenanceUrl = "https://sluhay.com.ua/room-observer-b",
                    cards = listOf(
                        CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/tini", "Тіні забутих предків", "Михайло Коцюбинський", "https://fixture.invalid/b1.jpg"),
                        CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/kaminnyi-khrest", "Камінний хрест", "Василь Стефаник", "https://fixture.invalid/b2.jpg")
                    ),
                    fetchedAt = 6_000L, staleAfter = 86_406_000L, version = 4L,
                    lastAttempt = CollectiveAttempt(6_000L, CollectiveAttemptStatus.SUCCESS)
                )
                val completeStates = listOf(listOf(a, c), listOf(failedA, c), listOf(failedA, c1),
                    listOf(failedA, c1Next), listOf(failedA, c2), listOf(failedA, c2Next),
                    listOf(restoredA, c2Next), listOf(restoredA, c3), listOf(restoredA, c3Next), listOf(b, c3Next))
                suspend fun expectEmission(label: String, expected: List<CollectiveFeedBlock>) {
                    assertEquals(label, expected, withTimeout(10_000L) { received.receive() })
                    assertTrue("the same public collector remains alive", requireNotNull(collecting).isActive)
                    assertTrue("every observed block is a complete independent expected payload",
                        emissions.all { it in completeStates })
                }

                // Persist C first: source/kind order must come from the public reader, not insertion order.
                assertTrue(store.activate(c))
                assertTrue(store.activate(a))
                assertEquals(listOf(a, c), reader.read(sources))
                collecting = launch(Dispatchers.Default) {
                    reader.observe(sources).collect { snapshot ->
                        emissions += snapshot
                        received.send(snapshot)
                    }
                }
                expectEmission("initial real Room subscription acknowledgement", listOf(a, c))

                store.recordAttempt(a.blockKey, CollectiveAttempt(2_000L, CollectiveAttemptStatus.TIMEOUT))
                assertEquals(failedA, store.active(a.blockKey))
                expectEmission("a failed attempt keeps both complete payloads", listOf(failedA, c))

                val malformedWhole = FeedSnapshotEntity("sluhayua", "collective-recommendations", "", 2_250L, "{not a block}")
                dao.upsertFeedSnapshot(malformedWhole)
                assertEquals(malformedWhole, dao.getFeedSnapshot("sluhayua", "collective-recommendations"))
                assertNull(store.active(a.blockKey))
                assertEquals(listOf(c), reader.read(sources))
                assertTrue(store.activate(c1))
                expectEmission("first healthy acknowledgement while whole A remains broken", listOf(failedA, c1))
                assertEquals(malformedWhole, dao.getFeedSnapshot("sluhayua", "collective-recommendations"))
                assertTrue(store.activate(c1Next))
                expectEmission("second healthy acknowledgement retains complete broken-A last good", listOf(failedA, c1Next))

                dao.clearFeedSnapshots("sluhayua", "collective-recommendations")
                assertNull(dao.getFeedSnapshot("sluhayua", "collective-recommendations"))
                assertEquals(listOf(c1Next), reader.read(sources))
                assertTrue(store.activate(c2))
                expectEmission("first healthy acknowledgement while A remains missing", listOf(failedA, c2))
                assertNull(dao.getFeedSnapshot("sluhayua", "collective-recommendations"))
                assertTrue(store.activate(c2Next))
                expectEmission("second healthy acknowledgement retains missing-A complete last good", listOf(failedA, c2Next))

                assertTrue(store.activate(restoredA))
                assertEquals(listOf(restoredA, c2Next), reader.read(sources))
                expectEmission("complete A restoration establishes the next literal baseline", listOf(restoredA, c2Next))

                // A syntactically valid newer document has one malformed card and one intact card.
                // The expected retained block comes from restoredA, never from decoding this input.
                val newerDocument = restoredA.copy(version = 3L, fetchedAt = 4_500L, staleAfter = 86_404_500L,
                    lastAttempt = CollectiveAttempt(4_500L, CollectiveAttemptStatus.SUCCESS))
                val encoded = CollectiveFeedBlockCodec.encode(newerDocument)
                val titleField = "\"title\":\"Кобзар\""
                assertEquals("the controlled defect removes exactly one required card field", 1,
                    encoded.split(titleField).size - 1)
                val partialDocument = encoded.replaceFirst(titleField, "\"missingTitle\":\"Кобзар\"")
                val malformedPartial = FeedSnapshotEntity("sluhayua", "collective-recommendations", "", 4_500L, partialDocument)
                dao.upsertFeedSnapshot(malformedPartial)
                assertEquals(malformedPartial, dao.getFeedSnapshot("sluhayua", "collective-recommendations"))
                assertTrue(store.activate(c3))
                assertEquals(c3, store.active(c.blockKey))
                // A first healthy acknowledgement can mix an earlier A read with a later C read.
                // Preserve every emission without rejecting partial A before the decisive second gate.
                val withHealthyCommit = withTimeout(10_000L) {
                    var snapshot = received.receive()
                    while (c3 !in snapshot) snapshot = received.receive()
                    snapshot
                }
                assertTrue("the first healthy commit was acknowledged by the same live collector",
                    requireNotNull(collecting).isActive)
                assertTrue(c3 in withHealthyCommit)
                assertEquals(malformedPartial, dao.getFeedSnapshot("sluhayua", "collective-recommendations"))
                // Commit only AFTER that acknowledgement, with A still faulted. This next loop's
                // A read cannot precede the already committed malformed document.
                assertTrue(store.activate(c3Next))
                assertEquals(c3Next, store.active(c.blockKey))
                val withSecondHealthyCommit = withTimeout(10_000L) {
                    var snapshot = received.receive()
                    while (c3Next !in snapshot) snapshot = received.receive()
                    snapshot
                }
                assertEquals("S2_ROOM_LAST_GOOD_PARTIAL_FIRST: a malformed card must retain the complete previous block",
                    listOf(restoredA, c3Next), withSecondHealthyCommit)
                assertTrue("no partial or foreign payload may have escaped before the healthy commit",
                    emissions.all { it in completeStates })
                assertTrue(requireNotNull(collecting).isActive)

                // This unchanged tail gets credit only if the decisive broken-document oracle passes.
                assertTrue(store.activate(b))
                assertEquals(listOf(b, c3Next), reader.read(sources))
                expectEmission("valid B replaces A completely in the same collector and card order", listOf(b, c3Next))
                withTimeout(10_000L) { requireNotNull(collecting).cancelAndJoin() }
                assertTrue(requireNotNull(collecting).isCancelled)
                assertEquals(completeStates, emissions.toList())
            }
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
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
                cleanup { withTimeout(15_000L) { collecting?.cancelAndJoin() } }
                cleanup { received.close() }
                cleanup { database?.close() }
            }
            if (bodyFailure == null) cleanupFailure?.let { throw it }
        }
    }
}
