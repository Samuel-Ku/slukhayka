package com.slukhayka.audiobooks.data.collective

import android.app.Application
import android.app.Instrumentation
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.privacy.*
import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.player.AudioPlayerManager
import com.sun.net.httpserver.HttpServer
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
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual attached App/VM and same-device Room delta; not Home/provider/backend/device proof. */
@RunWith(ControlledAppFixtureRunner::class)
@Config(sdk = [36], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ControlledAttachedAppLateRoomOverviewTest {
    // JUnit's timeout would move the body off Robolectric's main thread. The
    // coroutine body and the external single-worker supervisor own the bounds.
    @Test
    fun `actual attached App live overview publishes a later same Room block without another refresh`() = runBlocking {
        var context: Application? = null
        var player: AudioPlayerManager? = null
        var database: AudiobookDatabase? = null
        var relay: HttpServer? = null
        var relayBase: String? = null
        var executor: ExecutorService? = null
        var viewModelStore: ViewModelStore? = null
        var subscriber: Job? = null
        var subscriptionOwner: Job? = null
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
                val requests = CopyOnWriteArrayList<String>()
                val controlResponses = AtomicInteger()
                val total = AtomicInteger()
                val localExecutor = Executors.newFixedThreadPool(4).also { executor = it }
                val localRelay = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8).also { relay = it }
                localRelay.executor = localExecutor
                val controlUrl = "https://fixture.invalid/s2-late-room-control"
                localRelay.createContext("/") { exchange ->
                    try {
                        val target = exchange.requestURI.rawQuery.orEmpty().split('&')
                            .singleOrNull { it.startsWith("url=") }?.substringAfter("url=")
                            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }.orEmpty()
                        requests += target
                        val call = total.incrementAndGet()
                        val body = if (call <= 128 && target == controlUrl) {
                            controlResponses.incrementAndGet()
                            "S2_LATE_ROOM_CONTROL_OK".toByteArray(StandardCharsets.UTF_8)
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
                assertEquals("S2_LATE_ROOM_CONTROL_OK", withContext(Dispatchers.IO) { HttpFetcher().getText(controlUrl) })
                assertEquals(listOf(controlUrl), requests.toList())
                assertEquals(1, controlResponses.get())
                println("S2_LATE_ROOM_STAGE=FIXTURE_READY")

                val actualApp = Instrumentation.newApplication(App::class.java, runtime) as App
                assertSame(runtime, actualApp.baseContext)
                assertSame(runtime, actualApp.applicationContext)
                assertEquals(route, actualApp.privacySettings.load())
                Instrumentation().callApplicationOnCreate(actualApp)
                assertSame(actualApp, App.instance)
                val db = AudiobookDatabase.getDatabase(actualApp).also { database = it }
                assertSame(actualApp.audiobookDao, db.audiobookDao())
                assertTrue(collectiveBlockSources().any { it.id == "sluhayua" })
                assertEquals(emptyList<CollectiveFeedBlock>(), actualApp.collectiveOverviewBlocks.read())
                assertTrue(FirebaseApp.getApps(actualApp).isEmpty())
                println("S2_LATE_ROOM_STAGE=APP_READY")

                val expectedA = CollectiveFeedBlock(
                    "sluhayua|RECOMMENDATIONS", "sluhayua", CollectiveBlockKind.RECOMMENDATIONS,
                    "До «Тарас Бульба»", "https://sluhay.com.ua/2932269:Микола-Гоголь-Тарас-Бульба",
                    listOf(CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/7043213:Кониський-Олександр-Семен-Жук-і-його-родичі",
                        "Семен Жук і його родичі", "Кониський Олександр", "https://fixture.invalid/covers/a.png")),
                    1_000L, 86_401_000L, 1L, CollectiveAttempt(1_000L, CollectiveAttemptStatus.SUCCESS))
                val expectedB = CollectiveFeedBlock(
                    "sluhayua|RECOMMENDATIONS", "sluhayua", CollectiveBlockKind.RECOMMENDATIONS,
                    "До «Сердешна Оксана»", "https://sluhay.com.ua/5931576:grigorij-kvitka-osnovjanenko-serdjeshna-oksana",
                    listOf(
                        CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/2932269:Микола-Гоголь-Тарас-Бульба",
                            "Тарас Бульба", "Микола Гоголь", "https://fixture.invalid/covers/b.png"),
                        CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/7043213:Кониський-Олександр-Семен-Жук-і-його-родичі",
                            "Семен Жук і його родичі", "Кониський Олександр", "https://fixture.invalid/covers/a.png")),
                    2_000L, 86_402_000L, 2L, CollectiveAttempt(2_000L, CollectiveAttemptStatus.SUCCESS))
                val firstStore = RoomCollectiveFeedBlockStore(actualApp.audiobookDao)
                assertTrue(firstStore.activate(expectedA))
                assertEquals(expectedA, firstStore.active("sluhayua|RECOMMENDATIONS"))
                assertEquals(listOf(expectedA), actualApp.collectiveOverviewBlocks.read())
                println("S2_LATE_ROOM_STAGE=INITIAL_COMMIT")

                requireMainThread()
                actualApp.playerManager.also { player = it }
                val vmStore = ViewModelStore().also { viewModelStore = it }
                val vm = ViewModelProvider(vmStore, ViewModelProvider.AndroidViewModelFactory(actualApp))[MainViewModel::class.java]
                val initial = CompletableDeferred<Unit>()
                val later = CompletableDeferred<Unit>()
                val subscriberErrors = CopyOnWriteArrayList<Throwable>()
                val observedEmissions = CopyOnWriteArrayList<List<CollectiveFeedBlock>>()
                val unexpectedEmissions = CopyOnWriteArrayList<List<CollectiveFeedBlock>>()
                val owner = SupervisorJob().also { subscriptionOwner = it }
                val collector = CoroutineScope(owner + Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
                    var acknowledgedA = false
                    try {
                        vm.collectiveBlocks.collect { blocks ->
                            val observed = blocks.toList()
                            observedEmissions.add(observed)
                            println("S2_LATE_ROOM_VM_EMISSION=$observed")
                            val allowed = if (acknowledgedA) observed == listOf(expectedA) || observed == listOf(expectedB)
                                else observed.isEmpty() || observed == listOf(expectedA)
                            if (!allowed) {
                                unexpectedEmissions.add(observed)
                                throw AssertionError("S2_LATE_ROOM_UNEXPECTED_VM_EMISSION")
                            }
                            if (observed == listOf(expectedA)) {
                                acknowledgedA = true
                                initial.complete(Unit)
                            }
                            if (observed == listOf(expectedB)) later.complete(Unit)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Throwable) {
                        subscriberErrors += error
                        initial.completeExceptionally(error)
                        later.completeExceptionally(error)
                    }
                }.also { subscriber = it }
                assertTrue(collector.isActive)
                vm.refreshCollectiveBlocks()
                withTimeout(30_000L) { awaitWithMainLooper(initial) }
                assertEquals(listOf(expectedA), vm.collectiveBlocks.value)
                assertTrue(collector.isActive && subscriberErrors.isEmpty())
                println("S2_LATE_ROOM_STAGE=INITIAL_VM_PUBLICATION")

                val secondStore = RoomCollectiveFeedBlockStore(actualApp.audiobookDao)
                assertNotSame(firstStore, secondStore)
                assertSame(actualApp.audiobookDao, db.audiobookDao())
                assertTrue(secondStore.activate(expectedB))
                assertEquals(expectedB, firstStore.active("sluhayua|RECOMMENDATIONS"))
                assertEquals(expectedB, secondStore.active("sluhayua|RECOMMENDATIONS"))
                assertEquals(listOf(expectedB), actualApp.collectiveOverviewBlocks.read())
                println("S2_LATE_ROOM_STAGE=LATE_ROOM_COMMIT")
                val published = withTimeoutOrNull(10_000L) { awaitWithMainLooper(later); true }
                pumpMainLooper()
                assertEquals(expectedB, secondStore.active("sluhayua|RECOMMENDATIONS"))
                assertEquals(listOf(expectedB), actualApp.collectiveOverviewBlocks.read())
                assertTrue("the same subscriber remains alive", collector.isActive && subscriberErrors.isEmpty())
                assertTrue("all actual startup targets are bounded and identified", total.get() <= 128 && requests.all { it.isNotBlank() })
                assertEquals(1, controlResponses.get())
                assertTrue("no cover or card is requested by this projection", requests.none {
                    it.startsWith("https://fixture.invalid/covers/") || expectedA.cards.any { card -> card.sourceUrl == it } ||
                        expectedB.cards.any { card -> card.sourceUrl == it }
                })
                assertTrue(FirebaseApp.getApps(actualApp).isEmpty())
                val emissionsBeforeFinal = observedEmissions.toList()
                assertTrue("the subscriber observed complete initial A", emissionsBeforeFinal.any { it == listOf(expectedA) })
                assertTrue("no unexpected or partial publication qualifies as a missing B", unexpectedEmissions.isEmpty())
                println("S2_LATE_ROOM_VM_EMISSIONS_BEFORE_FINAL=$emissionsBeforeFinal")
                if (published == null) {
                    assertEquals("a missing B must leave the actual VM at complete A", listOf(expectedA), vm.collectiveBlocks.value)
                    assertTrue("an observed B is not a missing publication", emissionsBeforeFinal.none { it == listOf(expectedB) })
                    assertTrue("the subscriber remains alive without errors before missing-B classification",
                        collector.isActive && subscriberErrors.isEmpty() && unexpectedEmissions.isEmpty())
                    println("S2_LATE_ROOM_STAGE=FINAL_CONTROLS_READY")
                    throw AssertionError("S2_LATE_ROOM_FINAL_MISSING_VM_B_AFTER_COMMITTED_ROOM_AND_PUBLIC_APP_B")
                }
                println("S2_LATE_ROOM_STAGE=FINAL_CONTROLS_READY")
                assertEquals(listOf(expectedB), vm.collectiveBlocks.value)
                println("S2_LATE_ROOM_STAGE=FINAL_VM_PUBLICATION")
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
                cleanup { withTimeout(5_000L) { subscriber?.cancelAndJoin() } }
                cleanup { withTimeout(5_000L) { subscriptionOwner?.cancelAndJoin() } }
                cleanup { requireMainThread(); viewModelStore?.clear() }
                cleanup { requireMainThread(); player?.release() }
                // Public VM/player cleanup does not close unscoped App modules.
                // Keep both persisted and live transport fail-closed until the owned worker exits.
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
                    else throw AssertionError("S2_LATE_ROOM_CLEANUP_FAILED").also { aggregate -> cleanupErrors.forEach { aggregate.addSuppressed(it) } }
                }
                println("S2_LATE_ROOM_STAGE=OWNED_TEST_RESOURCES_CLOSED")
            }
        }
    }

    /** Execute real due main messages; PAUSED mode does not do this while runBlocking awaits IO. */
    private fun pumpMainLooper() {
        requireMainThread()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The caller owns the original wall-time deadline; no virtual clock or dispatcher is substituted. */
    private suspend fun awaitWithMainLooper(signal: Deferred<Unit>) {
        requireMainThread()
        val pumped = CompletableDeferred<Unit>()
        assertTrue("actual main Handler accepts the pump control",
            Handler(Looper.getMainLooper()).post { pumped.complete(Unit) })
        while (true) {
            pumpMainLooper()
            assertTrue("actual main Handler control executed", pumped.isCompleted)
            if (signal.isCompleted) {
                signal.await()
                return
            }
            delay(10L)
        }
    }

    private fun requireMainThread() {
        assertSame("public App/VM/player lifecycle must execute on the actual main looper thread",
            Looper.getMainLooper().thread, Thread.currentThread())
        assertSame(Looper.getMainLooper(), Looper.myLooper())
    }
}
