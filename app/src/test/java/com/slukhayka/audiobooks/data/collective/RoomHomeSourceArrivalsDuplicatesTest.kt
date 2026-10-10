package com.slukhayka.audiobooks.data.collective

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.FeedSnapshotPolicy
import com.slukhayka.audiobooks.data.catalog.FeedSnapshotStore
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
class RoomHomeSourceArrivalsDuplicatesTest {
    @Test(timeout = 60_000L)
    fun `public Home feed keeps one first observed card per Work inside its collective arrivals block`() = runBlocking {
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
            val liveJson = """{"cards":[{"_id":71,"slug":"alpha-first","bookName":"Альфа","bookAuthor":["Автор Альфи"],"kindSrc":"/covers/alpha-first.jpg"},{"_id":71,"slug":"alpha-first","bookName":"Альфа","bookAuthor":["Автор Альфи"],"kindSrc":"/covers/alpha-first.jpg"},{"_id":73,"slug":"alpha-alternative","bookName":"Альфа","bookAuthor":["Автор Альфи"],"kindSrc":"/covers/alpha-alternative.jpg"},{"_id":72,"slug":"beta","bookName":"Бета","bookAuthor":["Автор Бети"],"kindSrc":"/covers/beta.jpg"}],"pageCount":1}"""
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
                SourceBook("Альфа", "Автор Альфи", url = "https://sluhay.com.ua/71:alpha-first",
                    coverImageUrl = "https://sluhay.com.ua/covers/alpha-first.jpg", sourceId = "sluhayua"),
                SourceBook("Альфа", "Автор Альфи", url = "https://sluhay.com.ua/71:alpha-first",
                    coverImageUrl = "https://sluhay.com.ua/covers/alpha-first.jpg", sourceId = "sluhayua"),
                SourceBook("Альфа", "Автор Альфи", url = "https://sluhay.com.ua/73:alpha-alternative",
                    coverImageUrl = "https://sluhay.com.ua/covers/alpha-alternative.jpg", sourceId = "sluhayua"),
                SourceBook("Бета", "Автор Бети", url = "https://sluhay.com.ua/72:beta",
                    coverImageUrl = "https://sluhay.com.ua/covers/beta.jpg", sourceId = "sluhayua")
            )
            val returnedBooks = listOf(
                SourceBook("Альфа", "Автор Альфи", url = "https://sluhay.com.ua/71:alpha-first",
                    coverImageUrl = "https://sluhay.com.ua/covers/alpha-first.jpg", sourceId = "sluhayua", language = "uk"),
                SourceBook("Альфа", "Автор Альфи", url = "https://sluhay.com.ua/71:alpha-first",
                    coverImageUrl = "https://sluhay.com.ua/covers/alpha-first.jpg", sourceId = "sluhayua", language = "uk"),
                SourceBook("Альфа", "Автор Альфи", url = "https://sluhay.com.ua/73:alpha-alternative",
                    coverImageUrl = "https://sluhay.com.ua/covers/alpha-alternative.jpg", sourceId = "sluhayua", language = "uk"),
                SourceBook("Бета", "Автор Бети", url = "https://sluhay.com.ua/72:beta",
                    coverImageUrl = "https://sluhay.com.ua/covers/beta.jpg", sourceId = "sluhayua", language = "uk")
            )
            val now = 1_700_000_000_000L
            val dao = db.audiobookDao()
            val snapshots = FeedSnapshotStore(dao) { now }
            val blocks = RoomCollectiveFeedBlockStore(dao)
            val key = "sluhayua|NEW_ARRIVALS"
            assertNull(snapshots.snapshot("sluhayua", FeedSnapshotPolicy.FEED_NEW_ARRIVALS))
            assertNull(blocks.active(key))
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
            assertEquals("ordinary feed persistence also saw the literal live response", rawBooks,
                snapshots.snapshot("sluhayua", FeedSnapshotPolicy.FEED_NEW_ARRIVALS)?.books)
            assertTrue("enumeration does not import a listening profile", dao.getAllAudiobooksOnce().isEmpty())
            assertTrue("enumeration creates no chapter topology", dao.getAllChaptersOnce().isEmpty())
            assertTrue("enumeration does not materialize playable sources", dao.getSourcesByTypes(listOf("sluhayua")).isEmpty())
            assertEquals(0, dao.countLibraryEntries())
            assertEquals("existing browse admission remains enabled", 2, dao.countWorks())
            assertEquals("exact repeated URL is idempotent but alternative URL stays a browse Source", 3, dao.countWorkSources())
            val expected = CollectiveFeedBlock(
                "sluhayua|NEW_ARRIVALS", "sluhayua", CollectiveBlockKind.NEW_ARRIVALS,
                "Sluhay UA", "https://sluhay.com.ua",
                listOf(
                    CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/71:alpha-first", "Альфа", "Автор Альфи", "https://sluhay.com.ua/covers/alpha-first.jpg"),
                    CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/72:beta", "Бета", "Автор Бети", "https://sluhay.com.ua/covers/beta.jpg")
                ), 1_700_000_000_000L, 1_700_021_600_000L, 1L,
                CollectiveAttempt(1_700_000_000_000L, CollectiveAttemptStatus.SUCCESS)
            )
            val active = blocks.active(key)
            assertNotNull("the already accepted live producer must still create an active block", active)
            assertEquals("one live producer observation even with duplicate cards", 1, observations.get())
            assertEquals("one admission publication before inspecting its duplicate policy", 1, published.size)
            assertEquals("full independent active block keeps the first occurrence of each Work", expected, active)
            assertEquals("actual admission publishes its single committed deduplicated block", listOf(expected), published.toList())
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
