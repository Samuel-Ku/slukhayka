package com.slukhayka.audiobooks.data.collective

import android.app.Application
import android.app.Instrumentation
import android.content.pm.PackageManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.data.catalog.CatalogIndexEntry
import com.slukhayka.audiobooks.data.catalog.FeedSnapshotPolicy
import com.slukhayka.audiobooks.data.catalog.FeedSnapshotStore
import com.slukhayka.audiobooks.data.catalog.PersistedWorkIndex
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.catalog.WorkIndexStore
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.privacy.*
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceGateProvider
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual App composition with a warm persisted Work index; not providers/Home/backend/device/audio. */
@RunWith(ControlledAppFixtureRunner::class)
@Config(sdk = [36], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ControlledAttachedAppArrivalsCompositionTest {
    // Keep public App lifecycle on the actual main looper; the coroutine body
    // and the existing official single-worker supervisor own the deadlines.
    @Test
    fun `actual attached App public live feed persists received arrivals for its public overview reader`() = runBlocking {
        var context: Application? = null
        var database: AudiobookDatabase? = null
        var relay: HttpServer? = null
        var relayBase: String? = null
        var executor: ExecutorService? = null
        var primaryFailure: Throwable? = null
        try {
            withTimeout(180_000L) {
                println("S2_ACTUAL_TEST_WORKER_PID=${ProcessHandle.current().pid()}")
                val runtime = ApplicationProvider.getApplicationContext<Application>().also { context = it }
                assertEquals(Application::class.java, runtime.javaClass)
                requireMainThread()
                @Suppress("DEPRECATION")
                val declared = runtime.packageManager.getPackageInfo(runtime.packageName,
                    PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS or
                        PackageManager.GET_SERVICES or PackageManager.GET_ACTIVITIES)
                assertTrue(declared.providers.isNullOrEmpty() && declared.receivers.isNullOrEmpty() &&
                    declared.services.isNullOrEmpty() && declared.activities.isNullOrEmpty())
                val seed = runtime.assets.open("catalog_seed.json").use { it.readBytes() }
                assertEquals("9502be0c57dcd7fa001f875cd3eba38f18fc6b08484e7702f7246efdc8b2a10d",
                    MessageDigest.getInstance("SHA-256").digest(seed).joinToString("") { "%02x".format(it.toInt() and 255) })
                assertNull(FirebaseOptions.fromResource(runtime))
                assertTrue(FirebaseApp.getApps(runtime).isEmpty())
                assertEquals(0, runtime.resources.getIdentifier("google_app_id", "string", runtime.packageName))
                assertNull(AudiobookDatabase.databaseNameOverride)
                assertNull("runtime setup has no installed App request gate yet", SourceGateProvider.current)
                val requests = CopyOnWriteArrayList<String>()
                val arrivalsResponses = AtomicInteger()
                val total = AtomicInteger()
                val localExecutor = Executors.newFixedThreadPool(4).also { executor = it }
                val localRelay = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8).also { relay = it }
                localRelay.executor = localExecutor
                val feedUrl = "https://sluhay.com.ua/find/allcards?sort=time&order=desc&page=1"
                val liveJson = """{"cards":[{"_id":901001,"slug":"live-arrivals-first","bookName":"Свіжа перша","bookAuthor":["Перший автор"],"kindSrc":"/covers/live-first.jpg"},{"_id":901002,"slug":"live-arrivals-second","bookName":"Свіжа друга","bookAuthor":["Другий автор"],"kindSrc":"/covers/live-second.jpg"}],"pageCount":1}"""
                localRelay.createContext("/") { exchange ->
                    try {
                        val target = exchange.requestURI.rawQuery.orEmpty().split('&')
                            .singleOrNull { it.startsWith("url=") }?.substringAfter("url=")
                            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }.orEmpty()
                        requests += target
                        val call = total.incrementAndGet()
                        val body = if (call <= 128 && target == feedUrl) {
                            arrivalsResponses.incrementAndGet()
                            liveJson.toByteArray(StandardCharsets.UTF_8)
                        } else ByteArray(0)
                        exchange.sendResponseHeaders(if (body.isNotEmpty()) 200 else 404, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    } finally { exchange.close() }
                }
                localRelay.start()
                val localBase = "http://127.0.0.1:${localRelay.address.port}".also { relayBase = it }
                val route = PrivacyPrefs(routeMode = RouteMode.RELAY, proxyAddress = localBase, dohEnabled = false)
                SharedPreferencesPrivacySettingsStore(runtime).save(route)
                assertEquals(RouteResolution.Ok(NetworkRoute.Relay(localBase)), TransportPrivacy.install(route))
                val actualApp = Instrumentation.newApplication(App::class.java, runtime) as App
                assertEquals(App::class.java, actualApp.javaClass)
                assertSame(runtime, actualApp.baseContext)
                assertSame(runtime, actualApp.applicationContext)
                assertEquals(route, actualApp.privacySettings.load())
                assertNull(actualApp.sharedMetaStore)
                val db = AudiobookDatabase.getDatabase(actualApp).also { database = it }
                val dao = actualApp.audiobookDao
                assertSame(dao, db.audiobookDao())
                assertTrue(dao.getAllAudiobooksOnce().isEmpty())
                assertEquals(0, dao.countWorks())
                assertEquals(0, dao.countLibraryEntries())
                val blocks = RoomCollectiveFeedBlockStore(dao)
                val snapshots = FeedSnapshotStore(dao)
                assertNull(blocks.active("sluhayua|NEW_ARRIVALS"))
                assertNull(snapshots.snapshot("sluhayua", FeedSnapshotPolicy.FEED_NEW_ARRIVALS))

                // Public file-backed nonempty index avoids startup consuming
                // the exact same Sluhay URL before the explicit feed action.
                val warmStore = WorkIndexStore(File(actualApp.filesDir, "work_index.tsv"))
                assertNull("runtime-owned Work index is initially absent", warmStore.load())
                val warmIndex = PersistedWorkIndex(listOf(CatalogIndexEntry(
                    "sluhayua", "https://sluhay.com.ua/5931576:grigorij-kvitka-osnovjanenko-serdjeshna-oksana",
                    "grigorij-kvitka-osnovjanenko-serdjeshna-oksana", "")), System.currentTimeMillis())
                warmStore.save(warmIndex)
                assertEquals("public persisted warm index is nonempty and complete", warmIndex, warmStore.load())
                requireMainThread()
                Instrumentation().callApplicationOnCreate(actualApp)
                assertSame(actualApp, App.instance)
                assertNotNull("real onCreate installs its normal request gate", SourceGateProvider.current)
                assertEquals(NetworkRoute.Relay(localBase), TransportPrivacy.current())
                val servedIndex = requireNotNull(withTimeout(30_000L) { actualApp.workIndexRefresher.refreshIfStale() })
                assertEquals(warmIndex.entries, servedIndex.allEntriesSnapshot)
                assertEquals(warmIndex, warmStore.load())
                pumpMainLooper()
                assertEquals("startup never consumes the arrivals endpoint", 0, requests.count { it == feedUrl })
                assertEquals(0, arrivalsResponses.get())
                assertNull("startup does not seed this target block", blocks.active("sluhayua|NEW_ARRIVALS"))
                assertNull(snapshots.snapshot("sluhayua", FeedSnapshotPolicy.FEED_NEW_ARRIVALS))
                assertTrue(collectiveBlockSources().any { it.id == "sluhayua" })
                assertTrue(FirebaseApp.getApps(actualApp).isEmpty())
                println("S2_APP_ARRIVALS_STAGE=WARM_INDEX_STARTUP_WITHOUT_ARRIVALS")

                val rawBooks = listOf(
                    SourceBook("Свіжа перша", "Перший автор", url = "https://sluhay.com.ua/901001:live-arrivals-first",
                        coverImageUrl = "https://sluhay.com.ua/covers/live-first.jpg", sourceId = "sluhayua"),
                    SourceBook("Свіжа друга", "Другий автор", url = "https://sluhay.com.ua/901002:live-arrivals-second",
                        coverImageUrl = "https://sluhay.com.ua/covers/live-second.jpg", sourceId = "sluhayua"))
                val returnedBooks = listOf(
                    SourceBook("Свіжа перша", "Перший автор", url = "https://sluhay.com.ua/901001:live-arrivals-first",
                        coverImageUrl = "https://sluhay.com.ua/covers/live-first.jpg", sourceId = "sluhayua", language = "uk"),
                    SourceBook("Свіжа друга", "Другий автор", url = "https://sluhay.com.ua/901002:live-arrivals-second",
                        coverImageUrl = "https://sluhay.com.ua/covers/live-second.jpg", sourceId = "sluhayua", language = "uk"))
                val started = System.currentTimeMillis()
                val feeds = withTimeout(120_000L) { actualApp.sourceCatalog.refreshSourceFeeds(forceRefresh = true) }
                val ended = System.currentTimeMillis()
                assertEquals(listOf(SourceCatalog.SourceNewFeed("sluhayua", "Sluhay UA", returnedBooks)),
                    feeds.filter { it.sourceId == "sluhayua" })
                assertEquals("one arrivals response belongs to the explicit feed action", 1, requests.count { it == feedUrl })
                assertEquals(1, arrivalsResponses.get())
                val snapshot = requireNotNull(snapshots.snapshot("sluhayua", FeedSnapshotPolicy.FEED_NEW_ARRIVALS))
                assertEquals("sluhayua", snapshot.sourceId)
                assertEquals(FeedSnapshotPolicy.FEED_NEW_ARRIVALS, snapshot.feedKey)
                assertEquals(rawBooks, snapshot.books)
                assertEquals("", snapshot.parameters)
                assertTrue("ordinary observation belongs to this explicit action", snapshot.observedAt in started..ended)
                val firstWork = requireNotNull(dao.findWorkByMergeKey("свіжа перша|перший автор"))
                val secondWork = requireNotNull(dao.findWorkByMergeKey("свіжа друга|другий автор"))
                assertEquals("Свіжа перша", firstWork.title)
                assertEquals("Перший автор", firstWork.author)
                assertEquals("Свіжа друга", secondWork.title)
                assertEquals("Другий автор", secondWork.author)
                assertEquals(listOf("sluhayua" to "https://sluhay.com.ua/901001:live-arrivals-first"),
                    dao.getWorkSourcesForWorkSync(firstWork.id).map { it.sourceId to it.sourceUrl })
                assertEquals(listOf("sluhayua" to "https://sluhay.com.ua/901002:live-arrivals-second"),
                    dao.getWorkSourcesForWorkSync(secondWork.id).map { it.sourceId to it.sourceUrl })
                assertTrue("enumeration creates no listening profile", dao.getAllAudiobooksOnce().isEmpty())
                assertTrue("enumeration creates no chapter topology", dao.getAllChaptersOnce().isEmpty())
                assertTrue("enumeration creates no playable Source", dao.getSourcesByTypes(listOf("sluhayua")).isEmpty())
                assertEquals(0, dao.countLibraryEntries())
                assertTrue("startup/feed traffic stays bounded and identified", total.get() <= 128 && requests.all { it.isNotBlank() })
                assertTrue("no card, cover, play or stream is followed", requests.none { target ->
                    rawBooks.any { it.url == target || it.coverImageUrl == target } ||
                        target.startsWith("https://sluhay.com.ua/play?") || target.contains("/stream/") })
                println("S2_APP_ARRIVALS_STAGE=EXPLICIT_LIVE_FEED_AND_MIRROR_CONTROLS")
                val active = blocks.active("sluhayua|NEW_ARRIVALS")
                    ?: throw AssertionError("S2_ACTUAL_APP_ARRIVALS_MISSING_BLOCK_AFTER_EXPLICIT_LIVE_FEED")
                assertTrue("activation belongs to this explicit action", active.fetchedAt in started..ended)
                val expected = CollectiveFeedBlock(
                    "sluhayua|NEW_ARRIVALS", "sluhayua", CollectiveBlockKind.NEW_ARRIVALS,
                    "Sluhay UA", "https://sluhay.com.ua",
                    listOf(
                        CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/901001:live-arrivals-first", "Свіжа перша", "Перший автор", "https://sluhay.com.ua/covers/live-first.jpg"),
                        CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/901002:live-arrivals-second", "Свіжа друга", "Другий автор", "https://sluhay.com.ua/covers/live-second.jpg")),
                    active.fetchedAt, active.fetchedAt + 21_600_000L, 1L,
                    CollectiveAttempt(active.fetchedAt, CollectiveAttemptStatus.SUCCESS))
                assertEquals("full independent actual App arrivals block", expected, active)
                assertEquals(listOf(expected), withTimeout(30_000L) { actualApp.collectiveOverviewBlocks.read(listOf("sluhayua")) })
                assertEquals("public overview never refreshes the source", 1, requests.count { it == feedUrl })
                assertTrue("public overview follows no received card or cover", requests.none { target ->
                    rawBooks.any { it.url == target || it.coverImageUrl == target } ||
                        target.startsWith("https://sluhay.com.ua/play?") || target.contains("/stream/") })
                assertTrue("traffic remains bounded after overview read", total.get() <= 128 && requests.all { it.isNotBlank() })
                assertTrue(FirebaseApp.getApps(actualApp).isEmpty())
                println("S2_APP_ARRIVALS_STAGE=FULL_APP_ROOM_AND_PUBLIC_OVERVIEW")
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                val cleanupErrors = mutableListOf<Throwable>()
                suspend fun cleanup(action: suspend () -> Unit) {
                    try { action() } catch (error: Throwable) { cleanupErrors += error }
                }
                // Real App jobs have no public cancellation owner. Fail-close
                // both route carriers until the official owned worker exits.
                val closedRoute = PrivacyPrefs(routeMode = RouteMode.RELAY,
                    proxyAddress = (relayBase ?: "http://127.0.0.1:1") + "/closed", dohEnabled = false)
                cleanup { context?.let { SharedPreferencesPrivacySettingsStore(it).save(closedRoute) } }
                cleanup { assertEquals(RouteResolution.Ok(NetworkRoute.Relay(closedRoute.proxyAddress)), TransportPrivacy.install(closedRoute)) }
                cleanup { pumpMainLooper() }
                cleanup { database?.close() }
                cleanup { relay?.stop(0) }
                cleanup { executor?.shutdownNow() }
                cleanup { executor?.let { assertTrue("owned relay threads terminate", it.awaitTermination(5, TimeUnit.SECONDS)) } }
                cleanup {
                    pumpMainLooper()
                    assertTrue("actual main looper has no executable cleanup tasks", shadowOf(Looper.getMainLooper()).isIdle)
                }
                if (cleanupErrors.isNotEmpty()) {
                    val failure = primaryFailure
                    if (failure != null) cleanupErrors.forEach { failure.addSuppressed(it) }
                    else throw AssertionError("S2_APP_ARRIVALS_CLEANUP_FAILED").also { aggregate -> cleanupErrors.forEach { aggregate.addSuppressed(it) } }
                }
                println("S2_APP_ARRIVALS_STAGE=OWNED_TEST_RESOURCES_CLOSED")
            }
        }
    }

    private fun pumpMainLooper() {
        requireMainThread()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun requireMainThread() {
        assertSame("public App lifecycle executes on the actual main looper thread",
            Looper.getMainLooper().thread, Thread.currentThread())
        assertSame(Looper.getMainLooper(), Looper.myLooper())
    }
}
