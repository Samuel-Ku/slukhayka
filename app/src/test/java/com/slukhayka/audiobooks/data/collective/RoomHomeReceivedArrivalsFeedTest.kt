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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The ordinary ingestion doors Home calls; not actual App/Compose/backend acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoomHomeReceivedArrivalsFeedTest {
    @Test(timeout = 60_000L)
    fun `ordinary Home ingestion reuses received arrivals while their six hour block is fresh`() = runBlocking {
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
            val liveJson = """{"cards":[{"_id":901001,"slug":"network-only-first","bookName":"Мережева перша","bookAuthor":["Автор мережі"],"kindSrc":"/covers/network-first.jpg"},{"_id":901002,"slug":"network-only-second","bookName":"Мережева друга","bookAuthor":["Інший автор"],"kindSrc":"/covers/network-second.jpg"}],"pageCount":1}"""
            relay.createContext("/") { exchange ->
                try {
                    val target = exchange.requestURI.rawQuery.orEmpty().split('&')
                        .singleOrNull { it.startsWith("url=") }?.substringAfter("url=")
                        ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }.orEmpty()
                    requests += target
                    val body = if (target == feedUrl) liveJson.toByteArray(StandardCharsets.UTF_8) else ByteArray(0)
                    exchange.sendResponseHeaders(if (body.isNotEmpty()) 200 else 404, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                } finally {
                    exchange.close()
                }
            }
            relay.start()
            val relayBase = "http://127.0.0.1:${relay.address.port}".also { ownedRelayBase = it }
            assertEquals(RouteResolution.Ok(NetworkRoute.Relay(relayBase)),
                TransportPrivacy.install(PrivacyPrefs(routeMode = RouteMode.RELAY, proxyAddress = relayBase, dohEnabled = false)))
            val adapter = SluhayuaAdapter(HttpFetcher())
            val liveBooks = listOf(
                SourceBook("Мережева перша", "Автор мережі", url = "https://sluhay.com.ua/901001:network-only-first",
                    coverImageUrl = "https://sluhay.com.ua/covers/network-first.jpg", sourceId = "sluhayua"),
                SourceBook("Мережева друга", "Інший автор", url = "https://sluhay.com.ua/901002:network-only-second",
                    coverImageUrl = "https://sluhay.com.ua/covers/network-second.jpg", sourceId = "sluhayua")
            )
            assertEquals("real adapter/relay positive control", liveBooks, withTimeout(15_000L) { adapter.fetchNew() })
            assertEquals(listOf(feedUrl), requests.toList())

            val now = 1_700_000_000_000L
            val dao = db.audiobookDao()
            val blocks = RoomCollectiveFeedBlockStore(dao)
            val received = CollectiveFeedBlock(
                "sluhayua|NEW_ARRIVALS", "sluhayua", CollectiveBlockKind.NEW_ARRIVALS,
                "Новинки Sluhay UA", "https://sluhay.com.ua",
                listOf(
                    CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/910001:received-first", "Отримана перша", "Перший автор", "https://sluhay.com.ua/covers/received-first.jpg"),
                    CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/910002:received-second", "Отримана друга", "Другий автор", "https://sluhay.com.ua/covers/received-second.jpg")
                ),
                fetchedAt = 1_699_996_400_000L, staleAfter = 1_700_018_000_000L, version = 2L,
                lastAttempt = CollectiveAttempt(1_699_996_400_000L, CollectiveAttemptStatus.SUCCESS)
            )
            assertTrue(blocks.activate(received))
            assertEquals(received, blocks.active("sluhayua|NEW_ARRIVALS"))
            val expectedBooks = listOf(
                SourceBook("Отримана перша", "Перший автор", url = "https://sluhay.com.ua/910001:received-first",
                    coverImageUrl = "https://sluhay.com.ua/covers/received-first.jpg", sourceId = "sluhayua", language = "uk"),
                SourceBook("Отримана друга", "Другий автор", url = "https://sluhay.com.ua/910002:received-second",
                    coverImageUrl = "https://sluhay.com.ua/covers/received-second.jpg", sourceId = "sluhayua", language = "uk")
            )
            val snapshots = FeedSnapshotStore(dao) { now }
            assertNull("the receiver has no ordinary arrivals snapshot", snapshots.snapshot("sluhayua", FeedSnapshotPolicy.FEED_NEW_ARRIVALS))
            // Independent catalogue lane: seven-hour-old cards are within its 24 h TTL.
            // Its legitimate ingestion remains enabled; it must not contaminate the feed oracle.
            // Native adapter cards carry no per-book language; the catalog fixture does likewise.
            // The separate final feed oracle retains the adapter's declared Ukrainian claim.
            val catalogBooks = listOf(
                SourceBook("Отримана перша", "Перший автор", url = "https://sluhay.com.ua/910001:received-first",
                    coverImageUrl = "https://sluhay.com.ua/covers/received-first.jpg", sourceId = "sluhayua", language = ""),
                SourceBook("Отримана друга", "Другий автор", url = "https://sluhay.com.ua/910002:received-second",
                    coverImageUrl = "https://sluhay.com.ua/covers/received-second.jpg", sourceId = "sluhayua", language = "")
            )
            assertTrue(snapshots.saveSnapshot(PersistedFeedSnapshot("sluhayua", "catalog", catalogBooks,
                observedAt = 1_699_974_800_000L, parameters = "limit=60")))
            assertEquals(catalogBooks, snapshots.freshBooks("sluhayua", "catalog"))
            val catalog = SourceCatalog(dao, listOf(adapter), LibraryImport(dao, context, listOf(adapter)),
                feedSnapshotStore = snapshots, feedNowMillis = { now })
            val union = withTimeout(15_000L) { catalog.refreshUnifiedCatalog() }
            assertEquals(listOf("Отримана друга", "Отримана перша"), union.map { it.title })
            assertEquals("the independently fresh catalogue snapshot prevents its own source request", listOf(feedUrl), requests.toList())
            val owner = CollectiveFeedRefresh(blocks, InMemoryCollectiveRefreshLease(),
                fetch = { key ->
                    assertEquals("sluhayua|NEW_ARRIVALS", key)
                    catalog.collectiveBlockFetch("sluhayua", CollectiveBlockKind.NEW_ARRIVALS)
                }, clock = { now })
            assertEquals(received, withTimeout(15_000L) { owner.read("sluhayua|NEW_ARRIVALS") })
            assertEquals("fresh collective owner read is already source-free", listOf(feedUrl), requests.toList())

            // This is HomeScreen's existing ordinary feed door, not a cache-only reader call.
            val feeds = withTimeout(15_000L) { catalog.refreshSourceFeeds() }
            assertEquals("S2_HOME_FRESH_BLOCK_FEED_FIRST: ordinary Home ingestion must reuse the received arrivals while its six-hour TTL is fresh",
                listOf(feedUrl), requests.toList())
            assertEquals(listOf(SourceCatalog.SourceNewFeed("sluhayua", "Sluhay UA", expectedBooks)), feeds)
            assertEquals("ordinary ingestion must not age or replace the received block", received,
                blocks.active("sluhayua|NEW_ARRIVALS"))
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
            cleanUp {
                ownedExecutor?.let { executor ->
                    assertTrue("owned relay executor terminates", executor.awaitTermination(5L, TimeUnit.SECONDS))
                }
            }
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
