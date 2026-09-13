package com.slukhayka.audiobooks.data.duration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #528 — the pass repairs what is already poisoned, exactly once.
 *
 * The book below is the observed shape: fifteen chapters all carrying the
 * interstitial's 52 s, over fifteen DISTINCT files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChapterDurationRepairTest {

    private val editionId = "edition-1"

    private fun chapters() = (0 until 15).map { index ->
        ChapterEntity(
            id = "chapter-$index",
            bookId = "book-1",
            chapterIndex = index,
            title = "Розділ ${index + 1}",
            durationSeconds = 52L,
            editionId = editionId
        )
    }

    private fun tracks() = (0 until 15).map { index ->
        SourceTrackEntity(
            id = "soundbooks-${editionId}_tr_${index + 1}",
            sourceId = "soundbooks-$editionId",
            trackIndex = index,
            url = "https://arch.sound-books.net/4111/track-$index.mp3"
        )
    }

    private fun prefs() =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("repair-test", Context.MODE_PRIVATE)

    @Test
    fun `resets the poisoned book once and then does nothing`() = runTest {
        val dao = FakeAudiobookDao(chapters = chapters())
        dao.insertTracks(tracks())
        val repair = ChapterDurationRepair(dao, prefs())

        assertEquals("first pass repairs every poisoned chapter", 15, repair.runOnce())
        assertEquals("second pass is a no-op", 0, repair.runOnce())
    }

    @Test
    fun `an honest book is left untouched`() = runTest {
        val dao = FakeAudiobookDao(
            chapters = listOf(
                ChapterEntity("c0", "book-2", 0, "Розділ 1", 1701L, editionId),
                ChapterEntity("c1", "book-2", 1, "Розділ 2", 1200L, editionId)
            )
        )
        dao.insertTracks(
            listOf(
                SourceTrackEntity("soundbooks-${editionId}_tr_1", "soundbooks-$editionId", 0, "https://a/1.mp3"),
                SourceTrackEntity("soundbooks-${editionId}_tr_2", "soundbooks-$editionId", 1, "https://a/2.mp3")
            )
        )
        assertEquals(0, ChapterDurationRepair(dao, prefs()).runOnce())
    }
}
