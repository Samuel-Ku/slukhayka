package com.slukhayka.audiobooks.accessibility

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.data.EditionId
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.SelectedTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Release-gate tracer through one real, fully local listener journey. The
 * fixture ships in the test APK, so accessibility checks never depend on a
 * catalogue server or an audio CDN.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 34)
class MainActivityAccessibilityTest {

    @get:Rule
    val notificationPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private val fixtureBookId = "accessibility-fixture-book"
    private val fixtureChapterId = "accessibility-fixture-chapter-1"
    private val fixtureTitle = "Лісова пісня"
    private val fixtureAuthor = "Леся Українка"
    private val fixtureNarrator = "Тестова оповідачка"

    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun seedDeterministicLocalJourney() {
        val localAudio = File(app.filesDir, "accessibility_fixture_short.mp3")
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("fixture_short.mp3")
            .use { input ->
                localAudio.outputStream().use { output -> input.copyTo(output) }
            }

        runBlocking(Dispatchers.IO) {
            val database = AudiobookDatabase.getDatabase(app)
            database.clearAllTables()
            val dao = database.audiobookDao()
            val mergeKey = MergeKey.keyFor(fixtureTitle, fixtureAuthor)
            val editionId = EditionId.forBook(
                mergeKey = mergeKey,
                bookId = fixtureBookId,
                narrator = fixtureNarrator
            )
            val source = SourceEntity(
                id = "local-$editionId",
                bookId = fixtureBookId,
                editionId = editionId,
                type = "local",
                url = "",
                streamOnly = false,
                addedAt = 1L
            )

            dao.insertAudiobooks(
                listOf(
                    AudiobookEntity(
                        id = fixtureBookId,
                        title = fixtureTitle,
                        author = fixtureAuthor,
                        narrator = fixtureNarrator,
                        description = "Локальна книга для перевірки доступності.",
                        coverDrawableRes = 0,
                        coverImageUrl = null,
                        genre = "Класика",
                        sourceUrl = "",
                        isDownloaded = true,
                        totalDurationSeconds = 3L,
                        totalChapters = 1,
                        rating = 5f
                    )
                )
            )
            dao.upsertWork(
                WorkEntity(
                    id = mergeKey,
                    mergeKey = mergeKey,
                    title = fixtureTitle,
                    author = fixtureAuthor,
                    addedAt = 1L
                )
            )
            dao.upsertLibraryEntry(
                id = fixtureBookId,
                workId = mergeKey,
                isFavorite = true,
                createdAt = 1L,
                downloadProgress = 1f
            )
            dao.insertEdition(
                EditionEntity(
                    id = editionId,
                    workId = fixtureBookId,
                    narrator = fixtureNarrator,
                    totalChapters = 1,
                    totalDurationSeconds = 3L
                )
            )
            dao.insertChapters(
                listOf(
                    ChapterEntity(
                        id = fixtureChapterId,
                        bookId = fixtureBookId,
                        editionId = editionId,
                        chapterIndex = 0,
                        title = "Дія перша",
                        durationSeconds = 3L
                    )
                )
            )
            dao.insertSources(listOf(source))
            dao.insertTracks(
                listOf(
                    SourceTrackEntity(
                        id = "${source.id}-tr-1",
                        sourceId = source.id,
                        trackIndex = 0,
                        url = localAudio.absolutePath,
                        localFilePath = localAudio.absolutePath,
                        isDownloaded = true
                    )
                )
            )
            dao.savePlaybackProgress(
                PlaybackProgressEntity(
                    editionId = editionId,
                    bookId = fixtureBookId,
                    currentChapterIndex = 0,
                    currentPositionSeconds = 0L,
                    lastListenedAt = 1L
                )
            )
        }
        composeTestRule.activity.runOnUiThread {
            ViewModelProvider(composeTestRule.activity)[MainViewModel::class.java].apply {
                playerManager.stopAndClear()
                setShowFullPlayer(false)
                selectBook(null)
                selectTab(SelectedTab.LISTEN)
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun seededLibraryBookChapterPlayerRoundTripPassesAccessibilityGate() {
        composeTestRule.waitUntilExactlyOneExists(
            hasTestTag("listen_screen"),
            timeoutMillis = NAV_TIMEOUT_MS
        )

        composeTestRule.enableAccessibilityChecks()
        composeTestRule.onRoot().tryPerformAccessibilityChecks()

        composeTestRule.onNodeWithTag("tab_library").performClick()
        composeTestRule.waitUntilExactlyOneExists(
            hasTestTag("library_book_item_$fixtureBookId"),
            timeoutMillis = NAV_TIMEOUT_MS
        )
        composeTestRule.onRoot().tryPerformAccessibilityChecks()
        composeTestRule.onNodeWithTag("library_book_item_$fixtureBookId")
            .assertIsDisplayed()
            .performClick()

        composeTestRule.waitUntilExactlyOneExists(
            hasTestTag("book_detail_screen"),
            timeoutMillis = NAV_TIMEOUT_MS
        )
        composeTestRule.onNodeWithTag("book_detail_screen")
            .performScrollToNode(hasTestTag("book_detail_chapter_$fixtureChapterId"))
        val chapter = composeTestRule.onNodeWithTag("book_detail_chapter_$fixtureChapterId")
        chapter
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
        composeTestRule.onRoot().tryPerformAccessibilityChecks()
        chapter.performClick()

        composeTestRule.waitUntilExactlyOneExists(
            hasTestTag("full_player_screen"),
            timeoutMillis = NAV_TIMEOUT_MS
        )
        composeTestRule.waitUntil(timeoutMillis = NAV_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("player_context")
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.config
                ?.getOrNull(SemanticsProperties.Focused) == true
        }
        composeTestRule.onNodeWithTag("player_context").assertIsFocused()
        composeTestRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Програвач"),
            useUnmergedTree = true
        ).assertCountEquals(1)
        composeTestRule.onNodeWithTag("app_background", useUnmergedTree = true)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.HideFromAccessibility,
                    Unit
                )
            )

        val wasPlaying = currentViewModel().playerState.value.isPlaying
        composeTestRule.onNodeWithTag("player_play_pause_button")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
            .performClick()
        composeTestRule.waitUntil(timeoutMillis = PLAYBACK_TIMEOUT_MS) {
            currentViewModel().playerState.value.isPlaying != wasPlaying
        }
        composeTestRule.onRoot().tryPerformAccessibilityChecks()

        composeTestRule.onNodeWithTag("close_player_button")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performClick()
        composeTestRule.waitUntil(timeoutMillis = NAV_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("full_player_screen")
                .fetchSemanticsNodes()
                .isEmpty()
        }
        composeTestRule.waitUntil(timeoutMillis = NAV_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("book_detail_chapter_$fixtureChapterId")
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.config
                ?.getOrNull(SemanticsProperties.Focused) == true
        }
        composeTestRule.onNodeWithTag("book_detail_chapter_$fixtureChapterId")
            .assertIsFocused()
        composeTestRule.onNodeWithTag("app_background", useUnmergedTree = true)
            .assert(
                SemanticsMatcher("is visible to accessibility") { node ->
                    node.config.getOrNull(SemanticsProperties.HideFromAccessibility) == null
                }
            )
        composeTestRule.onRoot().tryPerformAccessibilityChecks()
    }

    private fun currentViewModel(): MainViewModel =
        ViewModelProvider(composeTestRule.activity)[MainViewModel::class.java]

    companion object {
        private const val NAV_TIMEOUT_MS = 20_000L
        private const val PLAYBACK_TIMEOUT_MS = 5_000L
    }
}
