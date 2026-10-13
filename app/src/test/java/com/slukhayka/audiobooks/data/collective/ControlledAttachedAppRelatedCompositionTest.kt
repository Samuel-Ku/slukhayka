package com.slukhayka.audiobooks.data.collective

import android.app.Application
import android.app.Instrumentation
import android.content.pm.PackageManager
import com.google.firebase.FirebaseOptions
import java.security.MessageDigest
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.entries.LibraryEntryOrigin
import com.slukhayka.audiobooks.data.privacy.NetworkRoute
import com.slukhayka.audiobooks.data.privacy.PrivacyPrefs
import com.slukhayka.audiobooks.data.privacy.SharedPreferencesPrivacySettingsStore
import com.slukhayka.audiobooks.data.privacy.RouteMode
import com.slukhayka.audiobooks.data.privacy.RouteResolution
import com.slukhayka.audiobooks.data.privacy.TransportPrivacy
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Controlled attached actual App lifecycle; not normal provider startup, Home, backend or playback. */
@RunWith(ControlledAppFixtureRunner::class)
@Config(sdk = [36], application = Application::class)
class ControlledAttachedAppRelatedCompositionTest {
    @Test(timeout = 180_000L)
    fun `actual attached App public import persists observed related cards for its public overview reader`() = runBlocking {
        println("S2_ACTUAL_TEST_WORKER_PID=${ProcessHandle.current().pid()}")
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(Application::class.java, context.javaClass)
        @Suppress("DEPRECATION")
        val declared = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS or
                PackageManager.GET_SERVICES or PackageManager.GET_ACTIVITIES)
        assertTrue("provider-free fixture is selected before runtime setup", declared.providers.isNullOrEmpty())
        assertTrue(declared.receivers.isNullOrEmpty() && declared.services.isNullOrEmpty() && declared.activities.isNullOrEmpty())
        val seed = context.assets.open("catalog_seed.json").use { it.readBytes() }
        assertEquals("real packaged asset remains accessible byte-for-byte",
            "9502be0c57dcd7fa001f875cd3eba38f18fc6b08484e7702f7246efdc8b2a10d",
            MessageDigest.getInstance("SHA-256").digest(seed).joinToString("") { "%02x".format(it.toInt() and 255) })
        assertNull("actual Firebase factory has no resource configuration", FirebaseOptions.fromResource(context))
        assertTrue("this fixture must have no configured Firebase credentials", FirebaseApp.getApps(context).isEmpty())
        assertEquals("no generated Firebase resource is present", 0,
            context.resources.getIdentifier("google_app_id", "string", context.packageName))
        assertNull("actual App must use its default Room factory", AudiobookDatabase.databaseNameOverride)
        val pageUrl = "https://sluhay.com.ua/5931576:grigorij-kvitka-osnovjanenko-serdjeshna-oksana"
        val html = requireNotNull(javaClass.getResourceAsStream("/s2-app-composition-serdeshna.html"))
            .use { it.readBytes() }
        val requests = CopyOnWriteArrayList<String>()
        val total = AtomicInteger()
        val executor = Executors.newFixedThreadPool(4)
        val relay = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8)
        relay.executor = executor
        relay.createContext("/") { exchange ->
            try {
                val query = exchange.requestURI.rawQuery.orEmpty()
                val encodedTarget = query.split('&').singleOrNull { it.startsWith("url=") }?.substringAfter("url=")
                val target = encodedTarget?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }.orEmpty()
                requests += target
                val call = total.incrementAndGet()
                val body = when {
                    call > 128 -> ByteArray(0)
                    target == pageUrl -> html
                    target.matches(Regex("https://sluhay\\.com\\.ua/play\\?bookId=5931576&fileId=[0-6]")) ->
                        "https://fixture.invalid/selected-5931576-${target.substringAfter("fileId=")}.mp3".toByteArray()
                    else -> ByteArray(0)
                }
                exchange.sendResponseHeaders(if (body.isNotEmpty()) 200 else 404, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } finally { exchange.close() }
        }
        relay.start()
        val relayBase = "http://127.0.0.1:${relay.address.port}"
        // The actual App is attached by the public Android framework API before its real onCreate.
        val route = PrivacyPrefs(routeMode = RouteMode.RELAY, proxyAddress = relayBase, dohEnabled = false)
        try {
            // Configure the public persisted privacy seam before constructing the real App.
            SharedPreferencesPrivacySettingsStore(context).save(route)
            assertEquals(RouteResolution.Ok(NetworkRoute.Relay(relayBase)), TransportPrivacy.install(route))
            val app = Instrumentation.newApplication(App::class.java, context) as App
            assertEquals(App::class.java, app.javaClass)
            assertSame("manual attachment uses the runtime-owned base context", context, app.baseContext)
            assertSame("manual fixture does not replace registered runtime Application", context, app.applicationContext)
            assertEquals(route, app.privacySettings.load())
            assertEquals(RouteResolution.Ok(NetworkRoute.Relay(relayBase)), TransportPrivacy.install(route))
            assertTrue(FirebaseApp.getApps(app).isEmpty())
            assertNull(app.sharedMetaStore)
            val dao = app.audiobookDao
            assertTrue("fresh runtime-owned Room starts without imported books", dao.getAllAudiobooksOnce().isEmpty())
            assertEquals(0, dao.countWorks())
            assertNull(RoomCollectiveFeedBlockStore(dao).active("sluhayua|RECOMMENDATIONS"))
            Instrumentation().callApplicationOnCreate(app)
            assertSame("actual onCreate installs the normal App singleton", app, App.instance)
            assertEquals(NetworkRoute.Relay(relayBase), TransportPrivacy.current())
            assertTrue("no Firebase app or credentials were initialized", FirebaseApp.getApps(app).isEmpty())
            val started = System.currentTimeMillis()
            val imported = requireNotNull(withTimeout(120_000L) {
                app.libraryImport.importFromSourceUrl("sluhayua", pageUrl, origin = LibraryEntryOrigin.EXPLICIT_SAVE)
            })
            val ended = System.currentTimeMillis()
            assertEquals("Сердешна Оксана", imported.title)
            assertEquals("Григорій Квітка-Основяненко", imported.author)
            val persisted = requireNotNull(RoomCollectiveFeedBlockStore(dao).active("sluhayua|RECOMMENDATIONS"))
            assertTrue("activation time belongs to this public import", persisted.fetchedAt in started..ended)
            val expected = CollectiveFeedBlock(
                blockKey = "sluhayua|RECOMMENDATIONS", sourceId = "sluhayua",
                kind = CollectiveBlockKind.RECOMMENDATIONS, name = "До «Сердешна Оксана»", provenanceUrl = pageUrl,
                cards = listOf(
                    CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/7056710:Іvan-karpenko-karij-burlaka", "Іван Карпенко-Карий — Бурлака", "", null),
                    CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/7043213:Кониський-Олександр-Семен-Жук-і-його-родичі", "Семен Жук і його родичі", "Кониський Олександр", null),
                    CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/2932269:Микола-Гоголь-Тарас-Бульба", "Тарас Бульба", "Микола Гоголь", null),
                    CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/9939782:kashchenko-adjrіan-borcі-za-pravdju", "Борці за правду", "Кащенко Адріан", null),
                    CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/9893312:mark-lіvіn-babine-lіto", "Бабине Літо", "Марк Лівін", null),
                    CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/7127531:adjrian-kashchenko-nadj-kodjackim-porogom", "Над кодацьким порогом", "Адриан Кащенко", null),
                    CollectiveBlockCard("sluhayua", "https://sluhay.com.ua/7681734:valerjan-pіdjmogilnij-povіst-bez-nazvi", "Валер'ян Підмогильний — Повість без назви", "", null)
                ), fetchedAt = persisted.fetchedAt, staleAfter = persisted.fetchedAt + 86_400_000L,
                version = 1L, lastAttempt = CollectiveAttempt(persisted.fetchedAt, CollectiveAttemptStatus.SUCCESS)
            )
            assertEquals("complete independently literal observed block", expected, persisted)
            assertEquals(listOf(expected), withTimeout(30_000L) { app.collectiveOverviewBlocks.read(listOf("sluhayua")) })
            assertEquals(listOf(imported.id), dao.getAllAudiobooksOnce().map { it.id })
            assertEquals(7, dao.getAllChaptersOnce().size)
            assertTrue(dao.getAllChaptersOnce().all { it.bookId == imported.id })
            assertEquals(1, dao.getSourcesByTypes(listOf("sluhayua")).size)
            assertEquals(1, dao.countWorks())
            assertEquals(1, dao.countWorkSources())
            assertEquals(1, dao.countLibraryEntries())
            assertEquals("explicit page received once", 1, requests.count { it == pageUrl })
            for (fileId in 0..6) assertEquals("only selected book's chapter recipe resolves", 1,
                requests.count { it == "https://sluhay.com.ua/play?bookId=5931576&fileId=$fileId" })
            assertTrue("every received target is bounded and identified", requests.all { it.isNotBlank() } && total.get() <= 128)
            assertTrue("no related link is requested", expected.cards.none { card -> requests.contains(card.sourceUrl) })
            assertTrue("no other book page is requested", requests.none { Regex("https://sluhay\\.com\\.ua/\\d+:.*").matches(it) && it != pageUrl })
            assertTrue("no stream is fetched", requests.none { it.startsWith("https://fixture.invalid/") })
            assertNull(RoomCollectiveFeedBlockStore(dao).active("sluhayua|COLLECTIONS"))
            assertTrue(FirebaseApp.getApps(app).isEmpty())
        } finally {
            // App.onTerminate does not cancel unscoped startup jobs. No cancellation is claimed.
            // Unscoped real startup jobs have no public cancellation owner. Keep the route fail-closed
            // at this soon-closed local relay until this selected single-test worker exits; NEVER direct.
            TransportPrivacy.install(PrivacyPrefs(routeMode = RouteMode.RELAY,
                proxyAddress = "$relayBase/closed", dohEnabled = false))
            relay.stop(0)
            executor.shutdownNow()
            assertTrue("owned local relay threads terminate", executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
