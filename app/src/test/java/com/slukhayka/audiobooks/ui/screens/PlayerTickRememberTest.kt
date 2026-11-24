package com.slukhayka.audiobooks.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1707 — the player's position ticks at ~1 Hz. Duration merging and the book
 * ruler's marker lists do not depend on that position, so they are remembered;
 * these tests prove the identity survives a tick instead of pinning an
 * implementation detail: without `remember` each recomposition produces a new
 * merged chapter list / marker object, and `assertSame` goes red.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerTickRememberTest {

    @get:Rule
    val compose = createComposeRule()

    private val book = AudiobookEntity(
        id = "b1",
        title = "Доктор Сон",
        author = "Стівен Кінг",
        narrator = "Олег Фомін",
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = "https://4read.org/doctor-sleep",
        isDownloaded = false,
        totalDurationSeconds = 0L,
        totalChapters = 3
    )

    private fun chapter(index: Int, duration: Long) = ChapterEntity(
        id = "c$index",
        bookId = "b1",
        chapterIndex = index,
        title = "Розділ ${index + 1}",
        durationSeconds = duration
    )

    private fun state(positionMs: Long) = PlayerState(
        currentBook = book,
        chapters = listOf(chapter(0, 600L), chapter(1, 600L), chapter(2, 600L)),
        currentChapterIndex = 0,
        isPlaying = true,
        currentPositionMs = positionMs,
        durationMs = 600_000L
    )

    @Test
    fun `a position tick does not rebuild the merged duration facts`() {
        val raw = mutableStateOf(state(positionMs = 0L))
        val liveBook = book.copy(totalDurationSeconds = 1_800L)
        val liveChapters = listOf(chapter(0, 600L), chapter(1, 610L), chapter(2, 600L))
        val observed = mutableListOf<PlayerState>()

        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                val playerState = rememberLiveDurationPlayerState(raw.value, liveBook, liveChapters)
                SideEffect { observed += playerState }
                Text("${playerState.currentPositionMs}")
            }
        }
        compose.waitForIdle()
        val beforeTick = observed.last()

        compose.runOnIdle { raw.value = raw.value.copy(currentPositionMs = 1_000L) }
        compose.waitForIdle()
        compose.runOnIdle { raw.value = raw.value.copy(currentPositionMs = 2_000L) }
        compose.waitForIdle()
        val afterTick = observed.last()

        compose.runOnIdle {
            assertEquals(2_000L, afterTick.currentPositionMs)
            assertEquals(liveBook, afterTick.currentBook)
            assertEquals(
                listOf(600L, 610L, 600L),
                afterTick.chapters.map { it.durationSeconds }
            )
            assertSame(
                "a position tick must reuse the merged chapter list, not rebuild it",
                beforeTick.chapters,
                afterTick.chapters
            )
        }
    }

    @Test
    fun `a position tick does not rebuild the effective chapter durations`() {
        val chapters = state(positionMs = 0L).chapters
        val position = mutableLongStateOf(0L)
        val observed = mutableListOf<List<Long>>()

        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                // #1733 — the chapter ruler PlayerScreen remembers for both the
                // markers and the per-tick progress.
                val durations = rememberEffectiveChapterDurations(
                    chapters = chapters,
                    currentChapterIndex = 1,
                    currentChapterDurationMs = 600_000L,
                    bookTotalDurationSeconds = 1_800L
                )
                SideEffect { observed += durations }
                Text("${position.longValue}")
            }
        }
        compose.waitForIdle()
        val beforeTick = observed.last()

        compose.runOnIdle { position.longValue = 1_000L }
        compose.waitForIdle()
        compose.runOnIdle { position.longValue = 2_000L }
        compose.waitForIdle()
        val afterTick = observed.last()

        compose.runOnIdle {
            assertTrue("the harness must actually recompose", observed.size >= 3)
            assertSame(
                "a position tick must reuse the remembered chapter ruler, not rebuild it",
                beforeTick,
                afterTick
            )
            assertEquals(listOf(600L, 600L, 600L), afterTick)
        }
    }

    @Test
    fun `a position tick does not rebuild the book ruler markers`() {
        val chapters = state(positionMs = 0L).chapters
        val position = mutableLongStateOf(0L)
        val bookmarks = listOf(
            BookmarkEntity(
                id = 1,
                bookId = "b1",
                chapterIndex = 1,
                chapterTitle = "Розділ 2",
                timestampSeconds = 30,
                note = "",
                createdAt = 1_700_000_000_000L
            )
        )
        val observed = mutableListOf<PlayerMarkers>()

        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                val markers = rememberPlayerMarkers(
                    chapters = chapters,
                    currentChapterIndex = 1,
                    currentChapterDurationMs = 600_000L,
                    bookmarks = bookmarks,
                    bookTotalDurationSeconds = 1_800L
                )
                SideEffect { observed += markers }
                Text("${position.longValue}")
            }
        }
        compose.waitForIdle()
        val beforeTick = observed.last()

        compose.runOnIdle { position.longValue = 1_000L }
        compose.waitForIdle()
        compose.runOnIdle { position.longValue = 2_000L }
        compose.waitForIdle()
        val afterTick = observed.last()

        compose.runOnIdle {
            assertTrue("the harness must actually recompose", observed.size >= 3)
            assertSame(
                "a position tick must reuse the remembered marker lists, not rebuild them",
                beforeTick,
                afterTick
            )
            assertSame(beforeTick.chapterMarkers, afterTick.chapterMarkers)
            assertSame(beforeTick.bookmarkMarkers, afterTick.bookmarkMarkers)
        }
    }
}
