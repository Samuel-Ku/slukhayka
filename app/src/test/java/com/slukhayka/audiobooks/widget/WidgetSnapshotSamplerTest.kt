package com.slukhayka.audiobooks.widget

import com.slukhayka.audiobooks.player.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1756 — the sampler must publish the empty frame when a live book is
 * closed, but must not overwrite the cold-start fallback before any book has
 * been seen. The bug this pins: the old sampler skipped every
 * `currentBook == null` observation, so an active widget session kept the last
 * playing frame (dead transport, stale progress).
 */
class WidgetSnapshotSamplerTest {

    private val emptyFrame = WidgetStateMapper.mapFromPlayerState(PlayerState())

    private val activeFrame = GlanceWidgetState(
        hasActiveBook = true,
        bookId = "book-1",
        title = "Кобзар",
        author = "Тарас Шевченко",
        chapterTitle = "Розділ 1",
        isPlaying = true,
        currentPositionSeconds = 125L,
        durationSeconds = 600L,
        progressFraction = 125f / 600f,
        positionFormatted = "02:05",
        durationFormatted = "10:00"
    )

    @Test
    fun coldStartWithoutABook_keepsTheFallbackFrame() {
        val sampler = WidgetSnapshotSampler()

        assertNull(sampler.nextSnapshot(emptyFrame, 0L))
        assertNull(
            "a cold-start empty observation must not repaint later either",
            sampler.nextSnapshot(emptyFrame, 60_000L)
        )
    }

    @Test
    fun closingTheBook_publishesTheEmptySnapshot() {
        val sampler = WidgetSnapshotSampler()
        assertEquals(activeFrame, sampler.nextSnapshot(activeFrame, 0L))

        val closed = sampler.nextSnapshot(emptyFrame, 1_000L)
        assertNotNull("book → null must publish, not keep the playing frame", closed)
        val frame = closed!!

        assertEquals(emptyFrame, frame)
        assertFalse(frame.hasActiveBook)
        assertFalse(frame.isPlaying)
        assertFalse(frame.isBuffering)
        assertNull(frame.bookId)
        assertEquals(0L, frame.currentPositionSeconds)
        assertEquals(0L, frame.durationSeconds)
        assertEquals(0f, frame.progressFraction, 0.001f)
        assertEquals("00:00", frame.positionFormatted)
        assertEquals("00:00", frame.durationFormatted)
        assertFalse(
            "the empty frame must not render dead transport controls",
            frame.showsTransportControls()
        )
        assertNull(
            "the empty frame must not be republished on every observation",
            sampler.nextSnapshot(emptyFrame, 2_000L)
        )
    }

    @Test
    fun bookOpenedAfterColdStart_isStillPublished() {
        val sampler = WidgetSnapshotSampler()
        assertNull(sampler.nextSnapshot(emptyFrame, 0L))

        assertEquals(activeFrame, sampler.nextSnapshot(activeFrame, 1_000L))
    }

    @Test
    fun completionKeepsTheBookFrame() {
        val sampler = WidgetSnapshotSampler()
        assertEquals(activeFrame, sampler.nextSnapshot(activeFrame, 0L))

        val completed = activeFrame.copy(
            isPlaying = false,
            currentPositionSeconds = 600L,
            progressFraction = 1f,
            positionFormatted = "10:00"
        )

        assertEquals(completed, sampler.nextSnapshot(completed, 15_000L))
        assertTrue(completed.hasActiveBook)
    }
}
