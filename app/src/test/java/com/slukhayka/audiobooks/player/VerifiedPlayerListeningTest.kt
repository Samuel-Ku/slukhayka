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

    @Test fun `Cast finish uses confirmed receiver duration when chapter metadata is unknown`() = runTest {
        assertCastCompletionPosition(metadataSeconds = 0L, receiverDurationMs = 60_000L, receiverPositionMs = 55_000L, expectedMs = 60_000L)
    }

    @Test fun `Cast finish uses confirmed receiver duration when chapter metadata is inaccurate`() = runTest {
        assertCastCompletionPosition(metadataSeconds = 17L, receiverDurationMs = 60_000L, receiverPositionMs = 55_000L, expectedMs = 60_000L)
    }

    @Test fun `Cast finish with unknown receiver duration keeps the last confirmed position`() = runTest {
        assertCastCompletionPosition(metadataSeconds = 17L, receiverDurationMs = 0L, receiverPositionMs = 0L, expectedMs = 55_000L)
    }

    @Test fun `Cast timer cannot complete from stale chapter duration before receiver FINISHED`() = runTest {
        assertCastCompletionPosition(metadataSeconds = 17L, receiverDurationMs = 60_000L, receiverPositionMs = 55_000L,
            expectedMs = 60_000L, verifyPlayingTick = true)
    }


    @Test fun `raw confirmed Cast position survives a zero UI mirror before unknown FINISHED`() = runTest {
        assertCastCompletionPosition(metadataSeconds = 17L, receiverDurationMs = 0L, receiverPositionMs = 0L,
            expectedMs = 55_000L, resetUiAtFinish = true)
    }

    @Test fun `unknown Cast finish cannot transfer confirmed position from the previous chapter`() = runTest {
        assertCastCompletionPosition(metadataSeconds = 17L, receiverDurationMs = 0L, receiverPositionMs = 0L,
            expectedMs = 0L, chapterCount = 2)
    }

    @Test fun `positive final receiver position is used even when UI still mirrors the previous chapter`() = runTest {
        assertCastCompletionPosition(metadataSeconds = 17L, receiverDurationMs = 0L, receiverPositionMs = 55_000L,
            expectedMs = 55_000L, chapterCount = 2)
    }

    @Test fun `same book reload discards the previous receiver position before a new unknown finish`() = runTest {
        assertCastCompletionPosition(metadataSeconds = 17L, receiverDurationMs = 0L, receiverPositionMs = 0L,
            expectedMs = 0L, reloadBeforeFinish = true)
    }

    @Test fun `late idle mirror after Cast FINISHED cannot erase terminal progress on pause`() = runTest {
        assertCastCompletionPosition(metadataSeconds = 17L, receiverDurationMs = 60_000L, receiverPositionMs = 55_000L,
            expectedMs = 60_000L, chapterCount = 2, lateUiResetAfterFinish = true)
    }

    @Test fun `Cast terminal protection allows explicit seek and replay after completion`() = runTest {
        assertCastCompletionPosition(metadataSeconds = 17L, receiverDurationMs = 60_000L, receiverPositionMs = 55_000L,
            expectedMs = 60_000L, verifyTransportAfterFinish = true)
    }

    @Test fun `Cast terminal protection cannot survive a new load of the completed book`() = runTest {
        assertCastCompletionPosition(metadataSeconds = 17L, receiverDurationMs = 60_000L, receiverPositionMs = 55_000L,
            expectedMs = 60_000L, reloadAfterFinish = true)
    }

    @Test fun `new owned receiver movement can replace the protected Cast terminal position`() = runTest {
        assertCastCompletionPosition(metadataSeconds = 17L, receiverDurationMs = 60_000L, receiverPositionMs = 55_000L,
            expectedMs = 60_000L, verifyReceiverMovementAfterFinish = true)
    }

    @Test fun `Cast ending preserves confirmed final chapter through paused local handback`() = runTest {
        assertCastCompletionPosition(metadataSeconds = 17L, receiverDurationMs = 60_000L, receiverPositionMs = 55_000L,
            expectedMs = 60_000L, chapterCount = 2, handbackAfterFinish = true)
    }

    @Test fun `manual seek after Cast finish determines paused local handback`() = runTest {
        assertCastCompletionPosition(metadataSeconds = 17L, receiverDurationMs = 60_000L, receiverPositionMs = 55_000L,
            expectedMs = 60_000L, chapterCount = 2, handbackAfterFinish = true, seekBeforeHandback = true)
    }

    @Test fun `receiver replacement clears the previous terminal checkpoint before local handback`() = runTest {
        assertCastCompletionPosition(metadataSeconds = 17L, receiverDurationMs = 60_000L, receiverPositionMs = 55_000L,
            expectedMs = 60_000L, chapterCount = 2, handbackAfterFinish = true, replaceBeforeHandback = true)
    }

    @Test fun `resuming the same finished Cast session preserves its checkpoint through idle and local handback`() = runTest {
        assertCastCompletionPosition(metadataSeconds = 17L, receiverDurationMs = 60_000L, receiverPositionMs = 55_000L,
            expectedMs = 60_000L, chapterCount = 2, resumeAfterFinish = true, handbackAfterFinish = true)
    }

    @Test fun `fresh actual Cast replay can restore the finish without duplicating completion history`() = runTest {
        assertCastCompletionPosition(metadataSeconds = 17L, receiverDurationMs = 60_000L, receiverPositionMs = 55_000L,
            expectedMs = 60_000L, verifyReplayFinish = true)
    }

    private suspend fun TestScope.assertCastCompletionPosition(metadataSeconds: Long, receiverDurationMs: Long,
        receiverPositionMs: Long, expectedMs: Long, verifyPlayingTick: Boolean = false,
        resetUiAtFinish: Boolean = false, chapterCount: Int = 1, reloadBeforeFinish: Boolean = false,
        lateUiResetAfterFinish: Boolean = false, verifyTransportAfterFinish: Boolean = false,
        reloadAfterFinish: Boolean = false, verifyReceiverMovementAfterFinish: Boolean = false,
        handbackAfterFinish: Boolean = false, seekBeforeHandback: Boolean = false,
        replaceBeforeHandback: Boolean = false, resumeAfterFinish: Boolean = false,
        verifyReplayFinish: Boolean = false) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val direct = java.util.concurrent.Executor { it.run() }
        val database = androidx.room.Room.inMemoryDatabaseBuilder(context,
            com.slukhayka.audiobooks.data.db.AudiobookDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(direct).setTransactionExecutor(direct).build()
        val book = TestDataFactory.dataBooks().first().copy(totalChapters = chapterCount)
        val chapters = TestDataFactory.dataChapters().filter { it.bookId == book.id }.take(chapterCount)
            .map { it.copy(durationSeconds = metadataSeconds) }
        val tracks = TestDataFactory.tracksFor(book, "4read")
        val playable = chapters.mapIndexed { index, chapter -> SourceCatalog.PlayableChapter(chapter, tracks[index]) }
        val dao = database.audiobookDao()
        dao.insertAudiobooks(listOf(book)); dao.insertChapters(chapters)
        val listening = ListeningStateStore(dao, dispatcher)
        val completed = mutableListOf<String>()
        val factory = RecordingPlayerFactory()
        val manager = AudioPlayerManager(context, listening, { playable },
            injectedPlayerFactory = factory, ioDispatcher = dispatcher,
            widgetSyncEnabled = false, onBookCompleted = completed::add)
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
            manager.attachCastHook(receiver)
            manager.mirrorCastState { it.copy(currentPositionMs = 55_000L, durationMs = 60_000L) }
            manager.reportActualCastPlayback(true, false, book.id, 0, 55_000L)
            if (reloadBeforeFinish) {
                manager.loadAndPlayBook(book, chapters, playable, autoPlay = false); runCurrent()
                manager.reportActualCastPlayback(true, false) // New receiver start has no position evidence yet.
            }
            if (verifyPlayingTick) {
                manager.mirrorCastState { it.copy(isPlaying = true, isBuffering = false, durationMs = metadataSeconds * 1000L) }
                advanceTimeBy(1001L); runCurrent()
                assertEquals(emptyList<String>(), completed)
                assertEquals(55_000L, manager.playerState.value.currentPositionMs)
            }
            if (resetUiAtFinish) manager.mirrorCastState { it.copy(isPlaying = false, currentPositionMs = 0L) }
            manager.reportActualCastPlayback(false, false)
            manager.reportActualCastCompletion(book.id, chapters.lastIndex, receiverDurationMs, receiverPositionMs)
            manager.reportActualCastCompletion(book.id, chapters.lastIndex, receiverDurationMs, receiverPositionMs)
            runCurrent()
            if (lateUiResetAfterFinish) {
                manager.mirrorCastState { it.copy(isPlaying = false, isBuffering = false, currentChapterIndex = 0,
                    currentPositionMs = 0L, durationMs = 0L) }
                manager.reportActualCastCompletion(book.id, chapters.lastIndex, receiverDurationMs, receiverPositionMs)
                manager.pause(); runCurrent()
            }
            if (resumeAfterFinish) {
                manager.resetActualCastReceiverEvidence(preserveTerminalPosition = true) // Suspended.
                manager.resumeActualCastReceiverObservation() // The production active-session resume policy.
                manager.reportActualCastPlayback(false, false) // Receiver remains IDLE, no fresh PLAYING.
                manager.mirrorCastState { it.copy(isPlaying = false, isBuffering = false, currentChapterIndex = 0,
                    currentPositionMs = 0L, durationMs = 0L) }
                manager.reportActualCastCompletion(book.id, chapters.lastIndex, receiverDurationMs, receiverPositionMs)
                manager.pause(); runCurrent()
                val resumedProgress = listening.getProgressSync(book.id)!!
                assertEquals(chapters.lastIndex, resumedProgress.currentChapterIndex)
                assertEquals(60L, resumedProgress.currentPositionSeconds)
                assertEquals(60_000L, manager.playerState.value.durationMs)
            }
            assertEquals(expectedMs, manager.playerState.value.currentPositionMs)
            assertEquals(chapters.lastIndex, manager.playerState.value.currentChapterIndex)
            if (lateUiResetAfterFinish) assertEquals(60_000L, manager.playerState.value.durationMs)
            val progress = listening.getProgressSync(book.id)!!
            assertEquals(chapters.lastIndex, progress.currentChapterIndex)
            assertEquals(expectedMs / 1000L, progress.currentPositionSeconds)
            val events = dao.getPlaybackEventsForBookSource(book.id, "")
                .filter { it.kind == com.slukhayka.audiobooks.data.db.PlaybackEventKind.COMPLETED }
            assertEquals(1, events.size)
            assertEquals(chapters.lastIndex, events.single().chapterIndex)
            assertEquals(expectedMs / 1000L, events.single().positionSeconds)
            assertEquals(listOf(book.id), completed)
            if (verifyReplayFinish) {
                manager.seekTo(20_000L)
                manager.mirrorCastState { it.copy(isPlaying = false, currentPositionMs = 20_000L) }
                manager.pause(); runCurrent()
                assertEquals(20L, listening.getProgressSync(book.id)!!.currentPositionSeconds)
                manager.reportActualCastCompletion(book.id, chapters.lastIndex, 60_000L, 60_000L)
                runCurrent() // Duplicate FINISHED without fresh actual playback cannot undo a deliberate seek.
                assertEquals(20_000L, manager.playerState.value.currentPositionMs)
                assertEquals(20L, listening.getProgressSync(book.id)!!.currentPositionSeconds)
                manager.play()
                manager.reportActualCastPlayback(true, false, book.id, chapters.lastIndex, 20_000L)
                manager.reportActualCastPlayback(false, false)
                manager.reportActualCastCompletion(book.id, chapters.lastIndex, 60_000L, 60_000L)
                manager.reportActualCastCompletion(book.id, chapters.lastIndex, 60_000L, 60_000L)
                runCurrent()
                assertEquals(60_000L, manager.playerState.value.currentPositionMs)
                assertEquals(60L, listening.getProgressSync(book.id)!!.currentPositionSeconds)
                val replayEvents = dao.getPlaybackEventsForBookSource(book.id, "")
                    .filter { it.kind == com.slukhayka.audiobooks.data.db.PlaybackEventKind.COMPLETED }
                assertEquals(1, replayEvents.size)
                assertEquals(60L, replayEvents.single().positionSeconds)
                assertEquals(listOf(book.id), completed)
            }
            if (handbackAfterFinish) {
                if (seekBeforeHandback) manager.seekTo(20_000L)
                if (replaceBeforeHandback) manager.resetActualCastReceiverEvidence()
                // SessionEnding/Suspended precedes SessionEnded: raw evidence/clock stop early.
                manager.resetActualCastReceiverEvidence(preserveTerminalPosition = true)
                val reportedIndex = if (seekBeforeHandback) chapters.lastIndex else 0
                val reportedPosition = if (seekBeforeHandback) 20_000L else 0L
                val resume = manager.captureCastResumePosition(reportedIndex, reportedPosition)
                manager.resetActualCastReceiverEvidence()
                manager.attachCastHook(null)
                manager.mirrorCastState { it.copy(isPlaying = false, isBuffering = false) }
                manager.prepareChapter(resume.first, resume.second, autoPlay = false); runCurrent()
                factory.current.simulateReady(60_000L); runCurrent()
                manager.pause(); runCurrent()
                val expectedChapter = if (replaceBeforeHandback) 0 else chapters.lastIndex
                val expectedPosition = when {
                    seekBeforeHandback -> 20_000L
                    replaceBeforeHandback -> 0L
                    else -> 60_000L
                }
                assertEquals(expectedChapter, manager.playerState.value.currentChapterIndex)
                assertEquals(expectedPosition, manager.playerState.value.currentPositionMs)
                assertEquals(false, manager.playerState.value.isPlaying)
                val persisted = listening.getProgressSync(book.id)!!
                assertEquals(expectedChapter, persisted.currentChapterIndex)
                assertEquals(expectedPosition / 1000L, persisted.currentPositionSeconds)
                val terminalEvents = dao.getPlaybackEventsForBookSource(book.id, "")
                    .filter { it.kind == com.slukhayka.audiobooks.data.db.PlaybackEventKind.COMPLETED }
                assertEquals(1, terminalEvents.size)
                assertEquals(chapters.lastIndex, terminalEvents.single().chapterIndex)
                assertEquals(60L, terminalEvents.single().positionSeconds)
                assertEquals(listOf(book.id), completed)
            }
            if (verifyTransportAfterFinish) {
                manager.seekTo(20_000L)
                manager.mirrorCastState { it.copy(isPlaying = false, currentPositionMs = 20_000L) }
                manager.pause(); runCurrent()
                assertEquals(20_000L, manager.playerState.value.currentPositionMs)
                assertEquals(20L, listening.getProgressSync(book.id)!!.currentPositionSeconds)
                manager.play()
                manager.reportActualCastPlayback(true, false, book.id, chapters.lastIndex, 1000L)
                manager.mirrorCastState { it.copy(isPlaying = true, currentPositionMs = 1000L) }
                manager.pause(); runCurrent()
                assertEquals(1000L, manager.playerState.value.currentPositionMs)
                assertEquals(1L, listening.getProgressSync(book.id)!!.currentPositionSeconds)
                assertEquals(listOf(book.id), completed)
            }
            if (verifyReceiverMovementAfterFinish) {
                manager.reportActualCastPlayback(false, false, book.id, chapters.lastIndex, 20_000L)
                manager.mirrorCastState { it.copy(isPlaying = false, currentPositionMs = 20_000L) }
                manager.pause(); runCurrent()
                assertEquals(20_000L, manager.playerState.value.currentPositionMs)
                assertEquals(20L, listening.getProgressSync(book.id)!!.currentPositionSeconds)
                assertEquals(listOf(book.id), completed)
            }
            if (reloadAfterFinish) {
                manager.loadAndPlayBook(book, chapters, playable, autoPlay = false); runCurrent()
                manager.reportActualCastPlayback(true, false)
                manager.mirrorCastState { it.copy(isPlaying = false, currentPositionMs = 0L) }
                manager.reportActualCastCompletion(book.id, chapters.lastIndex, 0L, 0L)
                manager.pause(); runCurrent()
                assertEquals(0L, manager.playerState.value.currentPositionMs)
                assertEquals(0L, listening.getProgressSync(book.id)!!.currentPositionSeconds)
                assertEquals(listOf(book.id, book.id), completed)
                assertEquals(listOf(0L, 60L), dao.getPlaybackEventsForBookSource(book.id, "")
                    .filter { it.kind == com.slukhayka.audiobooks.data.db.PlaybackEventKind.COMPLETED }
                    .map { it.positionSeconds })
            }
        } finally { manager.release(); runCurrent(); database.close(); Dispatchers.resetMain() }
    }

}
