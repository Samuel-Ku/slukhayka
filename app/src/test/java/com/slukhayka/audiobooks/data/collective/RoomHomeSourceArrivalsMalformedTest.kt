package com.slukhayka.audiobooks.data.collective

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.FeedSnapshotPolicy
import com.slukhayka.audiobooks.data.catalog.FeedSnapshotStore
import com.slukhayka.audiobooks.data.catalog.PersistedFeedSnapshot
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.privacy.NetworkRoute
import com.slukhayka.audiobooks.data.privacy.PrivacyPrefs
import com.slukhayka.audiobooks.data.privacy.RouteMode
import com.slukhayka.audiobooks.data.privacy.RouteResolution
import com.slukhayka.audiobooks.data.privacy.TransportPrivacy
import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.data.source.SluhayuaAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceGateProvider
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Public live-feed producer into its active block; not App/shared-backend/device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoomHomeSourceArrivalsMalformedTest {
    @Test(timeout = 60_000L)
    fun `a malformed nonempty Home arrivals response preserves last good block and records parse failure`() = runBlocking {
        var ownedDatabase: AudiobookDatabase? = null
        var ownedExecutor: ExecutorService? = null
        var ownedRelay: HttpServer? = null
        var ownedRelayBase: String? = null
        var primaryFailure: Throwable? = null
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            assertEquals(Application::class.java, context.javaClass)
            assertNull("no App-installed gate can hide source requests", SourceGateProvider.current)
            assertEquals(NetworkRoute.Direct, TransportPrivacy.current())
            val db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java).build()
                .also { ownedDatabase = it }
            val requests = CopyOnWriteArrayList<String>()
            val executor = Executors.newSingleThreadExecutor().also { ownedExecutor = it }
            val relay = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 4).also { ownedRelay = it }
            relay.executor = executor
            val feedUrl = "https://sluhay.com.ua/find/allcards?sort=time&order=desc&page=1"
            val liveJson = """{"cards":[{}]}"""
            relay.createContext("/") { exchange ->
                try {
                    val target = exchange.requestURI.rawQuery.orEmpty().split('&')
                        .singleOrNull { it.startsWith("url=") }?.substringAfter("url=")
                        ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }.orEmpty()
                    requests += target
                    val body = if (requests.size <= 4 && target == feedUrl) {
                        liveJson.toByteArray(StandardCharsets.UTF_8)
                    } else ByteArray(0)
                    exchange.sendResponseHeaders(if (body.isNotEmpty()) 200 else 404, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                } finally { exchange.close() }
            }
            relay.start()
            val relayBase = "http://127.0.0.1:${relay.address.port}".also { ownedRelayBase = it }
            assertEquals(RouteResolution.Ok(NetworkRoute.Relay(relayBase)),
                TransportPrivacy.install(PrivacyPrefs(routeMode = RouteMode.RELAY,
                    proxyAddress = relayBase, dohEnabled = false)))
            val adapter = SluhayuaAdapter(HttpFetcher())
            val rawBooks = listOf(
                SourceBook("", "", url = "https://sluhay.com.ua/:", sourceId = "sluhayua")
            )
            val returnedBooks = listOf(
                SourceBook("", "", url = "https://sluhay.com.ua/:", sourceId = "sluhayua", language = "uk")
            )
            val now = 1_700_000_000_000L
            val dao = db.audiobookDao()
            val snapshots = FeedSnapshotStore(dao) { now }
            val blocks = RoomCollectiveFeedBlockStore(dao)
            val key = "sluhayua|NEW_ARRIVALS"
            assertNull(snapshots.snapshot("sluhayua", FeedSnapshotPolicy.FEED_NEW_ARRIVALS))
            assertNull(blocks.active(key))
            val seededGood = CollectiveFeedBlock(
                "sluhayua|NEW_ARRIVALS", "sluhayua", CollectiveBlockKind.NEW_ARRIVALS,
                "Збережені новинки G", "https://sluhay.com.ua/find/allcards?observed=last-good-g",
                listOf(
                    CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/81:last-good-first", "Раніша перша", "Перший автор G", "https://sluhay.com.ua/covers/last-good-first.jpg"),
                    CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/82:last-good-second", "Раніша друга", "Другий автор G", "https://sluhay.com.ua/covers/last-good-second.jpg")
                ), 1_699_996_400_000L, 1_700_018_000_000L, 7L,
                CollectiveAttempt(1_699_996_400_000L, CollectiveAttemptStatus.SUCCESS)
            )
            assertTrue("real Room public store accepts the independent full last-good seed", blocks.activate(seededGood))
            assertEquals("full last-good G is persisted before the live response", seededGood, blocks.active(key))
            assertNull("collective seed must not prewarm the ordinary source feed", snapshots.snapshot("sluhayua", FeedSnapshotPolicy.FEED_NEW_ARRIVALS))
            assertEquals(0, dao.countWorks())
            assertEquals(0, dao.countLibraryEntries())
            val observations = AtomicInteger()
            val published = CopyOnWriteArrayList<CollectiveFeedBlock>()
            val coordinator = CollectiveFeedRefresh(
                blocks, InMemoryCollectiveRefreshLease(),
                fetch = { error("received arrivals must not start another source fetch") },
                clock = { now }, onActivated = { published += it }
            )
            lateinit var catalog: SourceCatalog
            catalog = SourceCatalog(
                dao, listOf(adapter), LibraryImport(dao, context, listOf(adapter)),
                feedSnapshotStore = snapshots, feedNowMillis = { now },
                onSourceArrivalsObserved = { sourceId, books ->
                    assertEquals("sluhayua", sourceId)
                    assertEquals("observer receives the actual parsed response", rawBooks, books)
                    observations.incrementAndGet()
                    coordinator.observeExplicit(key) { catalog.collectiveArrivalsBlock(sourceId, books) }
                }
            )

            // One public owner action. Its literal return is also the real parser/relay positive control.
            val feeds = withTimeout(20_000L) { catalog.refreshSourceFeeds(forceRefresh = true) }
            assertEquals(listOf(SourceCatalog.SourceNewFeed("sluhayua", "Sluhay UA", returnedBooks)), feeds)
            assertEquals("one arrivals endpoint; no card, detail, play, stream or cover fetch", listOf(feedUrl), requests.toList())
            assertEquals("ordinary snapshot honestly preserves the actual malformed nonempty parsed response",
                PersistedFeedSnapshot("sluhayua", FeedSnapshotPolicy.FEED_NEW_ARRIVALS, rawBooks, 1_700_000_000_000L),
                snapshots.snapshot("sluhayua", FeedSnapshotPolicy.FEED_NEW_ARRIVALS))
            assertTrue("enumeration does not import a listening profile", dao.getAllAudiobooksOnce().isEmpty())
            assertTrue("enumeration creates no chapter topology", dao.getAllChaptersOnce().isEmpty())
            assertTrue("enumeration does not materialize playable sources", dao.getSourcesByTypes(listOf("sluhayua")).isEmpty())
            assertEquals(0, dao.countLibraryEntries())
            assertEquals("a blank identity creates no browse Work", 0, dao.countWorks())
            assertEquals("a blank identity creates no browse WorkSource", 0, dao.countWorkSources())
            val expected = CollectiveFeedBlock(
                "sluhayua|NEW_ARRIVALS", "sluhayua", CollectiveBlockKind.NEW_ARRIVALS,
                "Збережені новинки G", "https://sluhay.com.ua/find/allcards?observed=last-good-g",
                listOf(
                    CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/81:last-good-first", "Раніша перша", "Перший автор G", "https://sluhay.com.ua/covers/last-good-first.jpg"),
                    CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/82:last-good-second", "Раніша друга", "Другий автор G", "https://sluhay.com.ua/covers/last-good-second.jpg")
                ), 1_699_996_400_000L, 1_700_018_000_000L, 7L,
                CollectiveAttempt(1_700_000_000_000L, CollectiveAttemptStatus.PARSE_FAILURE)
            )
            val active = blocks.active(key)
            assertNotNull("an existing active block must remain readable after a live malformed response", active)
            assertEquals("one actual malformed nonempty source response reaches the producer", 1, observations.get())
            assertEquals("S2_MALFORMED_NONEMPTY_ARRIVALS_MUST_KEEP_FULL_LAST_GOOD", expected, active)
            assertTrue("malformed nonempty arrivals never publish an invalid shared block", published.isEmpty())
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanUp(action: () -> Unit) {
                try { action() } catch (error: Throwable) { cleanupFailures += error }
            }
            cleanUp {
                ownedRelayBase?.let { base ->
                    TransportPrivacy.install(PrivacyPrefs(routeMode = RouteMode.RELAY,
                        proxyAddress = "$base/closed", dohEnabled = false))
                }
            }
            cleanUp { ownedRelay?.stop(0) }
            cleanUp { ownedExecutor?.shutdownNow() }
            cleanUp { ownedExecutor?.let { assertTrue("owned relay executor terminates", it.awaitTermination(5L, TimeUnit.SECONDS)) } }
            cleanUp { ownedDatabase?.close() }
            cleanUp { TransportPrivacy.install(PrivacyPrefs()) }
            if (cleanupFailures.isNotEmpty()) {
                val primary = primaryFailure
                if (primary != null) cleanupFailures.forEach { primary.addSuppressed(it) }
                else {
                    val cleanup = cleanupFailures.first()
                    cleanupFailures.drop(1).forEach { cleanup.addSuppressed(it) }
                    throw cleanup
                }
            }
        }
    }
}
