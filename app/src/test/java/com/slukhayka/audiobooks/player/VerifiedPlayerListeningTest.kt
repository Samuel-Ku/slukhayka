package com.slukhayka.audiobooks.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import com.slukhayka.audiobooks.testing.TestDataFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class VerifiedPlayerListeningTest {
    @Test fun `engine callbacks pause seek and release account wall time exactly once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val books = TestDataFactory.dataBooks()
        val book = books.first()
        val chapters = TestDataFactory.dataChapters().filter { it.bookId == book.id }
        val tracks = TestDataFactory.tracksFor(book, "4read")
        val playable = chapters.mapIndexed { index, chapter -> SourceCatalog.PlayableChapter(chapter, tracks[index]) }
        val dao = FakeAudiobookDao(books, chapters)
        val factory = RecordingPlayerFactory()
        var now = 0L
        val durations = mutableListOf<Long>()
        val starts = mutableListOf<Boolean>()
        val manager = AudioPlayerManager(ApplicationProvider.getApplicationContext<Context>(),
            ListeningStateStore(dao, dispatcher), { playable }, injectedPlayerFactory = factory,
            ioDispatcher = dispatcher, widgetSyncEnabled = false, monotonicNow = { now },
            onActualListeningDuration = durations::add, onActualPlaybackStarted = starts::add)
        try {
            manager.loadAndPlayBook(book, chapters, playable)
            runCurrent()
            now = 10000L // prepare/buffer is not listening
            factory.current.simulateReady(chapters.first().durationSeconds * 1000L)
            runCurrent()
            assertEquals(emptyList<Long>(), durations)
            factory.current.notifyIsPlayingChanged(true)
            now = 10650L; manager.seekTo(20000L)
            now = 10800L; manager.pause()
            manager.pause(); factory.current.notifyIsPlayingChanged(false)
            now = 30000L; manager.play(); runCurrent()
            factory.current.notifyIsPlayingChanged(true)
            now = 30400L; manager.release()
            manager.release()
            assertEquals(listOf(650L,150L,400L), durations)
            assertEquals(listOf(false,false), starts)
        } finally {
            manager.release()
            Dispatchers.resetMain()
        }
    }
    @Test fun `cast play command and UI mirrors cannot invent listening before actual receiver status`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val books = TestDataFactory.dataBooks()
        val book = books.first()
        val chapters = TestDataFactory.dataChapters().filter { it.bookId == book.id }
        val tracks = TestDataFactory.tracksFor(book, "4read")
        val playable = chapters.mapIndexed { index, chapter -> SourceCatalog.PlayableChapter(chapter, tracks[index]) }
        val factory = RecordingPlayerFactory()
        var now = 0L
        val durations = mutableListOf<Long>()
        val starts = mutableListOf<Boolean>()
        val completed = mutableListOf<String>()
        val manager = AudioPlayerManager(ApplicationProvider.getApplicationContext<Context>(),
            ListeningStateStore(FakeAudiobookDao(books, chapters), dispatcher), { playable },
            injectedPlayerFactory = factory, ioDispatcher = dispatcher, widgetSyncEnabled = false,
            monotonicNow = { now }, onActualListeningDuration = durations::add,
            onActualPlaybackStarted = starts::add, onBookCompleted = completed::add)
        val receiver = object : CastEngineHook {
            override val isActive = true
            override fun play() = Unit
            override fun pause() = Unit
            override fun seekTo(positionMs: Long) = Unit
            override fun setPlaybackSpeed(speed: Float) = Unit
            override fun setVolume(volume: Float) = Unit
            override fun prepareChapter(chapterIndex: Int, startPositionMs: Long, autoPlay: Boolean) = Unit
        }
        try {
            manager.loadAndPlayBook(book, chapters, playable, autoPlay = false); runCurrent()
            factory.current.simulateReady(chapters.first().durationSeconds * 1000L); runCurrent()
            manager.attachCastHook(receiver)
            manager.play()
            manager.mirrorCastState { it.copy(lastErrorMsg = "waiting for receiver") }
            manager.reportActualCastPlayback(false, true)
            now = 10000L; advanceTimeBy(1001L); runCurrent()
            now = 20000L; advanceTimeBy(1001L); runCurrent()
            assertEquals(emptyList<Long>(),durations)
            assertEquals(emptyList<Boolean>(),starts)
            manager.reportActualCastPlayback(true, false)
            now = 20650L; manager.reportActualCastPlayback(false, true)
            now = 40000L; manager.reportActualCastPlayback(false, false)
            manager.mirrorCastState { it.copy(isPlaying = false) }
            assertEquals(emptyList<String>(), completed)
            manager.reportActualCastCompletion("foreign-book", chapters.lastIndex)
            assertEquals(emptyList<String>(), completed)
            manager.reportActualCastCompletion(book.id, chapters.lastIndex - 1)
            assertEquals(emptyList<String>(), completed)
            manager.reportActualCastCompletion(book.id, chapters.lastIndex)
            manager.reportActualCastCompletion(book.id, chapters.lastIndex)
            assertEquals(listOf(book.id), completed)
            manager.loadAndPlayBook(book, chapters, playable, initialChapterIndex = chapters.lastIndex, autoPlay = false)
            runCurrent()
            manager.reportActualCastCompletion(book.id, chapters.lastIndex) // Earlier receiver finish after same-book reload.
            assertEquals(listOf(book.id), completed)
            manager.reportActualCastPlayback(true, false) // Only a new actual receiver start arms this load.
            manager.reportActualCastCompletion(book.id, chapters.lastIndex)
            manager.reportActualCastCompletion(book.id, chapters.lastIndex)
            assertEquals(listOf(book.id, book.id), completed)
            manager.release()
            assertEquals(listOf(650L),durations)
            assertEquals(listOf(false, false),starts)
        } finally { manager.release(); Dispatchers.resetMain() }
    }

    @Test fun `real local playback proves offline and completion while cancelled prepare and speed invent nothing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val book = TestDataFactory.dataBooks().first().copy(totalChapters = 1)
        val chapters = TestDataFactory.dataChapters().filter { it.bookId == book.id }.take(1)
        val file = java.io.File.createTempFile("699-offline", ".mp3", context.cacheDir)
            .apply { writeBytes(ByteArray(1024)) }
        val track = TestDataFactory.tracksFor(book, "4read").first()
            .copy(localFilePath = file.absolutePath, isDownloaded = true)
        val playable = listOf(SourceCatalog.PlayableChapter(chapters.single(), track))
        val factory = RecordingPlayerFactory()
        var now = 0L
        val durations = mutableListOf<Long>()
        val starts = mutableListOf<Boolean>()
        val completed = mutableListOf<String>()
        val manager = AudioPlayerManager(context,
            ListeningStateStore(FakeAudiobookDao(listOf(book), chapters), dispatcher), { playable },
            injectedPlayerFactory = factory, ioDispatcher = dispatcher, widgetSyncEnabled = false,
            monotonicNow = { now }, onActualListeningDuration = durations::add,
            onActualPlaybackStarted = starts::add, onBookCompleted = completed::add)
        try {
            manager.loadAndPlayBook(book, chapters, playable); runCurrent()
            manager.pause() // A queued READY/playing callback cannot revive the cancelled prepare.
            now = 10_000L
            factory.current.simulateReady(chapters.single().durationSeconds * 1000L)
            factory.current.notifyIsPlayingChanged(true); runCurrent()
            assertEquals(emptyList<Boolean>(), starts)
            assertEquals(emptyList<Long>(), durations)
            manager.play(); runCurrent()
            factory.current.notifyIsPlayingChanged(true)
            assertEquals(android.net.Uri.fromFile(file).toString(), factory.current.lastMediaItemUri)
            assertEquals(listOf(true), starts)
            now = 10_100L; manager.setPlaybackSpeed(2f)
            now = 10_300L; factory.current.simulateEnded()
            factory.current.simulateEnded(); manager.release()
            assertEquals(listOf(100L, 200L), durations)
            assertEquals(listOf(book.id), completed)
        } finally { manager.release(); file.delete(); Dispatchers.resetMain() }
    }

    @Test fun `accessible SAF playback is offline only for the real selected local Source`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = android.net.Uri.parse("content://699.local.documents/document/audiobook.mp3")
        org.robolectric.Shadows.shadowOf(context.contentResolver)
            .registerInputStream(uri, byteArrayOf(1, 2, 3).inputStream())
        assertEquals(1, context.contentResolver.openInputStream(uri)!!.use { it.read() })
        val database = androidx.room.Room.inMemoryDatabaseBuilder(context,
            com.slukhayka.audiobooks.data.db.AudiobookDatabase::class.java)
            .allowMainThreadQueries().build()
        val book = TestDataFactory.dataBooks().first().copy(totalChapters = 1)
        val chapters = TestDataFactory.dataChapters().filter { it.bookId == book.id }.take(1)
        val dao = database.audiobookDao()
        dao.insertAudiobooks(listOf(book)); dao.insertChapters(chapters)
        val owner = com.slukhayka.audiobooks.data.db.SourceEntity("saf-source", book.id,
            type = "local", url = uri.toString())
        dao.insertSources(listOf(owner))
        dao.insertTracks(listOf(com.slukhayka.audiobooks.data.db.SourceTrackEntity("saf-track",
            owner.id, 0, url = uri.toString())))
        val catalog = SourceCatalog(dao, emptyList(),
            com.slukhayka.audiobooks.data.imports.LibraryImport(dao, context, emptyList()))
        val playable = catalog.getPlayableChapters(book.id)
        assertEquals("local", playable.single().sourceId)
        val factory = RecordingPlayerFactory()
        val starts = mutableListOf<Boolean>()
        val manager = AudioPlayerManager(context, ListeningStateStore(dao, dispatcher),
            catalog::getPlayableChapters, injectedPlayerFactory = factory, ioDispatcher = dispatcher,
            widgetSyncEnabled = false, onActualPlaybackStarted = starts::add)
        try {
            manager.loadAndPlayBook(book, chapters, playable); runCurrent()
            assertEquals(emptyList<Boolean>(), starts)
            factory.current.simulateReady(chapters.single().durationSeconds * 1000L)
            factory.current.notifyIsPlayingChanged(true); runCurrent()
            assertEquals(uri.toString(), factory.current.lastMediaItemUri)
            assertEquals(listOf(true), starts)
            manager.pause()
            dao.insertSources(listOf(owner.copy(type = "sluhay")))
            val remoteBinding = catalog.getPlayableChapters(book.id)
            assertEquals("sluhay", remoteBinding.single().sourceId)
            manager.loadAndPlayBook(book, chapters, remoteBinding); runCurrent()
            factory.current.simulateReady(chapters.single().durationSeconds * 1000L)
            factory.current.notifyIsPlayingChanged(true); runCurrent()
            assertEquals(listOf(true, false), starts)
        } finally { manager.release(); database.close(); Dispatchers.resetMain() }
    }

}
