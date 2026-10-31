package com.slukhayka.audiobooks.widget

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.glance.GlanceId
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.player.PlayerState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #1749 — the widget renders the snapshot in Glance's DataStore, not a value
 * captured by `provideGlance`. These tests pin the two halves of that
 * contract on the JVM: the snapshot codec, and the update path that writes a
 * new frame for every placed widget instance.
 */
class WidgetSnapshotCodecTest {

    @Test
    fun snapshotRoundTrip_keepsEveryRenderedField() {
        val original = activeState()

        val stored = mutablePreferencesOf().also { WidgetSnapshotCodec.write(it, original) }

        assertEquals(original, WidgetSnapshotCodec.read(stored))
    }

    @Test
    fun emptyPreferences_haveNoSnapshot_soColdStartStillRenders() {
        assertNull(WidgetSnapshotCodec.read(emptyPreferences()))
    }

    @Test
    fun publishingANewSnapshot_replacesTheFrameContentReads() = runTest {
        val ids = listOf(FakeGlanceId(1), FakeGlanceId(2))
        val stored = mutableMapOf<GlanceId, Preferences>()
        val refreshed = mutableListOf<GlanceId>()

        suspend fun publish(state: GlanceWidgetState): Int = publishWidgetSnapshot(
            state = state,
            ids = ids,
            write = { id, snapshot ->
                stored[id] = mutablePreferencesOf().also {
                    WidgetSnapshotCodec.write(it, snapshot)
                }
            },
            update = { id -> refreshed += id }
        )

        val paused = activeState(isPlaying = false, positionMs = 60_000L)
        val playing = activeState(isPlaying = true, positionMs = 75_000L)

        assertEquals(2, publish(paused))
        ids.forEach { id ->
            assertEquals(paused, WidgetSnapshotCodec.read(stored.getValue(id)))
        }

        refreshed.clear()
        assertEquals(2, publish(playing))
        ids.forEach { id ->
            // The content reads exactly the new frame, not the one captured
            // when the session started.
            assertEquals(playing, WidgetSnapshotCodec.read(stored.getValue(id)))
        }
        assertEquals(ids, refreshed)
    }

    @Test
    fun noPlacedWidget_publishesNothing() = runTest {
        var writes = 0
        var updates = 0

        val published = publishWidgetSnapshot(
            state = activeState(),
            ids = emptyList(),
            write = { _, _ -> writes++ },
            update = { updates++ }
        )

        assertEquals(0, published)
        assertEquals(0, writes)
        assertEquals(0, updates)
    }

    private fun activeState(
        isPlaying: Boolean = true,
        positionMs: Long = 125_000L
    ): GlanceWidgetState = WidgetStateMapper.mapFromPlayerState(
        PlayerState(
            currentBook = AudiobookEntity(
                id = "test_book_1",
                title = "Кобзар",
                author = "Тарас Шевченко",
                narrator = "Опанас Маркович",
                description = "Поетична збірка",
                coverDrawableRes = 0,
                genre = "Класика",
                sourceUrl = "https://4read.org/kobzar.html"
            ),
            chapters = listOf(
                ChapterEntity(
                    id = "ch_0",
                    bookId = "test_book_1",
                    chapterIndex = 0,
                    title = "Думи мої, думи мої",
                    durationSeconds = 600L
                )
            ),
            currentChapterIndex = 0,
            currentPositionMs = positionMs,
            durationMs = 600_000L,
            isPlaying = isPlaying
        )
    )

    private data class FakeGlanceId(val value: Int) : GlanceId
}
