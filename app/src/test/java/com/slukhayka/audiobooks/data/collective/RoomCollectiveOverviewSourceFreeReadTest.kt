package com.slukhayka.audiobooks.data.collective

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.privacy.NetworkRoute
import com.slukhayka.audiobooks.data.privacy.PrivacyPrefs
import com.slukhayka.audiobooks.data.privacy.RouteMode
import com.slukhayka.audiobooks.data.privacy.RouteResolution
import com.slukhayka.audiobooks.data.privacy.TransportPrivacy
import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.data.source.SluhayuaAdapter
import com.slukhayka.audiobooks.data.source.SourceGateProvider
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Public Overview reader over real Room and real source HTTP; not App/Home/backend proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoomCollectiveOverviewSourceFreeReadTest {
    @Test(timeout = 60_000L)
    fun `reading received recommendations without arrivals does not request the source feed`() = runBlocking {
        var ownedDatabase: AudiobookDatabase? = null
        var ownedExecutor: java.util.concurrent.ExecutorService? = null
        var ownedRelay: HttpServer? = null
        var ownedRelayBase: String? = null
        var primaryFailure: Throwable? = null
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            assertEquals(Application::class.java, context.javaClass)
            assertNull("no App-installed source gate may hide the positive-control HTTP call", SourceGateProvider.current)
            assertEquals("the isolated Room worker begins with the default route", NetworkRoute.Direct, TransportPrivacy.current())
            val db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java).build()
                .also { ownedDatabase = it }
            val requests = CopyOnWriteArrayList<String>()
            val executor = Executors.newSingleThreadExecutor().also { ownedExecutor = it }
            val relay = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 4).also { ownedRelay = it }
            relay.executor = executor
            val feedUrl = "https://sluhay.com.ua/find/allcards?sort=time&order=desc&page=1"
            relay.createContext("/") { exchange ->
                try {
                    val target = exchange.requestURI.rawQuery.orEmpty().split('&')
                        .singleOrNull { it.startsWith("url=") }?.substringAfter("url=")
                        ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }.orEmpty()
                    requests += target
                    // A real valid empty feed is enough to detect this unsolicited read.
                    // No received recommendation link or profile is served by the transport.
                    val body = if (target == feedUrl) "{\"cards\":[],\"pageCount\":1}".toByteArray() else ByteArray(0)
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
            // Public real-adapter positive control, no CollectiveFeedRefresh/store activation.
            // This proves the transport can detect a feed call; zero is not a broken route.
            assertTrue(withTimeout(15_000L) { adapter.fetchNew() }.isEmpty())
            assertEquals(listOf(feedUrl), requests.toList())
            val dao = db.audiobookDao()
            val store = RoomCollectiveFeedBlockStore(dao)
            val received = CollectiveFeedBlock(
                blockKey = "sluhayua|RECOMMENDATIONS", sourceId = "sluhayua",
                kind = CollectiveBlockKind.RECOMMENDATIONS, name = "До «Сердешна Оксана»",
                provenanceUrl = "https://sluhay.com.ua/5931576:grigorij-kvitka-osnovjanenko-serdjeshna-oksana",
                cards = listOf(CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/2932269:Микола-Гоголь-Тарас-Бульба",
                    "Тарас Бульба", "Микола Гоголь", null)),
                fetchedAt = 1_000L, staleAfter = 86_401_000L, version = 1L,
                lastAttempt = CollectiveAttempt(1_000L, CollectiveAttemptStatus.SUCCESS)
            )
            assertTrue(store.activate(received))
            assertNull("the receiver has never observed an arrivals block", store.active("sluhayua|NEW_ARRIVALS"))
            val overview = CollectiveOverviewBlocks(store)
            val requestsBeforeRead = requests.toList()
            assertEquals(listOf(received), withTimeout(15_000L) { overview.read(listOf("sluhayua")) })
            assertEquals("public local read must not request a feed because a different block is absent",
                requestsBeforeRead, requests.toList())
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            // Each owned cleanup runs even if setup or another cleanup failed.
            // Keep the original assertion failure and attach cleanup errors to it.
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanUp(action: () -> Unit) {
                try {
                    action()
                } catch (error: Throwable) {
                    cleanupFailures += error
                }
            }
            cleanUp {
                ownedRelayBase?.let { relayBase ->
                    TransportPrivacy.install(PrivacyPrefs(routeMode = RouteMode.RELAY,
                        proxyAddress = "$relayBase/closed", dohEnabled = false))
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
                if (primary != null) {
                    cleanupFailures.forEach { primary.addSuppressed(it) }
                } else {
                    val cleanup = cleanupFailures.first()
                    cleanupFailures.drop(1).forEach { cleanup.addSuppressed(it) }
                    throw cleanup
                }
            }
        }
    }
}
