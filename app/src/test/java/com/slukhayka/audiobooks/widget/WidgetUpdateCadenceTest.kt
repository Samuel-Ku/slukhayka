package com.slukhayka.audiobooks.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1749 — one widget repaint for discrete playback transitions, one per
 * progress interval for a steadily playing stream. The 2 s poll is gone; this
 * pins what took its place.
 */
class WidgetUpdateCadenceTest {

    @Test
    fun firstObservation_isPublishedImmediately() {
        val cadence = WidgetUpdateCadence()

        assertEquals(WidgetUpdateKind.DISCRETE, cadence.decide(state(), 0L))
    }

    @Test
    fun playPauseTransition_isDiscreteAndImmediate() {
        val cadence = WidgetUpdateCadence()
        assertEquals(WidgetUpdateKind.DISCRETE, cadence.decide(state(playing = true, position = 0), 0L))

        assertEquals(
            WidgetUpdateKind.DISCRETE,
            cadence.decide(state(playing = false, position = 0), 500L)
        )
        assertEquals(
            WidgetUpdateKind.DISCRETE,
            cadence.decide(state(playing = true, position = 1), 1_000L)
        )
    }

    @Test
    fun chapterAndBookChanges_areDiscrete() {
        val cadence = WidgetUpdateCadence()
        assertEquals(WidgetUpdateKind.DISCRETE, cadence.decide(state(chapter = "Розділ 1"), 0L))

        assertEquals(
            WidgetUpdateKind.DISCRETE,
            cadence.decide(state(chapter = "Розділ 2", position = 0), 1_000L)
        )
        assertEquals(
            WidgetUpdateKind.DISCRETE,
            cadence.decide(state(bookId = "book-2", chapter = "Розділ 1", position = 0), 2_000L)
        )
    }

    @Test
    fun bufferingTransition_isDiscrete() {
        val cadence = WidgetUpdateCadence()
        assertEquals(WidgetUpdateKind.DISCRETE, cadence.decide(state(buffering = false), 0L))

        assertEquals(
            WidgetUpdateKind.DISCRETE,
            cadence.decide(state(buffering = true), 500L)
        )
    }

    @Test
    fun forwardSeek_isDiscreteEvenInsideTheProgressWindow() {
        val cadence = WidgetUpdateCadence()
        assertEquals(WidgetUpdateKind.DISCRETE, cadence.decide(state(position = 0), 0L))
        assertEquals(WidgetUpdateKind.NONE, cadence.decide(state(position = 1), 1_000L))

        assertEquals(
            WidgetUpdateKind.DISCRETE,
            cadence.decide(state(position = 16), 2_000L)
        )
    }

    @Test
    fun backwardSeek_isDiscrete() {
        val cadence = WidgetUpdateCadence()
        assertEquals(WidgetUpdateKind.DISCRETE, cadence.decide(state(position = 30), 30_000L))

        assertEquals(
            WidgetUpdateKind.DISCRETE,
            cadence.decide(state(position = 15), 31_000L)
        )
    }

    @Test
    fun steadyPlayingTicks_publishProgressOnlyAtTheInterval() {
        val cadence = WidgetUpdateCadence()
        assertEquals(WidgetUpdateKind.DISCRETE, cadence.decide(state(position = 0), 0L))

        for (second in 1L..14L) {
            assertEquals(
                "second $second must stay silent",
                WidgetUpdateKind.NONE,
                cadence.decide(state(position = second), second * 1_000L)
            )
        }

        assertEquals(
            WidgetUpdateKind.PROGRESS,
            cadence.decide(state(position = 15), 15_000L)
        )
    }

    @Test
    fun pausedStream_neverPublishesProgress() {
        val cadence = WidgetUpdateCadence()
        assertEquals(
            WidgetUpdateKind.DISCRETE,
            cadence.decide(state(playing = false, position = 120), 0L)
        )

        assertEquals(
            WidgetUpdateKind.NONE,
            cadence.decide(state(playing = false, position = 120), 60_000L)
        )
    }

    @Test
    fun threeTimesSpeedProgress_isNotMistakenForASeek() {
        val cadence = WidgetUpdateCadence()
        assertEquals(WidgetUpdateKind.DISCRETE, cadence.decide(state(position = 0), 0L))

        for (second in 1L..5L) {
            assertEquals(
                WidgetUpdateKind.NONE,
                cadence.decide(state(position = second * 3), second * 1_000L)
            )
        }
    }

    @Test
    fun progressRepaint_restartsTheInterval() {
        val cadence = WidgetUpdateCadence()
        assertEquals(WidgetUpdateKind.DISCRETE, cadence.decide(state(position = 0), 0L))
        for (second in 1L..14L) {
            assertEquals(
                WidgetUpdateKind.NONE,
                cadence.decide(state(position = second), second * 1_000L)
            )
        }
        assertEquals(
            WidgetUpdateKind.PROGRESS,
            cadence.decide(state(position = 15), 15_000L)
        )

        for (second in 16L..29L) {
            assertEquals(
                WidgetUpdateKind.NONE,
                cadence.decide(state(position = second), second * 1_000L)
            )
        }
        assertEquals(
            WidgetUpdateKind.PROGRESS,
            cadence.decide(state(position = 30), 30_000L)
        )
    }

    private fun state(
        playing: Boolean = true,
        buffering: Boolean = false,
        bookId: String = "book-1",
        chapter: String = "Розділ 1",
        position: Long = 0L,
        duration: Long = 600L
    ): GlanceWidgetState = GlanceWidgetState(
        hasActiveBook = true,
        bookId = bookId,
        title = "Книга",
        author = "Автор",
        chapterTitle = chapter,
        isPlaying = playing,
        isBuffering = buffering,
        currentPositionSeconds = position,
        durationSeconds = duration
    )
}
