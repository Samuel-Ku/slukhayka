package com.slukhayka.audiobooks.data.collective

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.FeedSnapshotStore
import com.slukhayka.audiobooks.data.catalog.PersistedFeedSnapshot
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.FeedSnapshotEntity
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.privacy.NetworkRoute
import com.slukhayka.audiobooks.data.privacy.PrivacyPrefs
import com.slukhayka.audiobooks.data.privacy.RouteMode
import com.slukhayka.audiobooks.data.privacy.RouteResolution
import com.slukhayka.audiobooks.data.privacy.TransportPrivacy
import com.slukhayka.audiobooks.data.source.FourReadAdapter
import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.data.source.LihtarAdapter
import com.slukhayka.audiobooks.data.source.SluhayAdapter
import com.slukhayka.audiobooks.data.source.SluhayuaAdapter
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceCookieProvider
import com.slukhayka.audiobooks.data.source.SourceGateProvider
import com.slukhayka.audiobooks.data.source.SourceRegistry
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real public Home ingestion regressions; no App/Compose or distributed lease proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoomHomeReceivedArrivalsEdgesTest {
    @Test(timeout = 60_000L)
    fun `forced refresh bypasses both fresh received and ordinary arrivals`() = runBlocking {
        withFixture {
            val adapter = SluhayuaAdapter(HttpFetcher())
            positiveControl(adapter, NETWORK_BOOKS, listOf(FEED_URL))
            val received = receivedBlock()
            assertTrue(blocks.activate(received))
            val snapshots = FeedSnapshotStore(db.audiobookDao()) { NOW }
            assertTrue(snapshots.saveSnapshot(PersistedFeedSnapshot("sluhayua", "new-arrivals", RECEIVED_BOOKS, NOW - 1_000L)))
            assertEquals("ordinary arrivals are genuinely fresh before force bypass", RECEIVED_BOOKS,
                snapshots.freshBooks("sluhayua", "new-arrivals"))
            val catalog = catalog(adapter, snapshots)
            assertEquals(listOf(SourceCatalog.SourceNewFeed("sluhayua", "Sluhay UA", NETWORK_UK_BOOKS)),
                withTimeout(15_000L) { catalog.refreshSourceFeeds(forceRefresh = true) })
            assertEquals(listOf(FEED_URL, FEED_URL), requests.toList())
            assertEquals(received, blocks.active(received.blockKey))
        }
    }

    @Test(timeout = 60_000L)
    fun `session bound live adapter bypasses received and ordinary arrivals`() = runBlocking {
        withFixture {
            // External cookie input only; the real Sluhay transport/parser and session policy run.
            val adapter = SluhayAdapter(HttpFetcher(), cookieProvider = object : SourceCookieProvider {
                override fun cookieFor(url: String): String = if (url == "https://sluhay.com/") "fixture_session=present" else ""
            })
            assertTrue(adapter.sessionBound)
            val expected = listOf(SourceBook("Сесійна книга", "Сесійний автор", url = "https://sluhay.com/9901-session.html",
                coverImageUrl = "https://sluhay.com/uploads/session.jpg", sourceId = "sluhay"))
            positiveControl(adapter, expected, listOf("https://sluhay.com/"))
            val received = receivedBlock("sluhay")
            assertTrue(blocks.activate(received))
            val snapshots = FeedSnapshotStore(db.audiobookDao()) { NOW }
            val sessionCacheBooks = listOf(SourceBook("Кеш сесії", "Автор кешу", url = "https://sluhay.com/9902-cache.html", sourceId = "sluhay"))
            assertTrue(snapshots.saveSnapshot(PersistedFeedSnapshot("sluhay", "new-arrivals", sessionCacheBooks, NOW - 1_000L)))
            assertEquals("ordinary arrivals are genuinely fresh before session bypass", sessionCacheBooks,
                snapshots.freshBooks("sluhay", "new-arrivals"))
            assertEquals(listOf(SourceCatalog.SourceNewFeed("sluhay", "Sluhay", expected.map { it.copy(language = "uk") })),
                withTimeout(15_000L) { catalog(adapter, snapshots).refreshSourceFeeds() })
            assertEquals(listOf("https://sluhay.com/", "https://sluhay.com/"), requests.toList())
            assertEquals(received, blocks.active(received.blockKey))
        }
    }

    @Test(timeout = 60_000L)
    fun `registry excluded scam source cannot publish received cards or fetch Home`() = runBlocking {
        withFixture {
            positiveControl(SluhayuaAdapter(HttpFetcher()), NETWORK_BOOKS, listOf(FEED_URL))
            assertTrue(SourceRegistry.isScam("4read"))
            val received = receivedBlock("4read")
            assertTrue(blocks.activate(received))
            val rowBefore = db.audiobookDao().getFeedSnapshot("4read", "collective-new_arrivals")
            assertNotNull(rowBefore)
            assertEquals(emptyList<SourceCatalog.SourceNewFeed>(),
                withTimeout(15_000L) { catalog(FourReadAdapter(HttpFetcher())).refreshSourceFeeds() })
            assertEquals(listOf(FEED_URL), requests.toList())
            assertEquals(rowBefore, db.audiobookDao().getFeedSnapshot("4read", "collective-new_arrivals"))
        }
    }

    @Test(timeout = 60_000L)
    fun `missing received arrivals retain the real source fallback`() = runBlocking {
        withFixture {
            assertNull(blocks.active("sluhayua|NEW_ARRIVALS"))
            expectSluhayFallback(null)
        }
    }

    @Test(timeout = 60_000L)
    fun `exact expired boundary retains received stamps while falling back`() = runBlocking {
        withFixture { expectSluhayFallback(receivedBlock().copy(fetchedAt = NOW - 21_600_000L, staleAfter = NOW)) }
    }

    @Test(timeout = 60_000L)
    fun `future received timestamp cannot bypass the real source fallback`() = runBlocking {
        withFixture { expectSluhayFallback(receivedBlock().copy(fetchedAt = NOW + 1L, staleAfter = NOW + 21_600_001L)) }
    }

    @Test(timeout = 60_000L)
    fun `malformed received document retains the real source fallback`() = runBlocking {
        withFixture {
            val row = FeedSnapshotEntity("sluhayua", "collective-new_arrivals", "", NOW - 3_600_000L,
                """{"blockKey":"sluhayua|NEW_ARRIVALS","sourceId":"sluhayua","kind":"NEW_ARRIVALS","cards":[{"sourceId":"sluhayua","sourceUrl":"https://sluhay.com.ua/910001:received-first"}]}""")
            db.audiobookDao().upsertFeedSnapshot(row)
            assertNull(blocks.active("sluhayua|NEW_ARRIVALS"))
            expectSluhayFallback(null)
            assertEquals(row, db.audiobookDao().getFeedSnapshot("sluhayua", "collective-new_arrivals"))
        }
    }

    @Test(timeout = 60_000L)
    fun `known broken Lihtar slug block falls back to real category cards`() = runBlocking {
        withFixture {
            val adapter = LihtarAdapter(HttpFetcher())
            val expected = listOf(SourceBook("Ліхтарна книга", "Ліхтарний автор", url = "https://lihtar.in.ua/biblioteka/kazky/live-card",
                coverImageUrl = "https://lihtar.in.ua/covers/lihtar.jpg", sourceId = "lihtar"))
            val urls = listOf("https://lihtar.in.ua/biblioteka", "https://lihtar.in.ua/biblioteka/kazky")
            positiveControl(adapter, expected, urls)
            val broken = receivedBlock("lihtar").copy(cards = listOf(
                CollectiveBlockCard("lihtar", "https://lihtar.in.ua/biblioteka/kazky/old-slug", "old slug", "", null)))
            assertTrue(blocks.activate(broken))
            assertEquals(broken, blocks.active("lihtar|NEW_ARRIVALS"))
            assertEquals(listOf(SourceCatalog.SourceNewFeed("lihtar", "Lihtar", expected.map { it.copy(language = "uk") })),
                withTimeout(15_000L) { catalog(adapter).refreshSourceFeeds() })
            assertEquals(urls + urls, requests.toList())
            assertEquals(broken, blocks.active("lihtar|NEW_ARRIVALS"))
        }
    }

    @Test(timeout = 60_000L)
    fun `caller cancellation after Room read cannot fetch or publish received cards`() = runBlocking {
        withFixture {
            val adapter = SluhayuaAdapter(HttpFetcher())
            positiveControl(adapter, NETWORK_BOOKS, listOf(FEED_URL))
            val received = receivedBlock()
            assertTrue(blocks.activate(received))
            val clockEntered = CompletableDeferred<Unit>()
            val releaseClock = CountDownLatch(1)
            try {
                val catalog = catalog(adapter, clock = {
                    clockEntered.complete(Unit)
                    check(releaseClock.await(5L, TimeUnit.SECONDS)) { "public clock barrier timed out" }
                    NOW
                })
                coroutineScope {
                    val caller = launch(Dispatchers.Default) { catalog.refreshSourceFeeds() }
                    var callerFailure: Throwable? = null
                    try {
                        withTimeout(5_000L) { clockEntered.await() }
                        assertEquals(received, blocks.active(received.blockKey))
                        caller.cancel()
                        releaseClock.countDown()
                        withTimeout(5_000L) { caller.join() }
                        assertTrue(caller.isCancelled)
                    } catch (error: Throwable) {
                        callerFailure = error
                        throw error
                    } finally {
                        releaseClock.countDown()
                        try {
                            withContext(NonCancellable) { withTimeout(5_000L) { caller.cancelAndJoin() } }
                        } catch (cleanup: Throwable) {
                            val original = callerFailure
                            if (original != null) original.addSuppressed(cleanup) else throw cleanup
                        }
                    }
                }
                assertEquals(listOf(FEED_URL), requests.toList())
                assertEquals(emptyList<SourceCatalog.SourceNewFeed>(), catalog.sourceFeeds.value)
                assertFalse(catalog.isFeedsLoading.value)
                assertEquals(received, blocks.active(received.blockKey))
            } finally {
                releaseClock.countDown()
            }
        }
    }

    private class Fixture(val context: Context, val db: AudiobookDatabase, val requests: CopyOnWriteArrayList<String>) {
        val blocks = RoomCollectiveFeedBlockStore(db.audiobookDao())
        fun catalog(adapter: SourceAdapter, snapshots: FeedSnapshotStore = FeedSnapshotStore(db.audiobookDao()) { NOW }, clock: () -> Long = { NOW }) =
            SourceCatalog(db.audiobookDao(), listOf(adapter), LibraryImport(db.audiobookDao(), context, listOf(adapter)),
                feedSnapshotStore = snapshots, feedNowMillis = clock)

        suspend fun positiveControl(adapter: SourceAdapter, expected: List<SourceBook>, urls: List<String>) {
            assertEquals(expected, withTimeout(15_000L) { adapter.fetchNew() })
            assertEquals(urls, requests.toList())
        }

        suspend fun expectSluhayFallback(received: CollectiveFeedBlock?) {
            val adapter = SluhayuaAdapter(HttpFetcher())
            positiveControl(adapter, NETWORK_BOOKS, listOf(FEED_URL))
            if (received != null) {
                assertTrue(blocks.activate(received))
                assertEquals(received, blocks.active("sluhayua|NEW_ARRIVALS"))
            }
            assertEquals(listOf(SourceCatalog.SourceNewFeed("sluhayua", "Sluhay UA", NETWORK_UK_BOOKS)),
                withTimeout(15_000L) { catalog(adapter).refreshSourceFeeds() })
            assertEquals(listOf(FEED_URL, FEED_URL), requests.toList())
            assertEquals(received, blocks.active("sluhayua|NEW_ARRIVALS"))
        }
    }

    private suspend fun withFixture(action: suspend Fixture.() -> Unit) {
        var db: AudiobookDatabase? = null
        var executor: ExecutorService? = null
        var relay: HttpServer? = null
        var relayBase: String? = null
        var primary: Throwable? = null
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            assertEquals(Application::class.java, context.javaClass)
            assertNull(SourceGateProvider.current)
            assertEquals(NetworkRoute.Direct, TransportPrivacy.current())
            val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java).build().also { db = it }
            val requests = CopyOnWriteArrayList<String>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 4).also { relay = it }
            server.executor = Executors.newSingleThreadExecutor().also { executor = it }
            val responses = mapOf(
                FEED_URL to """{"cards":[{"_id":901001,"slug":"network-only-first","bookName":"Мережева перша","bookAuthor":["Автор мережі"],"kindSrc":"/covers/network-first.jpg"},{"_id":901002,"slug":"network-only-second","bookName":"Мережева друга","bookAuthor":["Інший автор"],"kindSrc":"/covers/network-second.jpg"}],"pageCount":1}""",
                "https://sluhay.com/" to """<a class="poster-item grid-item" href="https://sluhay.com/9901-session.html"><img data-src="/uploads/session.jpg"><div class="poster-item__title">Сесійна книга - Сесійний автор</div></a>""",
                "https://lihtar.in.ua/biblioteka" to """<a href="https://lihtar.in.ua/biblioteka/kazky">Казки</a>""",
                "https://lihtar.in.ua/biblioteka/kazky" to """<a href="https://lihtar.in.ua/biblioteka/kazky/live-card"><img src="/covers/lihtar.jpg"><h4>Ліхтарна книга</h4><p>Ліхтарний автор</p></a>"""
            )
            server.createContext("/") { exchange ->
                try {
                    val target = exchange.requestURI.rawQuery.orEmpty().split('&').singleOrNull { it.startsWith("url=") }
                        ?.substringAfter("url=")?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }.orEmpty()
                    requests += target
                    val body = responses[target]?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
                    exchange.sendResponseHeaders(if (body.isNotEmpty()) 200 else 404, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                } finally { exchange.close() }
            }
            server.start()
            val base = "http://127.0.0.1:${server.address.port}".also { relayBase = it }
            assertEquals(RouteResolution.Ok(NetworkRoute.Relay(base)),
                TransportPrivacy.install(PrivacyPrefs(routeMode = RouteMode.RELAY, proxyAddress = base, dohEnabled = false)))
            Fixture(context, database, requests).action()
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            val failures = mutableListOf<Throwable>()
            fun clean(action: () -> Unit) { try { action() } catch (error: Throwable) { failures += error } }
            clean { relayBase?.let { TransportPrivacy.install(PrivacyPrefs(routeMode = RouteMode.RELAY, proxyAddress = "$it/closed", dohEnabled = false)) } }
            clean { relay?.stop(0) }
            clean { executor?.shutdownNow() }
            clean { executor?.let { assertTrue("owned relay executor terminates", it.awaitTermination(5L, TimeUnit.SECONDS)) } }
            clean { db?.close() }
            clean { TransportPrivacy.install(PrivacyPrefs()) }
            if (failures.isNotEmpty()) {
                val original = primary
                if (original != null) failures.forEach { original.addSuppressed(it) }
                else { val error = failures.first(); failures.drop(1).forEach { error.addSuppressed(it) }; throw error }
            }
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val FEED_URL = "https://sluhay.com.ua/find/allcards?sort=time&order=desc&page=1"
        val NETWORK_BOOKS = listOf(
            SourceBook("Мережева перша", "Автор мережі", url = "https://sluhay.com.ua/901001:network-only-first", coverImageUrl = "https://sluhay.com.ua/covers/network-first.jpg", sourceId = "sluhayua"),
            SourceBook("Мережева друга", "Інший автор", url = "https://sluhay.com.ua/901002:network-only-second", coverImageUrl = "https://sluhay.com.ua/covers/network-second.jpg", sourceId = "sluhayua")
        )
        val NETWORK_UK_BOOKS = NETWORK_BOOKS.map { it.copy(language = "uk") }
        val RECEIVED_BOOKS = listOf(
            SourceBook("Отримана перша", "Перший автор", url = "https://sluhay.com.ua/910001:received-first", coverImageUrl = "https://sluhay.com.ua/covers/received-first.jpg", sourceId = "sluhayua"),
            SourceBook("Отримана друга", "Другий автор", url = "https://sluhay.com.ua/910002:received-second", coverImageUrl = "https://sluhay.com.ua/covers/received-second.jpg", sourceId = "sluhayua")
        )
        fun receivedBlock(sourceId: String = "sluhayua"): CollectiveFeedBlock {
            val origin = when (sourceId) {
                "sluhayua" -> "https://sluhay.com.ua"
                "sluhay" -> "https://sluhay.com"
                "lihtar" -> "https://lihtar.in.ua"
                "4read" -> "https://4read.org"
                else -> error("Unknown fixture source")
            }
            return CollectiveFeedBlock(
                "$sourceId|NEW_ARRIVALS", sourceId, CollectiveBlockKind.NEW_ARRIVALS, "Отриманий блок", origin,
                listOf(
                    CollectiveBlockCard(sourceId, "$origin/received-first", "Отримана перша", "Перший автор", "$origin/first.jpg"),
                    CollectiveBlockCard(sourceId, "$origin/received-second", "Отримана друга", "Другий автор", "$origin/second.jpg")
                ),
                1_699_996_400_000L, 1_700_018_000_000L, 2L,
                CollectiveAttempt(1_699_996_400_000L, CollectiveAttemptStatus.SUCCESS)
            )
        }
    }
}
