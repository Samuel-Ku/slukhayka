package com.slukhayka.audiobooks.data.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.collective.*
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.source.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real import/catalogue and persisted block boundaries; source transport is an external fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SourceDetailObservationGuardTest {
    private lateinit var db: AudiobookDatabase
    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    private val pageUrl = "https://sluhay.com.ua/100:tyhrolovy"
    private val key = "sluhayua|RECOMMENDATIONS"
    private fun detail() = SourceBookDetail("Тигролови", "Іван Багряний", url = pageUrl,
        chapters = listOf(SourceChapter("Розділ 1", "https://mp3.sluhay.com.ua/100/1.mp3")),
        related = listOf(RelatedBook("Місто", "Валер’ян Підмогильний", "https://sluhay.com.ua/102:misto")))
    private class Adapter(var detail: SourceBookDetail) : SourceAdapter {
        override val sourceId = "sluhayua"
        val requests = mutableListOf<String>()
        override suspend fun search(query: String): List<SourceBook> = error("no search")
        override suspend fun fetchBookPage(url: String): SourceBookDetail { requests += url; return detail }
        override suspend fun fetchNew(limit: Int): List<SourceBook> = error("no feed")
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = error("no catalogue")
    }

    private suspend fun captureDoorCancellation(operation: suspend () -> Unit): java.util.concurrent.CancellationException? =
        try { operation(); null } catch (cancelled: java.util.concurrent.CancellationException) { cancelled }

    private fun assertDoorCancellation(original: java.util.concurrent.CancellationException,
        actual: java.util.concurrent.CancellationException?) {
        assertNotNull("public source operation must propagate cancellation", actual)
        assertEquals(original.javaClass, actual!!.javaClass)
        assertEquals(original.message, actual.message)
        assertTrue("original cancellation identity survives bounded coroutine recovery",
            generateSequence<Throwable>(actual) { it.cause }.take(8).any { it === original })
    }

    @Test
    fun `browser direct fetch cancellation escapes the public page import`() = runBlocking {
        val ownUrl = "https://sluhay.com/cancel-observer-fixture"
        val resolved = detail().copy(url = ownUrl, chapters = emptyList())
        val cancelled = java.util.concurrent.CancellationException("browser fetch cancelled")
        val requests = mutableListOf<String>()
        val received = mutableListOf<Pair<String, SourceBookDetail>>()
        val adapter = object : SourceAdapter {
            override val sourceId = "sluhay"
            override suspend fun search(query: String): List<SourceBook> = error("no search")
            override suspend fun fetchNew(limit: Int): List<SourceBook> = error("no feed")
            override suspend fun fetchCatalog(limit: Int): List<SourceBook> = error("no catalogue")
            override suspend fun fetchBookPage(url: String): SourceBookDetail {
                requests += url
                throw cancelled
            }
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = { id, value ->
            received += id to value
            throw cancelled
        })
        assertEquals(SourceAccessMode.BROWSER, SourceAccessPolicy.modeFor("sluhay"))
        assertFalse(SourceRegistry.isScam("sluhay"))
        assertTrue(requests.isEmpty())
        assertTrue(received.isEmpty())
        val actual = captureDoorCancellation { imports.importBrowserSourceDirectPage("sluhay", ownUrl) }
        assertEquals(listOf(ownUrl), requests)
        assertEquals(emptyList<Pair<String, SourceBookDetail>>(), received)
        assertTrue(db.audiobookDao().getAllAudiobooksOnce().isEmpty())
        assertDoorCancellation(cancelled, actual)
    }

    @Test
    fun `browser direct observer cancellation escapes the public page import`() = runBlocking {
        val ownUrl = "https://sluhay.com/cancel-observer-fixture"
        val resolved = detail().copy(url = ownUrl, chapters = emptyList())
        val cancelled = java.util.concurrent.CancellationException("browser observer cancelled")
        val requests = mutableListOf<String>()
        val received = mutableListOf<Pair<String, SourceBookDetail>>()
        val adapter = object : SourceAdapter {
            override val sourceId = "sluhay"
            override suspend fun search(query: String): List<SourceBook> = error("no search")
            override suspend fun fetchNew(limit: Int): List<SourceBook> = error("no feed")
            override suspend fun fetchCatalog(limit: Int): List<SourceBook> = error("no catalogue")
            override suspend fun fetchBookPage(url: String): SourceBookDetail {
                requests += url
                // successful already-received detail
                return resolved
            }
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = { id, value ->
            received += id to value
            throw cancelled
        })
        assertEquals(SourceAccessMode.BROWSER, SourceAccessPolicy.modeFor("sluhay"))
        assertFalse(SourceRegistry.isScam("sluhay"))
        assertTrue(requests.isEmpty())
        assertTrue(received.isEmpty())
        val actual = captureDoorCancellation { imports.importBrowserSourceDirectPage("sluhay", ownUrl) }
        assertEquals(listOf(ownUrl), requests)
        assertEquals(listOf("sluhay" to resolved), received)
        assertTrue(db.audiobookDao().getAllAudiobooksOnce().isEmpty())
        assertDoorCancellation(cancelled, actual)
    }

    @Test
    fun `explicit hydration catalogue fetch cancellation escapes instead of an empty or failed result`() = runBlocking {
        val cancelled = java.util.concurrent.CancellationException("hydration catalogue fetch cancelled")
        val resolved = detail()
        val limits = mutableListOf<Int>()
        val requests = mutableListOf<String>()
        val received = mutableListOf<Pair<String, SourceBookDetail>>()
        val adapter = object : SourceAdapter {
            override val sourceId = "sluhayua"
            override suspend fun search(query: String): List<SourceBook> = error("no search")
            override suspend fun fetchNew(limit: Int): List<SourceBook> = error("no feed")
            override suspend fun fetchCatalog(limit: Int): List<SourceBook> {
                limits += limit
                throw cancelled
            }
            override suspend fun fetchBookPage(url: String): SourceBookDetail {
                requests += url
                // successful already-received detail
                return resolved
            }
        }
        val observe: suspend (String, SourceBookDetail) -> Unit = { id, value ->
            received += id to value
            throw cancelled
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observe)
        val catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        assertTrue(limits.isEmpty())
        assertTrue(requests.isEmpty())
        assertTrue(received.isEmpty())
        val actual = captureDoorCancellation { catalog.hydrateWebSourceCatalog("sluhayua", limit = 1) }
        assertEquals(listOf(1), limits)
        assertEquals(emptyList<String>(), requests)
        assertEquals(emptyList<Pair<String, SourceBookDetail>>(), received)
        assertTrue("cancelled hydration imports no library work", db.audiobookDao().getAllAudiobooksOnce().isEmpty())
        assertDoorCancellation(cancelled, actual)
    }

    @Test
    fun `explicit hydration detail fetch cancellation escapes instead of an empty or failed result`() = runBlocking {
        val cancelled = java.util.concurrent.CancellationException("hydration detail fetch cancelled")
        val resolved = detail()
        val limits = mutableListOf<Int>()
        val requests = mutableListOf<String>()
        val received = mutableListOf<Pair<String, SourceBookDetail>>()
        val adapter = object : SourceAdapter {
            override val sourceId = "sluhayua"
            override suspend fun search(query: String): List<SourceBook> = error("no search")
            override suspend fun fetchNew(limit: Int): List<SourceBook> = error("no feed")
            override suspend fun fetchCatalog(limit: Int): List<SourceBook> {
                limits += limit
                // existing explicit catalogue result
                return listOf(SourceBook("Тигролови", "Іван Багряний", url = pageUrl, sourceId = "sluhayua"))
            }
            override suspend fun fetchBookPage(url: String): SourceBookDetail {
                requests += url
                throw cancelled
            }
        }
        val observe: suspend (String, SourceBookDetail) -> Unit = { id, value ->
            received += id to value
            throw cancelled
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observe)
        val catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        assertTrue(limits.isEmpty())
        assertTrue(requests.isEmpty())
        assertTrue(received.isEmpty())
        val actual = captureDoorCancellation { catalog.hydrateWebSourceCatalog("sluhayua", limit = 1) }
        assertEquals(listOf(1), limits)
        assertEquals(listOf(pageUrl), requests)
        assertEquals(emptyList<Pair<String, SourceBookDetail>>(), received)
        assertTrue("cancelled hydration imports no library work", db.audiobookDao().getAllAudiobooksOnce().isEmpty())
        assertDoorCancellation(cancelled, actual)
    }

    @Test
    fun `explicit hydration observer cancellation escapes instead of an empty or failed result`() = runBlocking {
        val cancelled = java.util.concurrent.CancellationException("hydration observer cancelled")
        val resolved = detail()
        val limits = mutableListOf<Int>()
        val requests = mutableListOf<String>()
        val received = mutableListOf<Pair<String, SourceBookDetail>>()
        val adapter = object : SourceAdapter {
            override val sourceId = "sluhayua"
            override suspend fun search(query: String): List<SourceBook> = error("no search")
            override suspend fun fetchNew(limit: Int): List<SourceBook> = error("no feed")
            override suspend fun fetchCatalog(limit: Int): List<SourceBook> {
                limits += limit
                // existing explicit catalogue result
                return listOf(SourceBook("Тигролови", "Іван Багряний", url = pageUrl, sourceId = "sluhayua"))
            }
            override suspend fun fetchBookPage(url: String): SourceBookDetail {
                requests += url
                // successful already-received detail
                return resolved
            }
        }
        val observe: suspend (String, SourceBookDetail) -> Unit = { id, value ->
            received += id to value
            throw cancelled
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observe)
        val catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        assertTrue(limits.isEmpty())
        assertTrue(requests.isEmpty())
        assertTrue(received.isEmpty())
        val actual = captureDoorCancellation { catalog.hydrateWebSourceCatalog("sluhayua", limit = 1) }
        assertEquals(listOf(1), limits)
        assertEquals(listOf(pageUrl), requests)
        assertEquals(listOf("sluhayua" to resolved), received)
        assertTrue("cancelled hydration imports no library work", db.audiobookDao().getAllAudiobooksOnce().isEmpty())
        assertDoorCancellation(cancelled, actual)
    }

    @Test
    fun `unsupported source feed detail fetch cancellation escapes the public search`() = runBlocking {
        val ownUrl = "https://sound-books.net/cancel-observer-fixture.html"
        val resolved = detail().copy(url = ownUrl, narrator = "Живий голос")
        val cancelled = java.util.concurrent.CancellationException("feed detail fetch cancelled")
        var feeds = 0
        val requests = mutableListOf<String>()
        val received = mutableListOf<Pair<String, SourceBookDetail>>()
        val adapter = object : SourceAdapter {
            override val sourceId = "soundbooks"
            override val supportsSearch = false
            override suspend fun search(query: String): List<SourceBook> = error("unsupported endpoint must not run")
            override suspend fun fetchCatalog(limit: Int): List<SourceBook> = error("no catalogue")
            override suspend fun fetchNew(limit: Int): List<SourceBook> {
                feeds++
                return listOf(SourceBook("Тигролови", "", url = ownUrl, sourceId = "soundbooks"))
            }
            override suspend fun fetchBookPage(url: String): SourceBookDetail {
                requests += url
                throw cancelled
            }
        }
        val observe: suspend (String, SourceBookDetail) -> Unit = { id, value ->
            received += id to value
            throw cancelled
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observe)
        val catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        assertEquals(0, feeds)
        assertTrue(requests.isEmpty())
        assertTrue(received.isEmpty())
        val actual = captureDoorCancellation { catalog.searchSource(adapter, "Тигролови") }
        assertEquals(1, feeds)
        assertEquals(listOf(ownUrl), requests)
        assertEquals(emptyList<Pair<String, SourceBookDetail>>(), received)
        assertTrue(db.audiobookDao().getAllAudiobooksOnce().isEmpty())
        assertDoorCancellation(cancelled, actual)
    }

    @Test
    fun `unsupported source feed detail observer cancellation escapes the public search`() = runBlocking {
        val ownUrl = "https://sound-books.net/cancel-observer-fixture.html"
        val resolved = detail().copy(url = ownUrl, narrator = "Живий голос")
        val cancelled = java.util.concurrent.CancellationException("feed detail observer cancelled")
        var feeds = 0
        val requests = mutableListOf<String>()
        val received = mutableListOf<Pair<String, SourceBookDetail>>()
        val adapter = object : SourceAdapter {
            override val sourceId = "soundbooks"
            override val supportsSearch = false
            override suspend fun search(query: String): List<SourceBook> = error("unsupported endpoint must not run")
            override suspend fun fetchCatalog(limit: Int): List<SourceBook> = error("no catalogue")
            override suspend fun fetchNew(limit: Int): List<SourceBook> {
                feeds++
                return listOf(SourceBook("Тигролови", "", url = ownUrl, sourceId = "soundbooks"))
            }
            override suspend fun fetchBookPage(url: String): SourceBookDetail {
                requests += url
                // successful already-received detail
                return resolved
            }
        }
        val observe: suspend (String, SourceBookDetail) -> Unit = { id, value ->
            received += id to value
            throw cancelled
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observe)
        val catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        assertEquals(0, feeds)
        assertTrue(requests.isEmpty())
        assertTrue(received.isEmpty())
        val actual = captureDoorCancellation { catalog.searchSource(adapter, "Тигролови") }
        assertEquals(1, feeds)
        assertEquals(listOf(ownUrl), requests)
        assertEquals(listOf("soundbooks" to resolved), received)
        assertTrue(db.audiobookDao().getAllAudiobooksOnce().isEmpty())
        assertDoorCancellation(cancelled, actual)
    }

    @Test
    fun `own source chapter materialization fetch cancellation preserves the generic seed`() = runBlocking {
        val seed = detail().copy(chapters = emptyList(), totalDurationSeconds = 0L,
            narrator = "Збережений голос", language = "uk")
        val cancelled = java.util.concurrent.CancellationException("materialize fetch cancelled")
        val requests = mutableListOf<String>()
        val received = mutableListOf<Pair<String, SourceBookDetail>>()
        val adapter = object : SourceAdapter {
            override val sourceId = "sluhayua"
            override suspend fun search(query: String): List<SourceBook> = error("no search")
            override suspend fun fetchNew(limit: Int): List<SourceBook> = error("no feed")
            override suspend fun fetchCatalog(limit: Int): List<SourceBook> = error("no catalogue")
            override suspend fun fetchBookPage(url: String): SourceBookDetail {
                requests += url
                throw cancelled
            }
        }
        val observe: suspend (String, SourceBookDetail) -> Unit = { id, value ->
            received += id to value
            throw cancelled
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observe)
        val catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        val imported = imports.importBookFromSource("sluhayua", seed, writeBackProfile = false)
        val sources = db.audiobookDao().getSourcesForBookSync(imported.id)
        val edition = db.audiobookDao().getEditionForWork(imported.id)!!
        assertEquals(com.slukhayka.audiobooks.data.EditionId.forBook(
            MergeKey.keyFor("Тигролови", "Іван Багряний"), imported.id, "Збережений голос", "uk"), edition.id)
        assertEquals("sluhayua-${edition.id}", sources.single().id)
        assertEquals("sluhayua", sources.single().type)
        assertEquals(pageUrl, sources.single().url)
        assertTrue(requests.isEmpty())
        assertTrue("generic seed is nonobserving", received.isEmpty())
        val actual = captureDoorCancellation { catalog.getPlayableChapters(imported.id) }
        assertEquals(listOf(pageUrl), requests)
        assertEquals(emptyList<Pair<String, SourceBookDetail>>(), received)
        assertEquals(sources, db.audiobookDao().getSourcesForBookSync(imported.id))
        assertEquals(edition, db.audiobookDao().getEditionForWork(imported.id))
        assertTrue(db.audiobookDao().getChaptersListForBook(imported.id).isEmpty())
        assertTrue(db.audiobookDao().getTracksForSourceSync(sources.single().id).isEmpty())
        assertEquals(0L, db.audiobookDao().getAudiobookById(imported.id)!!.totalDurationSeconds)
        assertNull(db.audiobookDao().findByMergeKey(MergeKey.keyFor("Місто", "Валер’ян Підмогильний")))
        assertDoorCancellation(cancelled, actual)
    }

    @Test
    fun `own source chapter materialization observer cancellation preserves the generic seed`() = runBlocking {
        val seed = detail().copy(chapters = emptyList(), totalDurationSeconds = 0L,
            narrator = "Збережений голос", language = "uk")
        val cancelled = java.util.concurrent.CancellationException("materialize observer cancelled")
        val requests = mutableListOf<String>()
        val received = mutableListOf<Pair<String, SourceBookDetail>>()
        val adapter = object : SourceAdapter {
            override val sourceId = "sluhayua"
            override suspend fun search(query: String): List<SourceBook> = error("no search")
            override suspend fun fetchNew(limit: Int): List<SourceBook> = error("no feed")
            override suspend fun fetchCatalog(limit: Int): List<SourceBook> = error("no catalogue")
            override suspend fun fetchBookPage(url: String): SourceBookDetail {
                requests += url
                // successful already-received empty-chapter detail
                return seed
            }
        }
        val observe: suspend (String, SourceBookDetail) -> Unit = { id, value ->
            received += id to value
            throw cancelled
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observe)
        val catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        val imported = imports.importBookFromSource("sluhayua", seed, writeBackProfile = false)
        val sources = db.audiobookDao().getSourcesForBookSync(imported.id)
        val edition = db.audiobookDao().getEditionForWork(imported.id)!!
        assertEquals(com.slukhayka.audiobooks.data.EditionId.forBook(
            MergeKey.keyFor("Тигролови", "Іван Багряний"), imported.id, "Збережений голос", "uk"), edition.id)
        assertEquals("sluhayua-${edition.id}", sources.single().id)
        assertEquals("sluhayua", sources.single().type)
        assertEquals(pageUrl, sources.single().url)
        assertTrue(requests.isEmpty())
        assertTrue("generic seed is nonobserving", received.isEmpty())
        val actual = captureDoorCancellation { catalog.getPlayableChapters(imported.id) }
        assertEquals(listOf(pageUrl), requests)
        assertEquals(listOf("sluhayua" to seed), received)
        assertEquals(sources, db.audiobookDao().getSourcesForBookSync(imported.id))
        assertEquals(edition, db.audiobookDao().getEditionForWork(imported.id))
        assertTrue(db.audiobookDao().getChaptersListForBook(imported.id).isEmpty())
        assertTrue(db.audiobookDao().getTracksForSourceSync(sources.single().id).isEmpty())
        assertEquals(0L, db.audiobookDao().getAudiobookById(imported.id)!!.totalDurationSeconds)
        assertNull(db.audiobookDao().findByMergeKey(MergeKey.keyFor("Місто", "Валер’ян Підмогильний")))
        assertDoorCancellation(cancelled, actual)
    }

    @Test
    fun `candidate verification fetch cancellation escapes instead of a successful verdict`() = runBlocking {
        val resolved = detail()
        val cancelled = java.util.concurrent.CancellationException("candidate fetch cancelled")
        val requests = mutableListOf<String>()
        val received = mutableListOf<Pair<String, SourceBookDetail>>()
        val adapter = object : SourceAdapter {
            override val sourceId = "sluhayua"
            override suspend fun search(query: String): List<SourceBook> = error("no search")
            override suspend fun fetchNew(limit: Int): List<SourceBook> = error("no feed")
            override suspend fun fetchCatalog(limit: Int): List<SourceBook> = error("no catalogue")
            override suspend fun fetchBookPage(url: String): SourceBookDetail {
                requests += url
                throw cancelled
            }
        }
        val observe: suspend (String, SourceBookDetail) -> Unit = { id, value ->
            received += id to value
            throw cancelled
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observe)
        val catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        assertTrue(requests.isEmpty())
        assertTrue(received.isEmpty())
        val actual = captureDoorCancellation { catalog.verifyIndexCandidate("sluhayua", pageUrl) }
        assertEquals(listOf(pageUrl), requests)
        assertEquals(emptyList<Pair<String, SourceBookDetail>>(), received)
        assertTrue(db.audiobookDao().getAllAudiobooksOnce().isEmpty())
        assertDoorCancellation(cancelled, actual)
    }

    @Test
    fun `candidate verification observer cancellation escapes instead of a successful verdict`() = runBlocking {
        val resolved = detail()
        val cancelled = java.util.concurrent.CancellationException("candidate observer cancelled")
        val requests = mutableListOf<String>()
        val received = mutableListOf<Pair<String, SourceBookDetail>>()
        val adapter = object : SourceAdapter {
            override val sourceId = "sluhayua"
            override suspend fun search(query: String): List<SourceBook> = error("no search")
            override suspend fun fetchNew(limit: Int): List<SourceBook> = error("no feed")
            override suspend fun fetchCatalog(limit: Int): List<SourceBook> = error("no catalogue")
            override suspend fun fetchBookPage(url: String): SourceBookDetail {
                requests += url
                // existing successful source detail
                return resolved
            }
        }
        val observe: suspend (String, SourceBookDetail) -> Unit = { id, value ->
            received += id to value
            throw cancelled
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observe)
        val catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        assertTrue(requests.isEmpty())
        assertTrue(received.isEmpty())
        val actual = captureDoorCancellation { catalog.verifyIndexCandidate("sluhayua", pageUrl) }
        assertEquals(listOf(pageUrl), requests)
        assertEquals(listOf("sluhayua" to resolved), received)
        assertTrue(db.audiobookDao().getAllAudiobooksOnce().isEmpty())
        assertDoorCancellation(cancelled, actual)
    }

    @Test
    fun `a fresh shared profile imports cached tracks without any live detail observation`() = runBlocking {
        val adapter = Adapter(detail())
        val received = mutableListOf<Pair<String, SourceBookDetail>>()
        val profileReads = mutableListOf<Pair<String, String>>()
        var profileWrites = 0
        val profile = com.slukhayka.audiobooks.data.metadata.BookProfile(
            title = "Кешований профіль", author = "Іван Багряний", narrator = "Кешований голос",
            description = "Кешований опис.", chapters = listOf(
                com.slukhayka.audiobooks.data.metadata.ProfileChapter("Кешований розділ",
                    "https://mp3.sluhay.com.ua/100/cached-profile.mp3", 900L)), totalDurationSeconds = 900L)
        val store = object : com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore {
            override suspend fun getDuration(editionId: String): Long? = null
            override suspend fun getDurations(editionIds: List<String>): Map<String, Long> = emptyMap()
            override suspend fun putDuration(editionId: String, durationSeconds: Long,
                provenance: com.slukhayka.audiobooks.data.metadata.DurationProvenance) = Unit
            override suspend fun getProfile(sourceId: String, editionId: String):
                com.slukhayka.audiobooks.data.metadata.BookProfile? = error("must use stamped entry")
            override suspend fun getProfileEntry(sourceId: String, editionId: String):
                com.slukhayka.audiobooks.data.metadata.SharedProfileEntry {
                profileReads += sourceId to editionId
                return com.slukhayka.audiobooks.data.metadata.SharedProfileEntry(profile, System.currentTimeMillis())
            }
            override suspend fun putProfile(sourceId: String, editionId: String,
                profile: com.slukhayka.audiobooks.data.metadata.BookProfile,
                provenance: com.slukhayka.audiobooks.data.metadata.ProfileProvenance) { profileWrites++ }
            override suspend fun getCover(mergeKey: String): String? = null
            override suspend fun getCovers(mergeKeys: List<String>): Map<String, String> = emptyMap()
            override suspend fun putCover(mergeKey: String, coverUrl: String,
                provenance: com.slukhayka.audiobooks.data.metadata.CoverProvenance) = Unit
        }
        val local = RoomCollectiveFeedBlockStore(db.audiobookDao())
        val published = mutableListOf<CollectiveFeedBlock>()
        val refresh = CollectiveFeedRefresh(local, InMemoryCollectiveRefreshLease(),
            fetch = { error("no implicit source fetch") }, clock = { 1_000L }, onActivated = { published += it })
        lateinit var catalog: SourceCatalog
        val observe: suspend (String, SourceBookDetail) -> Unit = { sourceId, resolved ->
            received += sourceId to resolved
            refresh.observeExplicit(key) { catalog.collectiveRelatedBlock(sourceId, resolved) }
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), profileStore = store,
            onSourceDetailObserved = observe)
        catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        assertTrue(adapter.requests.isEmpty())
        assertTrue(profileReads.isEmpty())
        assertTrue(received.isEmpty())
        val imported = imports.importFromSourceUrl("sluhayua", pageUrl,
            known = com.slukhayka.audiobooks.data.imports.KnownBookIdentity(
                "Тигролови", "Іван Багряний", "Кешований голос"))!!
        val readEdition = com.slukhayka.audiobooks.data.EditionId.forBook(
            MergeKey.keyFor("Тигролови", "Іван Багряний"), "", "Кешований голос", "")
        assertEquals(listOf("sluhayua" to readEdition), profileReads)
        assertEquals(0, profileWrites)
        assertEquals("Тигролови", imported.title)
        assertEquals("Іван Багряний", imported.author)
        assertEquals("Кешований голос", imported.narrator)
        val editionId = com.slukhayka.audiobooks.data.EditionId.forBook(
            MergeKey.keyFor("Тигролови", "Іван Багряний"), imported.id, "Кешований голос", "")
        val source = db.audiobookDao().getSourcesForBookSync(imported.id).single()
        assertEquals("sluhayua-$editionId", source.id)
        assertEquals(editionId, source.editionId)
        assertEquals("sluhayua", source.type)
        assertEquals(pageUrl, source.url)
        assertEquals(listOf(com.slukhayka.audiobooks.data.db.ChapterEntity(
            "${imported.id}_ch_1", imported.id, 0, "Кешований розділ", 900L, editionId)),
            db.audiobookDao().getChaptersListForBook(imported.id))
        assertEquals(listOf(com.slukhayka.audiobooks.data.db.SourceTrackEntity(
            "${source.id}_tr_1", source.id, 0, "https://mp3.sluhay.com.ua/100/cached-profile.mp3")),
            db.audiobookDao().getTracksForSourceSync(source.id))
        assertEquals(900L, db.audiobookDao().getAudiobookById(imported.id)!!.totalDurationSeconds)
        assertTrue("fresh profile skips source detail completely", adapter.requests.isEmpty())
        assertTrue("cached profile is never a live observation", received.isEmpty())
        assertNull(local.active(key))
        assertTrue(published.isEmpty())
        assertNull(db.audiobookDao().findByMergeKey(MergeKey.keyFor("Місто", "Валер’ян Підмогильний")))
    }
    @Test
    fun `chapterless own page still contributes received recommendations before materialization guard`() = runBlocking {
        val seed = detail().copy(chapters = emptyList(), totalDurationSeconds = 0L,
            narrator = "Збережений голос", language = "uk")
        val live = seed.copy(title = "Жива сторінка без розділів")
        val adapter = Adapter(live)
        val local = RoomCollectiveFeedBlockStore(db.audiobookDao())
        val received = mutableListOf<Pair<String, SourceBookDetail>>()
        val published = mutableListOf<CollectiveFeedBlock>()
        val refresh = CollectiveFeedRefresh(local, InMemoryCollectiveRefreshLease(),
            fetch = { error("no implicit source fetch") }, clock = { 1_000L }, onActivated = { published += it })
        lateinit var catalog: SourceCatalog
        val observe: suspend (String, SourceBookDetail) -> Unit = { sourceId, resolved ->
            received += sourceId to resolved
            refresh.observeExplicit(key) { catalog.collectiveRelatedBlock(sourceId, resolved) }
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observe)
        catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        val imported = imports.importBookFromSource("sluhayua", seed, writeBackProfile = false)
        val editionId = com.slukhayka.audiobooks.data.EditionId.forBook(
            MergeKey.keyFor("Тигролови", "Іван Багряний"), imported.id, "Збережений голос", "uk")
        val beforeEdition = db.audiobookDao().getEditionForWork(imported.id)!!
        val beforeSource = db.audiobookDao().getSourcesForBookSync(imported.id).single()
        assertEquals(editionId, beforeEdition.id)
        assertEquals("uk", beforeEdition.language)
        assertEquals("Збережений голос", beforeEdition.narrator)
        assertEquals("sluhayua-$editionId", beforeSource.id)
        assertEquals("sluhayua", beforeSource.type)
        assertEquals(pageUrl, beforeSource.url)
        assertTrue(adapter.requests.isEmpty())
        assertTrue("generic seed does not observe its related facts", received.isEmpty())
        assertNull(local.active(key))
        assertTrue(published.isEmpty())
        assertEquals(emptyList<SourceCatalog.PlayableChapter>(), catalog.getPlayableChapters(imported.id))
        assertEquals(listOf(pageUrl), adapter.requests)
        assertEquals(listOf(beforeSource), db.audiobookDao().getSourcesForBookSync(imported.id))
        assertEquals(beforeEdition, db.audiobookDao().getEditionForWork(imported.id))
        assertTrue(db.audiobookDao().getChaptersListForBook(imported.id).isEmpty())
        assertTrue(db.audiobookDao().getTracksForSourceSync(beforeSource.id).isEmpty())
        assertEquals(0L, db.audiobookDao().getAudiobookById(imported.id)!!.totalDurationSeconds)
        assertEquals("chapterless actual response must be observed once", listOf("sluhayua" to live), received)
        val block = local.active(key)!!
        assertEquals("sluhayua|RECOMMENDATIONS", block.blockKey)
        assertEquals("sluhayua", block.sourceId)
        assertEquals(CollectiveBlockKind.RECOMMENDATIONS, block.kind)
        assertEquals("До «Жива сторінка без розділів»", block.name)
        assertEquals(pageUrl, block.provenanceUrl)
        assertEquals(listOf(CollectiveBlockCard(sourceId = "sluhayua",
            sourceUrl = "https://sluhay.com.ua/102:misto", title = "Місто", author = "Валер’ян Підмогильний")), block.cards)
        assertEquals(listOf(block), published)
        assertEquals(listOf(block), CollectiveOverviewBlocks(local).read(listOf("sluhayua")))
        assertEquals("persisted recommendation reading never resolves linked detail", listOf(pageUrl), adapter.requests)
        assertNull(db.audiobookDao().findByMergeKey(MergeKey.keyFor("Місто", "Валер’ян Підмогильний")))
    }

    @Test
    fun `ordinary materialization observer failure preserves last good recommendations and own playable tracks`() = runBlocking {
        val seed = detail().copy(chapters = emptyList(), totalDurationSeconds = 0L,
            narrator = "Збережений голос", language = "uk")
        val live = seed.copy(chapters = listOf(
            SourceChapter("Вступ з власного джерела", "https://mp3.sluhay.com.ua/100/live-intro.mp3", 150L),
            SourceChapter("Фінал з власного джерела", "https://mp3.sluhay.com.ua/100/live-end.mp3", 300L)),
            totalDurationSeconds = 450L)
        val adapter = Adapter(live)
        val local = RoomCollectiveFeedBlockStore(db.audiobookDao())
        val published = mutableListOf<CollectiveFeedBlock>()
        val received = mutableListOf<Pair<String, SourceBookDetail>>()
        val refresh = CollectiveFeedRefresh(local, InMemoryCollectiveRefreshLease(),
            fetch = { error("no implicit source fetch") }, clock = { 1_000L }, onActivated = { published += it })
        val failure = java.io.IOException("optional recommendation observer unavailable")
        val observe: suspend (String, SourceBookDetail) -> Unit = { sourceId, resolved ->
            received += sourceId to resolved
            throw failure
        }
        val imports = LibraryImport(db.audiobookDao(), null, listOf(adapter), onSourceDetailObserved = observe)
        val catalog = SourceCatalog(db.audiobookDao(), listOf(adapter), imports, onSourceDetailObserved = observe)
        val priorDetail = detail().copy(title = "Попередня сторінка", related = listOf(
            RelatedBook("Сад Гетсиманський", "Іван Багряний", "https://sluhay.com.ua/103:sad")))
        refresh.observeExplicit(key) { catalog.collectiveRelatedBlock("sluhayua", priorDetail) }
        val prior = local.active(key)!!
        assertEquals("До «Попередня сторінка»", prior.name)
        assertEquals(pageUrl, prior.provenanceUrl)
        assertEquals(listOf(CollectiveBlockCard(sourceId = "sluhayua",
            sourceUrl = "https://sluhay.com.ua/103:sad", title = "Сад Гетсиманський", author = "Іван Багряний")), prior.cards)
        val imported = imports.importBookFromSource("sluhayua", seed, writeBackProfile = false)
        val editionId = com.slukhayka.audiobooks.data.EditionId.forBook(
            MergeKey.keyFor("Тигролови", "Іван Багряний"), imported.id, "Збережений голос", "uk")
        val beforeSource = db.audiobookDao().getSourcesForBookSync(imported.id).single()
        assertEquals("sluhayua-$editionId", beforeSource.id)
        assertEquals("sluhayua", beforeSource.type)
        assertEquals(pageUrl, beforeSource.url)
        assertTrue(adapter.requests.isEmpty())
        assertTrue("generic seed is nonobserving", received.isEmpty())
        val playable = catalog.getPlayableChapters(imported.id)
        assertEquals(listOf(pageUrl), adapter.requests)
        assertEquals("ordinary failure cannot silently skip the observer", listOf("sluhayua" to live), received)
        assertEquals(listOf(beforeSource), db.audiobookDao().getSourcesForBookSync(imported.id))
        val chapters = listOf(
            com.slukhayka.audiobooks.data.db.ChapterEntity("${imported.id}_ch_1", imported.id, 0,
                "Вступ з власного джерела", 150L, editionId),
            com.slukhayka.audiobooks.data.db.ChapterEntity("${imported.id}_ch_2", imported.id, 1,
                "Фінал з власного джерела", 300L, editionId))
        val tracks = listOf(
            com.slukhayka.audiobooks.data.db.SourceTrackEntity("${beforeSource.id}_tr_1", beforeSource.id, 0,
                "https://mp3.sluhay.com.ua/100/live-intro.mp3"),
            com.slukhayka.audiobooks.data.db.SourceTrackEntity("${beforeSource.id}_tr_2", beforeSource.id, 1,
                "https://mp3.sluhay.com.ua/100/live-end.mp3"))
        assertEquals(chapters, db.audiobookDao().getChaptersListForBook(imported.id))
        assertEquals(tracks, db.audiobookDao().getTracksForSourceSync(beforeSource.id))
        assertEquals(listOf(SourceCatalog.PlayableChapter(chapters[0], tracks[0], "sluhayua", pageUrl),
            SourceCatalog.PlayableChapter(chapters[1], tracks[1], "sluhayua", pageUrl)), playable)
        assertEquals(450L, db.audiobookDao().getAudiobookById(imported.id)!!.totalDurationSeconds)
        val edition = db.audiobookDao().getEditionForWork(imported.id)!!
        assertEquals(editionId, edition.id)
        assertEquals("uk", edition.language)
        assertEquals("Збережений голос", edition.narrator)
        assertEquals(prior, local.active(key))
        assertEquals(listOf(prior), published)
        assertEquals(listOf(prior), CollectiveOverviewBlocks(local).read(listOf("sluhayua")))
        assertEquals(listOf(pageUrl), adapter.requests)
        assertNull(db.audiobookDao().findByMergeKey(MergeKey.keyFor("Місто", "Валер’ян Підмогильний")))
    }
}
