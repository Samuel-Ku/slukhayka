package com.slukhayka.audiobooks.ui.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.personbookmarks.PersonBookmarks
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1706 — the person-bookmark Flows are cold DAO queries: every builder call
 * returns a NEW Flow instance, and `collectAsState` restarts its subscription
 * whenever that instance changes. Built in the composition body they were
 * rebuilt on every recomposition — the progress write ticks every ~5 s while
 * playing, and the «Огляд» search query changes on every keystroke.
 *
 * The counting DAO makes the regression visible: with `remember` the builders
 * run once for the whole composition, without it once per recomposition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PersonBookmarkFlowRememberTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the person bookmark flows are built once across recompositions`() {
        val dao = CountingDao()
        val bookmarks = PersonBookmarks(dao)
        val frame = mutableStateOf(0)

        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                val authors = rememberBookmarkedAuthors(bookmarks)
                val narrators = rememberBookmarkedNarrators(bookmarks)
                val all = rememberAllPersonBookmarks(bookmarks)
                Text("frame${frame.value}:${authors.size}/${narrators.size}/${all.size}")
            }
        }
        compose.waitForIdle()

        // Two real recompositions; the text proves both landed.
        compose.runOnIdle { frame.value = 1 }
        compose.waitForIdle()
        compose.runOnIdle { frame.value = 2 }
        compose.waitForIdle()
        compose.onNodeWithText("frame2:0/0/0").assertIsDisplayed()

        compose.runOnIdle {
            assertEquals(
                "bookmarkedAuthors() must be built once, not per recomposition",
                1,
                dao.authorQueries
            )
            assertEquals(
                "bookmarkedNarrators() must be built once, not per recomposition",
                1,
                dao.narratorQueries
            )
            assertEquals(
                "allBookmarks() must be built once, not per recomposition",
                1,
                dao.allQueries
            )
        }
    }

    /** Counts the builders' DAO calls; everything else delegates to the fake. */
    private class CountingDao(
        private val delegate: AudiobookDao = FakeAudiobookDao()
    ) : AudiobookDao by delegate {
        var authorQueries = 0
            private set
        var narratorQueries = 0
            private set
        var allQueries = 0
            private set

        override fun getPersonBookmarksByKind(kind: String): Flow<List<PersonBookmarkEntity>> {
            when (kind) {
                PersonRole.AUTHOR.storageValue -> authorQueries += 1
                PersonRole.NARRATOR.storageValue -> narratorQueries += 1
            }
            return delegate.getPersonBookmarksByKind(kind)
        }

        override fun getAllPersonBookmarks(): Flow<List<PersonBookmarkEntity>> {
            allQueries += 1
            return delegate.getAllPersonBookmarks()
        }
    }
}
