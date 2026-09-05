package com.slukhayka.audiobooks.player

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.PlaybackEventKind
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import com.slukhayka.audiobooks.testing.TestDataFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM unit tests for [AudioPlayerManager] driven by [FakePlayerEngine]
 * (GitHub issues #4 and #6).
 *
 * No ExoPlayer, no audio device, no network, no wall clock: the player is
 * injected through the [PlayerFactory] seam, the data comes from
 * [TestDataFactory], and the 45s prepare timeout is advanced virtually via the
 * injected [StandardTestDispatcher].
 *
 * Robolectric is still required because `AudioPlayerManager` touches `Uri`,
 * `Log` and `Build.VERSION` -- but nothing here reaches a real media pipeline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioPlayerManagerTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var dao: FakeAudiobookDao
    private lateinit var listeningState: ListeningStateStore

    private val book: AudiobookEntity = TestDataFactory.dataBooks()[STREAMING_BOOK_INDEX]
    private val chapters: List<ChapterEntity> = TestDataFactory.chaptersFor(book)

    // ADR-0007: the player resolves chapter → track 1:1 by index — the
    // physical stream URLs live on the Source tracks, not the chapter rows.
    private val playable: List<SourceCatalog.PlayableChapter> =
        chapters.mapIndexed { index, chapter ->
            SourceCatalog.PlayableChapter(
                chapter = chapter,
                track = TestDataFactory.tracksFor(book, "4read")[index]
            )
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        dao = FakeAudiobookDao(
            books = TestDataFactory.dataBooks(),
            chapters = TestDataFactory.dataChapters()
        )
        // ADR-0002: the player runs on the store + a chapter fetcher — no
        // repository graph. The fake DAO is the in-memory persistence.
        listeningState = ListeningStateStore(dao, dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------------
    // Ticket #4 representative cases
    // ---------------------------------------------------------------------

    @Test
    fun `prepareChapter prepares the engine and notifies ready`() = playerTest { manager, factory ->
        // Arrange
        val resolvedDurationMs = 90_000L

        // Act
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = false)
        val engine = factory.current

        // Assert -- buffering while the engine has not reported READY yet
        assertEquals(1, factory.engines.size)
        assertEquals(1, engine.prepareCount)
        assertEquals(playable[0].track!!.url, engine.lastMediaItemUri)
        assertTrue(manager.playerState.value.isBuffering)

        // Act -- the stream becomes playable
        engine.simulateReady(resolvedDurationMs)

        // Assert
        val state = manager.playerState.value
        assertFalse(state.isBuffering)
        assertEquals("4read Direct Stream", state.audioEngineMode)
        assertEquals(resolvedDurationMs, state.durationMs)
        assertEquals(playable[0].track!!.url, state.currentStreamUrl)
        assertFalse("autoPlay was false", state.isPlaying)
        assertEquals(0, engine.playCount)
    }

    @Test
    fun `playbackStarted waits for the factual engine callback and identifies the media`() =
        playerTest { manager, factory ->
            val started = async(start = CoroutineStart.UNDISPATCHED) {
                manager.playbackStarted.first()
            }
            manager.loadAndPlayBook(
                book,
                chapters,
                playable = playable,
                initialChapterIndex = 0,
                autoPlay = true
            )
            val engine = factory.current

            engine.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)
            assertFalse("a play command is not yet a factual start", started.isCompleted)

            engine.notifyIsPlayingChanged(true)
            val event = started.await()
            assertEquals(book.id, event.bookId)
            assertEquals(0, event.chapterIndex)
            assertEquals(playable[0].track!!.url, event.mediaUrl)
        }

    @Test
    fun `playback timeout flips audioEngineMode to the error state`() = playerTest { manager, factory ->
        // Arrange -- a stream that buffers forever and never reaches READY
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = true)
        val engine = factory.current
        engine.simulateTimeout()
        assertTrue(manager.playerState.value.isBuffering)

        // Act -- burn through the 45s prepare timeout in virtual time
        advanceTimeBy(FakePlayerEngine.PREPARE_TIMEOUT_MS + 1L)

        // Assert
        val state = manager.playerState.value
        assertEquals("Playback error", state.audioEngineMode)
        assertFalse(state.isBuffering)
        assertFalse(state.isPlaying)
        assertEquals("", state.currentStreamUrl)
        assertTrue("user-facing error must be set", state.lastErrorMsg.isNotBlank())
        // Background-playback refactor: the single shared engine is kept alive
        // after a failure so the MediaSession keeps wrapping a live player and
        // a later play() re-prepares it.
        assertFalse("the engine must survive the failure", engine.isReleased)

        // wayfinder #52: synthetic codes are observable too.
        assertEquals(1, manager.playbackMetrics.failures())
        assertTrue(manager.playbackMetrics.failureByCode().containsKey("PREPARE_TIMEOUT"))
        assertTrue(manager.playbackEventLog.export().contains("FAIL PREPARE_TIMEOUT"))
        awaitLedgerRows(1)
        assertEquals(1, dao.savedFailures.size)
        assertEquals("PREPARE_TIMEOUT", dao.savedFailures.first().errorCodeName)
        // wayfinder #61 Q1: the synthetic code maps to its own category.
        assertEquals("START_FAILED", dao.savedFailures.first().category)
    }

    @Test
    fun `automatic recovery replaces the error with bounded loading state`() = playerTest { manager, factory ->
        manager.loadAndPlayBook(
            book,
            chapters,
            playable = playable,
            initialChapterIndex = 0,
            autoPlay = true
        )
        factory.current.simulateTimeout()
        advanceTimeBy(FakePlayerEngine.PREPARE_TIMEOUT_MS + 1L)
        assertTrue(manager.playerState.value.lastErrorMsg.isNotBlank())

        manager.beginAutomaticRecovery()

        val state = manager.playerState.value
        assertTrue(state.isBuffering)
        assertEquals("", state.lastErrorMsg)
        assertEquals(PlaybackErrorKind.NONE, state.errorKind)
    }

    @Test
    fun `new playback attempt clears the previous failure without pretending to buffer`() = playerTest { manager, factory ->
        manager.loadAndPlayBook(
            book,
            chapters,
            playable = playable,
            initialChapterIndex = 0,
            autoPlay = true
        )
        factory.current.simulateTimeout()
        advanceTimeBy(FakePlayerEngine.PREPARE_TIMEOUT_MS + 1L)
        assertTrue(manager.playerState.value.lastErrorMsg.isNotBlank())

        manager.clearPlaybackFailureForNewAttempt()

        val state = manager.playerState.value
        assertFalse(state.isBuffering)
        assertEquals("", state.lastErrorMsg)
        assertEquals(PlaybackErrorKind.NONE, state.errorKind)
    }

    @Test
    fun `chapter completion advances to the next chapter`() = playerTest { manager, factory ->
        // Arrange
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = true)
        val firstEngine = factory.current
        firstEngine.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)
        assertEquals(0, manager.playerState.value.currentChapterIndex)

        // Act
        firstEngine.simulateEnded()

        // Assert -- the same long-lived engine is re-prepared for chapter 2
        // (background-playback refactor: one player per manager lifetime).
        val state = manager.playerState.value
        assertEquals(1, state.currentChapterIndex)
        assertEquals(playable[1].track!!.url, state.currentStreamUrl)
        assertEquals(1, factory.engines.size)
        assertFalse("the finished engine must be reused, not released", firstEngine.isReleased)
        assertEquals(2, firstEngine.prepareCount)
        assertEquals(playable[1].track!!.url, firstEngine.lastMediaItemUri)
    }

    // ---------------------------------------------------------------------
    // Resume-seek regression (2026-08-08, reproduced live on device): the
    // READY listener used to call mp.seekTo(currentPositionMs) on EVERY READY
    // transition. When resuming at a saved position > 0, currentPositionMs
    // never updates while the player is stuck in BUFFERING, so every READY
    // re-issued the same seek -> READY -> seek -> BUFFERING -> READY loop that
    // never settles (device logcat: buffered position kept resetting to the
    // resume position 452000 forever, position frozen). The resume seek must
    // be issued exactly once per prepare.
    // ---------------------------------------------------------------------

    @Test
    fun `resume position seeks the engine exactly once - no READY seek loop`() =
        playerTest { manager, factory ->
            // Arrange -- resume a book at chapter 2, 452s in.
            manager.loadAndPlayBook(
                book, chapters, playable = playable,
                initialChapterIndex = 1,
                initialPositionSeconds = 452L,
                autoPlay = true
            )
            val engine = factory.current

            // Act -- the chapter becomes ready; the listener must seek once to
            // the saved position (first READY after prepare).
            engine.simulateReady(chapters[1].durationSeconds * MILLIS_PER_SECOND)
            assertEquals(listOf(452_000L), engine.seekTargetsMs)
            assertEquals(452_000L, manager.playerState.value.currentPositionMs)

            // Act -- the seek settles and the engine reports READY again. The
            // listener must NOT re-issue the same seek (that is the loop).
            engine.simulateReady(chapters[1].durationSeconds * MILLIS_PER_SECOND)
            assertEquals(
                "the resume seek must be consumed after the first READY",
                listOf(452_000L),
                engine.seekTargetsMs
            )

            // Act -- a third READY (e.g. post-seek buffer drain) still no seek.
            engine.simulateReady(chapters[1].durationSeconds * MILLIS_PER_SECOND)
            assertEquals(listOf(452_000L), engine.seekTargetsMs)
        }

    @Test
    fun `seekTo while buffering arms the one-shot seek for the next READY`() =
        playerTest { manager, factory ->
            // Arrange -- prepare starts, engine stays BUFFERING (slow stream).
            manager.loadAndPlayBook(
                book,
                chapters,
                playable = playable,
                initialChapterIndex = 0,
                autoPlay = true
            )
            val engine = factory.current
            assertTrue(manager.playerState.value.isBuffering)

            // Act -- user seeks while the engine cannot honour it yet.
            manager.seekTo(60_000L)
            // No engine call yet (engine is not READY).
            assertTrue(engine.seekTargetsMs.isEmpty())

            // Act -- the stream finally becomes ready: the one-shot fires once.
            engine.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)
            assertEquals(listOf(60_000L), engine.seekTargetsMs)

            // Act -- a second READY (post-seek buffer drain) must not re-seek.
            engine.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)
            assertEquals(listOf(60_000L), engine.seekTargetsMs)
        }

    @Test
    fun `pause while buffering cancels the pending auto play`() = playerTest { manager, factory ->
        manager.loadAndPlayBook(
            book,
            chapters,
            playable = playable,
            initialChapterIndex = 0,
            autoPlay = true
        )
        val engine = factory.current
        assertTrue(manager.playerState.value.isBuffering)

        manager.pause()
        engine.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)

        assertFalse(manager.playerState.value.isPlaying)
        assertEquals(0, engine.playCount)
    }

    @Test
    fun `pause then play resumes from the same position`() = playerTest { manager, factory ->
        // Arrange
        manager.loadAndPlayBook(
            book,
            chapters,
            playable = playable,
            initialChapterIndex = 0,
            autoPlay = true
        )
        val engine = factory.current
        engine.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)
        assertTrue(manager.playerState.value.isPlaying)
        assertEquals(1, engine.playCount)

        val resumePositionMs = 60_000L
        manager.seekTo(resumePositionMs)

        // Act
        manager.pause()
        val pausedState = manager.playerState.value

        manager.play()
        val resumedState = manager.playerState.value

        // Assert
        assertFalse(pausedState.isPlaying)
        assertEquals(1, engine.pauseCount)
        assertTrue(resumedState.isPlaying)
        assertEquals(2, engine.playCount)
        assertEquals(resumePositionMs, resumedState.currentPositionMs)
        assertEquals(resumePositionMs, engine.currentPosition)
        assertTrue(engine.seekTargetsMs.contains(resumePositionMs))
    }

    // ---------------------------------------------------------------------
    // Whole-book offline playback: every chapter of a downloaded book must be
    // prepared from its LOCAL file (file://), never from the network stream.
    // ---------------------------------------------------------------------

    @Test
    fun `fully downloaded multi-chapter book prepares EVERY chapter from its local file`() =
        playerTest { manager, factory ->
            // Arrange — a fully downloaded 3-chapter book: every TRACK points
            // at a real file on disk (length > 100 so the local path wins).
            // ADR-0007: download state lives on the tracks, never on chapters.
            val localTracks = playable.map { p ->
                val file = java.io.File(context.cacheDir, "downloaded-${p.chapter.id}.mp3")
                file.writeBytes(ByteArray(1024))
                p.track!!.copy(localFilePath = file.absolutePath, isDownloaded = true)
            }
            val offlineBook = book.copy(isDownloaded = true).also { it.downloadProgress = 1f }
            val offlinePlayable = chapters.mapIndexed { index, chapter ->
                SourceCatalog.PlayableChapter(chapter, localTracks[index])
            }

            // Act — play chapter 0, then auto-advance through ALL chapters.
            manager.loadAndPlayBook(offlineBook, chapters, playable = offlinePlayable, initialChapterIndex = 0, autoPlay = false)
            val engine = factory.current

            for (i in localTracks.indices) {
                if (i > 0) manager.nextChapter()
                val expectedUri = android.net.Uri.fromFile(java.io.File(localTracks[i].localFilePath!!)).toString()
                assertEquals("chapter ${i + 1} must resolve to its local file", expectedUri, engine.lastMediaItemUri)
                assertNotEquals("chapter ${i + 1} must NOT use the network stream", localTracks[i].url, engine.lastMediaItemUri)
                engine.simulateReady(chapters[i].durationSeconds * MILLIS_PER_SECOND)
                assertEquals("Offline Local File", manager.playerState.value.audioEngineMode)
            }
            assertEquals("one engine reused across all chapters", 1, factory.engines.size)
        }

    @Test
    fun `partially downloaded book mixes local files and stream fallback per chapter`() =
        playerTest { manager, factory ->
            // Arrange — chapter 0's track downloaded; chapter 1's track file was
            // deleted from disk (localFilePath stale); chapter 2's downloaded.
            val file0 = java.io.File(context.cacheDir, "partial-0.mp3").apply { writeBytes(ByteArray(1024)) }
            val file2 = java.io.File(context.cacheDir, "partial-2.mp3").apply { writeBytes(ByteArray(1024)) }
            val mixedTracks = playable.mapIndexed { index, p ->
                when (index) {
                    0 -> p.track!!.copy(localFilePath = file0.absolutePath, isDownloaded = true)
                    1 -> p.track!!.copy(localFilePath = "/nonexistent/missing.mp3", isDownloaded = true) // file gone
                    else -> p.track!!.copy(localFilePath = file2.absolutePath, isDownloaded = true)
                }
            }
            val offlineBook = book.copy(isDownloaded = true).also { it.downloadProgress = 1f }
            val mixedPlayable = chapters.mapIndexed { index, chapter ->
                SourceCatalog.PlayableChapter(chapter, mixedTracks[index])
            }

            manager.loadAndPlayBook(offlineBook, chapters, playable = mixedPlayable, initialChapterIndex = 0, autoPlay = false)
            val engine = factory.current

            // Chapter 0 — local file.
            assertEquals(
                android.net.Uri.fromFile(file0).toString(),
                engine.lastMediaItemUri
            )
            engine.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)
            assertEquals("Offline Local File", manager.playerState.value.audioEngineMode)

            // Chapter 1 — localFilePath set but the file is GONE: fall back to
            // the network stream rather than preparing a dead file URI.
            manager.nextChapter()
            assertEquals(mixedTracks[1].url, engine.lastMediaItemUri)
            engine.simulateReady(chapters[1].durationSeconds * MILLIS_PER_SECOND)
            assertEquals("4read Direct Stream", manager.playerState.value.audioEngineMode)

            // Chapter 2 — local file again.
            manager.nextChapter()
            assertEquals(android.net.Uri.fromFile(file2).toString(), engine.lastMediaItemUri)
            engine.simulateReady(chapters[2].durationSeconds * MILLIS_PER_SECOND)
            assertEquals("Offline Local File", manager.playerState.value.audioEngineMode)
        }

    // ---------------------------------------------------------------------
    // Regression guards for the Phase 2.5 hotfix (audit CR-002 / SF-003)
    // ---------------------------------------------------------------------

    @Test
    fun `player error reports the failure instead of fabricating audio`() = playerTest { manager, factory ->
        // Arrange
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = true)
        val engine = factory.current

        // Act
        engine.simulateNetworkError()

        // Assert -- no silent switch to an unrelated sample stream
        val state = manager.playerState.value
        assertEquals("Playback error", state.audioEngineMode)
        assertEquals("", state.currentStreamUrl)
        assertFalse(state.isPlaying)
        assertTrue(state.lastErrorMsg.isNotBlank())
        // Technical evidence belongs in telemetry; listener-facing copy stays
        // understandable and never leaks raw Media3 codes or host names.
        assertEquals("Не вдалося відтворити аудіо з цього джерела.", state.lastErrorMsg)
        // The shared engine survives the failure (see timeout test above).
        assertFalse(engine.isReleased)
        assertEquals("no replacement engine may be built", 1, factory.engines.size)

        // wayfinder #52: telemetry and the durable ledger both caught it.
        assertEquals(1, manager.playbackMetrics.failures())
        assertTrue(manager.playbackMetrics.failureByCode().containsKey("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"))
        assertTrue(manager.playbackEventLog.export().contains("FAIL ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"))
        // The ledger write hops through the real IO dispatcher, so drain with
        // a bounded real-time poll instead of advancing virtual time (the
        // progress tracker re-schedules forever; advanceUntilIdle never idles).
        awaitLedgerRows(1)
        assertEquals(1, dao.savedFailures.size)
        assertEquals("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", dao.savedFailures.first().errorCodeName)
        assertEquals(playable[0].track!!.url, dao.savedFailures.first().streamUrl)
        // wayfinder #61 Q1: the Media3 IO code classifies as SOURCE_LOST.
        assertEquals("SOURCE_LOST", dao.savedFailures.first().category)
    }

    @Test
    fun `completion on the final chapter stops instead of wrapping around`() = playerTest { manager, factory ->
        // Arrange
        val lastIndex = chapters.lastIndex
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = lastIndex, autoPlay = true)
        val engine = factory.current
        val durationMs = chapters[lastIndex].durationSeconds * MILLIS_PER_SECOND
        engine.simulateReady(durationMs)

        // Act
        engine.simulateEnded()

        // Assert
        val state = manager.playerState.value
        assertEquals(lastIndex, state.currentChapterIndex)
        assertFalse(state.isPlaying)
        assertEquals(durationMs, state.currentPositionMs)
        assertEquals("no chapter after the last one", 1, factory.engines.size)
    }

    @Test
    fun `stopAndClear stops playback and resets the state`() = playerTest { manager, factory ->
        // Arrange -- a playing book
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = true)
        val engine = factory.current
        engine.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)
        assertTrue(manager.playerState.value.isPlaying)

        // Act -- spec #8 ticket T3: deleting the playing book
        manager.stopAndClear()

        // Assert -- pristine state, engine stopped but kept alive
        val state = manager.playerState.value
        assertNull(state.currentBook)
        assertTrue(state.chapters.isEmpty())
        assertFalse(state.isPlaying)
        assertEquals(0L, state.currentPositionMs)
        assertEquals("", state.currentStreamUrl)
        assertTrue("the engine must be stopped", engine.stopCount >= 1)
        assertFalse("the engine survives for future books", engine.isReleased)
    }

    // ---------------------------------------------------------------------
    // Spec-13 T2 — per-source stream headers: the manager applies the book's
    // source Referer as default request properties BEFORE each chapter prepare
    // (the shared redirectto.cc CDN 403s without it), and resets to empty for
    // sources that serve plain GETs. No headers ever leak onto hosts that need
    // none (SEC-004).
    // ---------------------------------------------------------------------

    @Test
    fun `sluhay book applies the sluhay referer as the stream headers`() = playerTest { manager, _ ->
        // Arrange — a book whose primary source is sluhay.com.
        val sluhayBook = book.copy(
            sourceUrl = "https://sluhay.com/svitova-literatura/6150-dzho-aberkrombi-trohi-nenavisti.html"
        )

        // Act
        manager.loadAndPlayBook(sluhayBook, chapters, playable = playable, initialChapterIndex = 0, autoPlay = false)

        // Assert — the shared CDN gate is met per book, never globally.
        assertEquals(
            mapOf("Referer" to "https://sluhay.com/"),
            manager.lastAppliedStreamHeaders
        )
    }

    @Test
    fun `audiobookmp3 book applies its own referer on the same cdn family`() = playerTest { manager, _ ->
        val mp3Book = book.copy(
            sourceUrl = "https://audiobook-mp3.com/uk/1/2/3.html"
        )

        manager.loadAndPlayBook(mp3Book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = false)

        assertEquals(
            mapOf("Referer" to "https://audiobook-mp3.com/uk"),
            manager.lastAppliedStreamHeaders
        )
    }

    @Test
    fun `4read book applies the source referer`() = playerTest { manager, _ ->
        val reasdPlayable = playable.mapIndexed { index, pair ->
            pair.copy(track = pair.track?.copy(url = "https://s1.reasd.org/5370/chapter-$index.mp3"))
        }
        manager.loadAndPlayBook(
            book,
            chapters,
            playable = reasdPlayable,
            initialChapterIndex = 0,
            autoPlay = false
        )

        assertEquals(
            mapOf("Referer" to "https://4read.org/"),
            manager.lastAppliedStreamHeaders
        )
    }

    @Test
    fun `playback speed reaches the engine`() = playerTest { manager, factory ->
        // Arrange
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = false)
        val engine = factory.current
        engine.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)

        // Act
        manager.setPlaybackSpeed(FASTER_SPEED)

        // Assert
        assertEquals(FASTER_SPEED, engine.appliedSpeed, SPEED_TOLERANCE)
        assertEquals(FASTER_SPEED, manager.playerState.value.playbackSpeed, SPEED_TOLERANCE)
        assertNotEquals(1.0f, engine.appliedSpeed)
    }

    // ---------------------------------------------------------------------
    // Spec-16 T2: the playback event trail — captured through the repository
    // seam, with noise (ticks, sub-threshold seeks, quick toggles) filtered
    // ---------------------------------------------------------------------

    @Test
    fun `a listening session leaves the expected event trail`() {
        val clock = TestClock()
        playerTest(clock = clock) { manager, factory ->
            // Fresh autoplay start → RESUME at the beginning.
            manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = true)
            factory.current.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)
            awaitEvents(1)
            assertEquals(listOf(PlaybackEventKind.RESUME), dao.savedPlaybackEvents.map { it.kind })
            assertEquals(0L, dao.savedPlaybackEvents.single().positionSeconds)

            // A 6-minute seek is a SEEK transition with from → to.
            manager.seekTo(6 * 60 * MILLIS_PER_SECOND)
            awaitEvents(2)
            val seek = dao.savedPlaybackEvents.first { it.kind == PlaybackEventKind.SEEK }
            assertEquals(0L, seek.fromPositionSeconds)
            assertEquals(360L, seek.positionSeconds)

            // Pause after a 61-second segment is recorded.
            clock.ms += 61_000L
            manager.pause()
            awaitEvents(3)
            assertTrue(dao.savedPlaybackEvents.any { it.kind == PlaybackEventKind.PAUSE })

            // Resume after a 61-second break is recorded (second RESUME).
            clock.ms += 61_000L
            manager.play()
            awaitEvents(4)
            assertEquals(2, dao.savedPlaybackEvents.count { it.kind == PlaybackEventKind.RESUME })

            // Deliberate chapter changes and the final completion.
            manager.nextChapter()
            factory.current.simulateReady(chapters[1].durationSeconds * MILLIS_PER_SECOND)
            awaitEvents(5)
            manager.nextChapter()
            factory.current.simulateReady(chapters[2].durationSeconds * MILLIS_PER_SECOND)
            awaitEvents(6)
            factory.current.simulateEnded()
            awaitEvents(7)

            val kinds = dao.savedPlaybackEvents.map { it.kind }
            assertEquals(
                listOf(
                    PlaybackEventKind.RESUME, PlaybackEventKind.SEEK, PlaybackEventKind.PAUSE,
                    PlaybackEventKind.RESUME, PlaybackEventKind.CHAPTER_CHANGE, PlaybackEventKind.CHAPTER_CHANGE,
                    PlaybackEventKind.COMPLETED
                ),
                kinds
            )
            val completed = dao.savedPlaybackEvents.first { it.kind == PlaybackEventKind.COMPLETED }
            assertEquals(chapters.lastIndex, completed.chapterIndex)
        }
    }

    @Test
    fun `sub-threshold seeks never land in the log`() {
        val clock = TestClock()
        playerTest(clock = clock) { manager, factory ->
            manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = true)
            factory.current.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)
            awaitEvents(1)

            manager.seekTo(30_000L)
            manager.seekTo(45_000L)

            assertEventCountStays(1)
            assertEquals(PlaybackEventKind.RESUME, dao.savedPlaybackEvents.single().kind)
        }
    }

    @Test
    fun `quick pause and resume toggles are noise`() {
        val clock = TestClock()
        playerTest(clock = clock) { manager, factory ->
            manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = true)
            factory.current.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)
            awaitEvents(1)

            clock.ms += 5_000L
            manager.pause()
            clock.ms += 5_000L
            manager.play()

            assertEventCountStays(1)
            assertEquals(PlaybackEventKind.RESUME, dao.savedPlaybackEvents.single().kind)
        }
    }

    @Test
    fun `loading the same book from another source keeps one listening identity`() {
        val clock = TestClock()
        playerTest(clock = clock) { manager, _ ->
            manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = true)
            awaitEvents(1)
            assertEquals(listOf(PlaybackEventKind.RESUME), dao.savedPlaybackEvents.map { it.kind })

            val soundBooksBook = book.copy(sourceUrl = "https://sound-books.net/books/kobzar.html")
            manager.loadAndPlayBook(soundBooksBook, chapters, playable = playable, initialChapterIndex = 0, autoPlay = true)
            awaitEvents(2)

            // ADR-0007: switching the source is NOT a listening-state
            // transition — progress is keyed by the Edition, so the second
            // load is just another RESUME, and every new row writes
            // sourceKey = "" (the column is history).
            val kinds = dao.savedPlaybackEvents.map { it.kind }
            assertEquals(listOf(PlaybackEventKind.RESUME, PlaybackEventKind.RESUME), kinds)
            assertTrue(
                "no SOURCE_SWITCH may be recorded",
                dao.savedPlaybackEvents.none { it.kind == PlaybackEventKind.SOURCE_SWITCH }
            )
            assertTrue(
                "new event rows write sourceKey = \"\"",
                dao.savedPlaybackEvents.all { it.sourceKey.isEmpty() }
            )
        }
    }

    @Test
    fun `reaching the end of the book records completion exactly once`() {
        val clock = TestClock()
        playerTest(clock = clock) { manager, factory ->
            manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = chapters.lastIndex, autoPlay = true)
            factory.current.simulateReady(chapters.last().durationSeconds * MILLIS_PER_SECOND)
            awaitEvents(1)

            factory.current.simulateEnded()
            awaitEvents(2)
            // A duplicate ENDED observation must not re-record the completion.
            factory.current.simulateEnded()
            assertEventCountStays(2)

            assertEquals(
                listOf(PlaybackEventKind.RESUME, PlaybackEventKind.COMPLETED),
                dao.savedPlaybackEvents.map { it.kind }
            )
            val completed = dao.savedPlaybackEvents.first { it.kind == PlaybackEventKind.COMPLETED }
            assertEquals(chapters.lastIndex, completed.chapterIndex)
            assertEquals(chapters.last().durationSeconds, completed.positionSeconds)
        }
    }

    // ---------------------------------------------------------------------
    // Spec-16 T3: persistent undo — the «Повернутися» offer survives a restart
    // (SeekHistory is a thin facade over the event log)
    // ---------------------------------------------------------------------

    @Test
    fun `the undo offer survives a restart via the event log`() {
        val clock = TestClock()
        // Session 1: a 9-minute seek leaves a SEEK candidate in the log
        // (a non-autoplay load records no RESUME).
        playerTest(clock = clock) { manager, factory ->
            manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, initialPositionSeconds = 60L, autoPlay = false)
            factory.current.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)
            manager.seekTo(600_000L)
            awaitEvents(1) // the SEEK
            assertEquals(60L, dao.savedPlaybackEvents.single().fromPositionSeconds)
            assertEquals(600L, dao.savedPlaybackEvents.single().positionSeconds)
        }
        // Session 2 (process death): same repository, listener still where the
        // jump landed → the pre-jump position is offered again. The restore
        // runs on the injected test scheduler (#101), so runCurrent() drains
        // it deterministically — no wall-clock budget to flake under load.
        playerTest(clock = clock) { manager, factory ->
            manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, initialPositionSeconds = 600L, autoPlay = false)
            factory.current.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)
            runCurrent()
            assertTrue("restart must re-offer the pre-jump position", manager.playerState.value.canUndoSeek)
            assertEquals(60_000L, manager.playerState.value.undoFromPositionMs)
        }
    }

    @Test
    fun `undo logs the jump back and a restart never re-offers it`() {
        val clock = TestClock()
        playerTest(clock = clock) { manager, factory ->
            manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, initialPositionSeconds = 60L, autoPlay = false)
            factory.current.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)
            manager.seekTo(600_000L)
            awaitEvents(1)
            assertTrue(manager.playerState.value.canUndoSeek)

            manager.undoLastSeek()
            assertFalse(manager.playerState.value.canUndoSeek)
            awaitEvents(2) // SEEK + the undo-back SEEK

            // The jump back is itself a SEEK, logged with its from-position
            // withheld so it can never become the next undo candidate.
            val undoBack = dao.savedPlaybackEvents.last { it.kind == PlaybackEventKind.SEEK }
            assertEquals(60L, undoBack.positionSeconds)
            assertNull("undo-back must not carry a from-position", undoBack.fromPositionSeconds)
        }
        // A restart at the undone position must NOT re-offer the consumed jump.
        playerTest(clock = clock) { manager, factory ->
            manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, initialPositionSeconds = 60L, autoPlay = false)
            factory.current.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)
            runCurrent()
            assertFalse("a consumed undo must never re-offer", manager.playerState.value.canUndoSeek)
        }
    }

    @Test
    fun `restart away from the landing position does not re-offer`() {
        val clock = TestClock()
        playerTest(clock = clock) { manager, factory ->
            manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, initialPositionSeconds = 60L, autoPlay = false)
            factory.current.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)
            manager.seekTo(600_000L)
            awaitEvents(1)
        }
        // The listener has moved on — far from the landing point, so the stale
        // candidate must stay silent.
        playerTest(clock = clock) { manager, factory ->
            manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, initialPositionSeconds = 1_800L, autoPlay = false)
            factory.current.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)
            runCurrent()
            assertFalse(manager.playerState.value.canUndoSeek)
        }
    }

    // ---------------------------------------------------------------------
    // Spec-16 T4: re-listen cycle — finishing logs COMPLETED once, starting a
    // finished book again resets to the beginning and logs RELISTEN
    // ---------------------------------------------------------------------

    @Test
    fun `finishing a book then starting it again logs the finish to relisten cycle`() {
        val clock = TestClock()
        // Session 1: play the last chapter to the very end → COMPLETED.
        playerTest(clock = clock) { manager, factory ->
            manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = chapters.lastIndex, autoPlay = true)
            factory.current.simulateReady(chapters.last().durationSeconds * MILLIS_PER_SECOND)
            awaitEvents(1) // RESUME
            factory.current.simulateEnded()
            awaitEvents(2) // + COMPLETED
            assertEquals(
                listOf(PlaybackEventKind.RESUME, PlaybackEventKind.COMPLETED),
                dao.savedPlaybackEvents.map { it.kind }
            )
        }
        // Session 2: starting the finished book again (its saved end position)
        // resets to chapter 0 / position 0 and logs RELISTEN — the history
        // now shows the honest finish → re-listen cycle.
        playerTest(clock = clock) { manager, factory ->
            val totalSeconds = chapters.sumOf { it.durationSeconds }
            manager.loadAndPlayBook(
                book, chapters, playable = playable,
                initialChapterIndex = chapters.lastIndex,
                initialPositionSeconds = totalSeconds,
                autoPlay = true
            )
            factory.current.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)
            awaitEvents(3) // RESUME + COMPLETED + RELISTEN

            assertEquals(
                listOf(
                    PlaybackEventKind.RESUME, PlaybackEventKind.COMPLETED, PlaybackEventKind.RELISTEN
                ),
                dao.savedPlaybackEvents.map { it.kind }
            )
            assertEquals(0, manager.playerState.value.currentChapterIndex)
            assertEquals(0L, manager.playerState.value.currentPositionMs)
            assertEquals(playable[0].track!!.url, factory.current.lastMediaItemUri)
        }
    }

    @Test
    fun `an explicit chapter tap at position zero never relistens`() {
        val clock = TestClock()
        playerTest(clock = clock) { manager, factory ->
            // Deliberate navigation: an explicit chapter at position 0, even
            // though the book may be finished — the rule only fires for a
            // start at/after the very end of the book.
            manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 2, initialPositionSeconds = 0L, autoPlay = true)
            factory.current.simulateReady(chapters[2].durationSeconds * MILLIS_PER_SECOND)
            awaitEvents(1)

            assertEquals(listOf(PlaybackEventKind.RESUME), dao.savedPlaybackEvents.map { it.kind })
            assertEquals(2, manager.playerState.value.currentChapterIndex)
            assertEquals(0L, manager.playerState.value.currentPositionMs)
        }
    }

    @Test
    fun `resuming a nearly finished book keeps its position`() {
        val clock = TestClock()
        playerTest(clock = clock) { manager, factory ->
            // The stored player position is chapter-local. Resume 60 s before
            // the end of the selected final chapter: it is not finished, so
            // READY must seek there instead of resetting to 0 / RELISTEN.
            val resumePosition = chapters.last().durationSeconds - 60L
            manager.loadAndPlayBook(
                book, chapters, playable = playable,
                initialChapterIndex = chapters.lastIndex,
                initialPositionSeconds = resumePosition,
                autoPlay = true
            )
            factory.current.simulateReady(chapters.last().durationSeconds * MILLIS_PER_SECOND)
            awaitEvents(1)

            assertEquals(listOf(PlaybackEventKind.RESUME), dao.savedPlaybackEvents.map { it.kind })
            assertEquals(chapters.lastIndex, manager.playerState.value.currentChapterIndex)
            assertEquals(resumePosition * 1000L, manager.playerState.value.currentPositionMs)
        }
    }

    @Test
    fun `opening a finished book without playing does not relisten`() {
        val clock = TestClock()
        playerTest(clock = clock) { manager, factory ->
            val totalSeconds = chapters.sumOf { it.durationSeconds }
            manager.loadAndPlayBook(
                book, chapters, playable = playable,
                initialChapterIndex = chapters.lastIndex,
                initialPositionSeconds = totalSeconds,
                autoPlay = false
            )
            factory.current.simulateReady(chapters.last().durationSeconds * MILLIS_PER_SECOND)
            settle()

            assertTrue("no playback started → no events at all", dao.savedPlaybackEvents.isEmpty())
            assertEquals(chapters.lastIndex, manager.playerState.value.currentChapterIndex)
            assertEquals(totalSeconds * 1000L, manager.playerState.value.currentPositionMs)
        }
    }

    @Test
    fun `forceRelisten resets a start that position alone would keep`() {
        val clock = TestClock()
        playerTest(clock = clock) { manager, factory ->
            // The gap #40 decision 1 covers: a saved position strictly below
            // the book total (last chapter, in-chapter seconds) — the
            // position-based rule would RESUME here, but «Почати спочатку»
            // must deterministically restart.
            val totalSeconds = chapters.sumOf { it.durationSeconds }
            manager.loadAndPlayBook(
                book, chapters, playable = playable,
                initialChapterIndex = chapters.lastIndex,
                initialPositionSeconds = totalSeconds - 60L,
                autoPlay = true,
                forceRelisten = true
            )
            factory.current.simulateReady(chapters[0].durationSeconds * MILLIS_PER_SECOND)
            awaitEvents(1) // RELISTEN replaces RESUME for the forced restart

            assertEquals(
                listOf(PlaybackEventKind.RELISTEN),
                dao.savedPlaybackEvents.map { it.kind }
            )
            assertEquals(0, manager.playerState.value.currentChapterIndex)
            assertEquals(0L, manager.playerState.value.currentPositionMs)
        }
    }

    /**
     * Builds a manager wired to a [RecordingPlayerFactory], runs [body] on the
     * shared test scheduler, and always releases the manager so its
     * progress-tracker loop cannot outlive the test.
     *
     * Spec-16 T2: [clock] injects the manager's wall clock so the event
     * capture filter (1-minute segments, 5-minute seeks) is deterministic.
     */
    // ---------------------------------------------------------------------
    // Spec-32 T4 (#234): self-healing stream URLs. A 404/403 stream failure
    // re-fetches the source page ONCE through the healer seam, re-prepares
    // with the fresh URL, and only surfaces the honest failure when the fresh
    // URL is dead too. The decision itself is the pure [StreamHealPolicy]
    // (JVM-tested in isolation); here we verify the wiring: exactly one heal,
    // no heal loops, honest state on exhaustion.
    // ---------------------------------------------------------------------

    /** A real-looking ExoPlayer HTTP failure with the given status. */
    private fun streamErrorOf(code: Int): PlaybackException {
        val cause = HttpDataSource.InvalidResponseCodeException(
            code,
            "HTTP $code",
            null,
            emptyMap(),
            DataSpec(android.net.Uri.parse(playable[0].track!!.url)),
            ByteArray(0)
        )
        return PlaybackException("HTTP $code", cause, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
    }

    @Test
    fun `late heal result cannot replace or stop a newly selected chapter`() {
        for (healedUrl in listOf(HEALED_URL, null)) {
            val result = kotlinx.coroutines.CompletableDeferred<String?>()
            playerTest(healer = HealerSeam { _, _, _ -> result.await() }) { manager, factory ->
                manager.loadAndPlayBook(book, chapters, playable = playable, autoPlay = true)
                val engine = factory.current
                engine.simulateError(streamErrorOf(404))
                runCurrent()
                manager.prepareChapter(1)
                engine.simulateReady()
                result.complete(healedUrl)
                runCurrent()
                assertEquals(1, manager.playerState.value.currentChapterIndex)
                assertEquals(playable[1].track!!.url, engine.lastMediaItemUri)
                assertTrue(engine.isPlaying)
                assertTrue(manager.playerState.value.lastErrorMsg.isBlank())
            }
        }
    }

    @Test
    fun `pause during heal is preserved when the replacement URL arrives`() {
        val result = kotlinx.coroutines.CompletableDeferred<String?>()
        playerTest(healer = HealerSeam { _, _, _ -> result.await() }) { manager, factory ->
            manager.loadAndPlayBook(book, chapters, playable = playable, autoPlay = true)
            val engine = factory.current
            engine.simulateError(streamErrorOf(404))
            runCurrent()
            manager.pause()
            result.complete(HEALED_URL)
            runCurrent()
            engine.simulateReady()
            assertEquals(HEALED_URL, engine.lastMediaItemUri)
            assertFalse(engine.isPlaying)
            assertFalse(manager.playerState.value.isPlaying)
        }
    }

    @Test
    fun `a 404 stream failure heals once - fresh URL re-prepares the same engine`() =
        playerTest(healer = HealerSeam { _, _, _ -> HEALED_URL }) { manager, factory ->
            manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = true)
            val engine = factory.current

            engine.simulateError(streamErrorOf(404))
            runCurrent() // the heal runs on the test dispatcher

            assertEquals("one heal retry", 2, engine.prepareCount)
            assertEquals(HEALED_URL, engine.lastMediaItemUri)
            assertEquals(HEALED_URL, manager.playerState.value.currentStreamUrl)
            assertTrue(manager.playerState.value.isBuffering)
            assertEquals("a successful heal is not a failure", 0, manager.playbackMetrics.failures())

            engine.simulateReady(90_000L)
            assertTrue(manager.playerState.value.isPlaying)
            assertEquals(HEALED_URL, manager.playerState.value.currentStreamUrl)
        }

    @Test
    fun `a 403 stream failure heals like a 404`() =
        playerTest(healer = HealerSeam { _, _, _ -> HEALED_URL }) { manager, factory ->
            manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = true)
            val engine = factory.current

            engine.simulateError(streamErrorOf(403))
            runCurrent()

            assertEquals(2, engine.prepareCount)
            assertEquals(HEALED_URL, engine.lastMediaItemUri)
            assertEquals(0, manager.playbackMetrics.failures())
        }

    @Test
    fun `a dead fresh URL surfaces the honest failure - the heal budget allows one retry only`() =
        playerTest(healer = HealerSeam { _, _, _ -> HEALED_URL }) { manager, factory ->
            manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = true)
            val engine = factory.current

            engine.simulateError(streamErrorOf(404))
            runCurrent()
            assertEquals("the heal retry happened", 2, engine.prepareCount)

            // The fresh URL is dead too — the budget is spent, no second heal.
            engine.simulateError(streamErrorOf(404))
            runCurrent()

            assertEquals("no heal loop", 2, engine.prepareCount)
            assertEquals(1, manager.playbackMetrics.failures())
            val state = manager.playerState.value
            assertFalse(state.isBuffering)
            assertFalse(state.isPlaying)
            assertTrue("honest unavailable message, got: ${state.lastErrorMsg}", state.lastErrorMsg.contains("недоступн"))
            awaitLedgerRows(1)
            assertEquals("STREAM_HEAL_FAILED", dao.savedFailures.first().errorCodeName)
        }

    @Test
    fun `a server error never heals`() =
        playerTest(healer = HealerSeam { _, _, _ -> error("a 500 must never reach the healer") }) { manager, factory ->
            manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = true)
            val engine = factory.current

            engine.simulateError(streamErrorOf(500))
            runCurrent()

            assertEquals("no heal retry", 1, engine.prepareCount)
            assertEquals(1, manager.playbackMetrics.failures())
            // Issue #381: the failure text is a Ukrainian resource now —
            // the typed kind is the stable contract, not the wording.
            assertEquals(
                PlaybackErrorKind.TRANSIENT,
                manager.playerState.value.errorKind
            )
            assertTrue(manager.playerState.value.lastErrorMsg.isNotBlank())
        }

    @Test
    fun `a heal that yields nothing surfaces the honest failure`() =
        playerTest(healer = HealerSeam { _, _, _ -> null }) { manager, factory ->
            manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = true)
            val engine = factory.current

            engine.simulateError(streamErrorOf(404))
            runCurrent()

            assertEquals("no retry without a fresh URL", 1, engine.prepareCount)
            val state = manager.playerState.value
            assertFalse(state.isBuffering)
            assertTrue("honest unavailable message, got: ${state.lastErrorMsg}", state.lastErrorMsg.contains("недоступн"))
            assertEquals(1, manager.playbackMetrics.failures())
            awaitLedgerRows(1)
        }

    @Test
    fun `selecting a missing track stops old audio and retains requested chapter position`() = playerTest { manager, factory ->
        val incomplete = playable.mapIndexed { index, item ->
            if (index == 1) item.copy(track = null) else item
        }
        manager.loadAndPlayBook(book, chapters, playable = incomplete, autoPlay = true)
        val engine = factory.current
        engine.simulateReady()
        assertTrue(engine.isPlaying)

        manager.prepareChapter(1, startPositionMs = 42_000)

        assertFalse("Previous audio stops on a missing chapter", engine.isPlaying)
        assertTrue(engine.stopCount > 0)
        assertEquals(1, manager.playerState.value.currentChapterIndex)
        assertEquals(42_000L, manager.playerState.value.currentPositionMs)
        assertFalse(manager.playerState.value.isBuffering)
        assertTrue(manager.playerState.value.lastErrorMsg.isNotBlank())
        engine.simulateReady()
        assertFalse("A late READY cannot restart the old media", engine.isPlaying)
    }

    @Test
    fun `a book without chapters stops the engine and surfaces the honest unavailable state`() = playerTest { manager, factory ->
        // Arrange -- the PREVIOUS book is loaded and playing on the engine
        // (the 2026-08-26 device bug scene: «грає попередня книга»).
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = true)
        val engine = factory.current
        engine.simulateReady()
        assertTrue(engine.isPlaying)
        val previousPrepareCount = engine.prepareCount
        awaitEvents(1) // the healthy load records its RESUME event

        // Act -- the user opens a book whose chapters never resolved: the
        // source page carries no playerjs audio at all (0 chapters, 0 tracks).
        val chapterless = TestDataFactory.dataBooks().first { it.id != book.id }
        manager.loadAndPlayBook(chapterless, emptyList(), playable = emptyList(), autoPlay = true)

        // Assert -- the engine is stopped: the previous book's audio must not
        // keep playing under the new book's UI, and nothing is fabricated.
        assertTrue("the engine must be stopped", engine.stopCount > 0)
        assertFalse("no audio may keep playing", engine.isPlaying)
        assertEquals(
            "a chapterless book must never reach the engine",
            previousPrepareCount,
            engine.prepareCount
        )

        // Assert -- the honest state per ADR-0014/ADR-0019: the unavailable
        // book is surfaced with its «недоступна» message and NO fake
        // «00:01» duration placeholder.
        val state = manager.playerState.value
        assertEquals(chapterless.id, state.currentBook?.id)
        assertTrue(state.chapters.isEmpty())
        assertEquals("no fabricated 1s duration", 0L, state.durationMs)
        assertFalse(state.isBuffering)
        assertTrue(
            "the honest unavailable state must surface",
            state.lastErrorMsg.contains("недоступна", ignoreCase = true)
        )

        // Assert -- no fabricated RESUME event for a book that never played.
        assertEventCountStays(1)
    }

    @Test
    fun `a youtube track resolves per use and prepares the engine with the resolved url`() = playerTest(
        resolver = { "https://cdn.example.org/fresh-audio.m4a" }
    ) { manager, factory ->
        // Arrange -- the persisted track locator is a YouTube watch URL
        // (spec 2026-08-26: an audio-less 4read page persists the embed's
        // watch URL; the signed stream URL never is).
        val ytTrack = playable[0].track!!.copy(url = "https://www.youtube.com/watch?v=ozaZXk5Qcwc")
        manager.loadAndPlayBook(
            book,
            chapters,
            playable = listOf(playable[0].copy(track = ytTrack)),
            autoPlay = true
        )
        runCurrent() // the resolution launch rides the test scheduler

        // Assert -- the engine prepared the RESOLVED url, never the watch URL
        val engine = factory.current
        assertEquals(1, engine.prepareCount)
        assertEquals("https://cdn.example.org/fresh-audio.m4a", engine.lastMediaItemUri)
    }

    @Test
    fun `a failed youtube resolution reports the honest failure and never prepares the engine`() = playerTest(
        resolver = { null }
    ) { manager, factory ->
        val ytTrack = playable[0].track!!.copy(url = "https://www.youtube.com/watch?v=ozaZXk5Qcwc")
        manager.loadAndPlayBook(
            book,
            chapters,
            playable = listOf(playable[0].copy(track = ytTrack)),
            autoPlay = true
        )
        runCurrent()

        assertEquals("no fabricated audio", 0, factory.current.prepareCount)
        val state = manager.playerState.value
        assertFalse(state.isBuffering)
        assertTrue(
            "the honest unavailable state must surface",
            state.lastErrorMsg.contains("недоступна", ignoreCase = true)
        )
    }

    @Test
    fun `the smart retry unavailability surfaces the honest UNAVAILABLE state`() = playerTest { manager, _ ->
        // Arrange — a book is loaded (the remote died before this; the smart
        // retry's re-resolve chain found nothing and the memo is exhausted).
        manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = true)

        // Act — #471: the bounded retry reports «Книга недоступна» instead of
        // knocking on the dead remote again (no fabricated retry, CR-002).
        manager.reportRetryUnavailable()

        // Assert — title-only UNAVAILABLE card: PlayerScreen shows the
        // explicit browser doors for any BROWSER source next to it.
        val state = manager.playerState.value
        assertEquals(PlaybackErrorKind.UNAVAILABLE, state.errorKind)
        assertFalse(state.isBuffering)
        assertFalse(state.isPlaying)
        assertTrue(
            "the honest unavailable state must surface",
            state.lastErrorMsg.contains("недоступна", ignoreCase = true)
        )
        assertEquals(1, manager.playbackMetrics.failureByCode()["SMART_RETRY_UNAVAILABLE"])
    }

    @Test
    fun `a user-initiated prepare resets the heal budget`() {
        var healCalls = 0
        playerTest(healer = HealerSeam { _, _, _ -> if (++healCalls == 1) HEALED_URL else HEALED_URL_2 }) { manager, factory ->
            manager.loadAndPlayBook(book, chapters, playable = playable, initialChapterIndex = 0, autoPlay = true)
            val engine = factory.current

            engine.simulateError(streamErrorOf(404))
            runCurrent() // heal 1: budget 0 -> 1
            assertEquals(2, engine.prepareCount)
            engine.simulateReady(90_000L)

            manager.prepareChapter(0) // user-initiated: budget resets
            engine.simulateError(streamErrorOf(404))
            runCurrent() // heal 2 again: budget 0 -> 1

            assertEquals("prepare + heal + user prepare + heal", 4, engine.prepareCount)
            assertEquals(HEALED_URL_2, engine.lastMediaItemUri)
            assertEquals(0, manager.playbackMetrics.failures())
        }
    }

    /** Spec-32 T4 (#234): non-function-typed holder so the trailing lambda stays the body. */
    private class HealerSeam(val heal: (suspend (String, Int, String) -> String?)?)

    private fun playerTest(
        clock: TestClock? = null,
        healer: HealerSeam? = null,
        // Spec 2026-08-26: the per-use stream resolution seam (YouTube watch
        // URLs). Null keeps the identity resolver (plain URL pass-through).
        resolver: (suspend (String) -> String?)? = null,
        body: suspend TestScope.(AudioPlayerManager, RecordingPlayerFactory) -> Unit
    ) = runTest(dispatcher) {
        val factory = RecordingPlayerFactory()
        val manager = AudioPlayerManager(
            context, listeningState,
            // ADR-0007: the fetcher yields chapter→track pairs; the explicit
            // playable list below carries the real tracks for URI assertions.
            { dao.getChaptersListForBook(it).map { ch -> SourceCatalog.PlayableChapter(ch, null) } },
            injectedPlayerFactory = factory,
            now = { clock?.ms ?: System.currentTimeMillis() },
            // Spec-32 T4 (#234): the self-healing seam — production wires
            // LibraryImport.refreshStreamUrl here; tests inject a fake.
            streamUrlHealer = healer?.heal,
            // Spec 2026-08-26: the YouTube per-use resolution seam.
            streamUrlResolver = resolver ?: { url -> url },
            // Spec-16 T3 flake (#101): the undo-candidate restore runs on the
            // test scheduler, so runCurrent() observes it instead of a
            // wall-clock awaitTrue budget that flakes under full-suite load.
            ioDispatcher = dispatcher,
            // Spec-22 T4: widget sync is a forever-running sampled collector;
            // keep it off the test scheduler.
            widgetSyncEnabled = false
        )
        try {
            body(manager, factory)
        } finally {
            manager.release()
        }
    }

    /**
     * Waits until the event log holds at least [expected] rows. The write
     * crosses the real IO dispatcher inside the repository, so a virtual-time
     * drain cannot observe it deterministically; this polls the in-memory fake
     * with a real-time budget (same pattern as [awaitLedgerRows]).
     */
    private suspend fun TestScope.awaitEvents(expected: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (dao.savedPlaybackEvents.size < expected && System.currentTimeMillis() < deadline) {
            runCurrent()
            realDelay(20)
        }
        assertTrue(
            "event log must hold at least $expected rows, got ${dao.savedPlaybackEvents.size}",
            dao.savedPlaybackEvents.size >= expected
        )
    }

    /**
     * Proves a negative: after a bounded real-time settle, the event log still
     * holds exactly [expected] rows (no spurious transitions leaked in).
     */
    private suspend fun TestScope.assertEventCountStays(expected: Int, settleMs: Long = 400L) {
        val deadline = System.currentTimeMillis() + settleMs
        while (System.currentTimeMillis() < deadline) {
            runCurrent()
            realDelay(10)
        }
        assertEquals("event log must stay at $expected rows", expected, dao.savedPlaybackEvents.size)
    }

    /** Bounded real-time settle so a negative assertion sees the async IO land. */
    private suspend fun TestScope.settle(ms: Long = 500L) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            runCurrent()
            realDelay(10)
        }
    }

    /**
     * Waits until the durable failure ledger holds at least [expected] rows.
     * The write crosses the real IO dispatcher inside the repository, so a
     * virtual-time drain cannot observe it deterministically; this polls the
     * in-memory fake with a real-time budget instead.
     */
    private suspend fun TestScope.awaitLedgerRows(expected: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (dao.savedFailures.size < expected && System.currentTimeMillis() < deadline) {
            runCurrent()
            realDelay(20)
        }
        assertTrue("ledger must hold $expected rows, got ${dao.savedFailures.size}", dao.savedFailures.size >= expected)
    }

    /**
     * Real-time sleep for poll loops. [delay] inside `runTest` is virtual and
     * advances the test scheduler, so a poll loop built on it busy-spins and
     * starves the real IO thread that writes the fake DAO (a wall-clock flake
     * under CI load). Sleeping on a real dispatcher yields the CPU.
     */
    private suspend fun realDelay(ms: Long) = withContext(Dispatchers.Default) { delay(ms) }

    /** Mutable wall clock for the spec-16 T2 capture-filter tests. */
    private class TestClock {
        var ms: Long = 0L
    }

    private companion object {
        /** Fixture book that is not marked downloaded, so playback streams. */
        const val STREAMING_BOOK_INDEX = 1
        const val MILLIS_PER_SECOND = 1_000L
        const val FASTER_SPEED = 1.5f
        const val SPEED_TOLERANCE = 0.001f
        /** The URL the fake healer "finds" after a 404/403 (spec-32 T4). */
        const val HEALED_URL = "https://cdn.sound-books.net/kobzar/healed-1.mp3"
        const val HEALED_URL_2 = "https://cdn.sound-books.net/kobzar/healed-2.mp3"
    }
}
