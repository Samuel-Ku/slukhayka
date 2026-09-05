package com.slukhayka.audiobooks.accessibility

import android.app.Application
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Release-gate tracer through one real, fully local listener journey. The
 * test generates its deterministic WAV locally, so accessibility checks never
 * depend on a catalogue server or an audio CDN.
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
        val localAudio = File(app.filesDir, "accessibility_fixture_stable.wav")
        writeSilentWav(localAudio, durationSeconds = FIXTURE_DURATION_SECONDS.toInt())

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
                        totalDurationSeconds = FIXTURE_DURATION_SECONDS,
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
                    totalDurationSeconds = FIXTURE_DURATION_SECONDS
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
                        durationSeconds = FIXTURE_DURATION_SECONDS
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
        assertEquals(
            "uk",
            composeTestRule.activity.resources.configuration.locales[0].language
        )
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
        chapter.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
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

        composeTestRule.waitUntil(timeoutMillis = NAV_TIMEOUT_MS) {
            currentViewModel().playerState.value.run {
                isPlaying && durationMs >= FIXTURE_DURATION_SECONDS * 1_000L
            }
        }
        val playPause = composeTestRule.onNodeWithTag("player_play_pause_button")
        playPause
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Відтворюється"
                )
            )
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .performClick()
        composeTestRule.waitUntil(timeoutMillis = PLAYBACK_TIMEOUT_MS) {
            !currentViewModel().playerState.value.isPlaying
        }
        playPause.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Призупинено"
            )
        )

        val progress = composeTestRule.onNodeWithTag("player_progress_slider")
        progress
            .assertContentDescriptionEquals("Позиція в поточному розділі")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(0.5f)
            }
        composeTestRule.waitUntil(timeoutMillis = PLAYBACK_TIMEOUT_MS) {
            currentViewModel().playerState.value.currentPositionMs in 14_500L..15_500L
        }
        progress.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "00:15 із 00:30"
            )
        )
        composeTestRule.onRoot().tryPerformAccessibilityChecks()

        val speedTrigger = composeTestRule.onNodeWithTag("speed_chip")
        speedTrigger.performClick()
        composeTestRule.waitUntilExactlyOneExists(
            hasTestTag("speed_sheet"),
            timeoutMillis = NAV_TIMEOUT_MS
        )
        waitUntilFocused("speed_sheet_heading")
        composeTestRule.onNodeWithTag("speed_sheet").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.PaneTitle,
                "Швидкість відтворення"
            )
        )
        composeTestRule.onNodeWithTag("speed_preset_1.0")
            .assertIsSelected()
        composeTestRule.onNodeWithTag("speed_preset_1.25")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .assertContentDescriptionEquals("Швидкість 1,25 раза")
            .performClick()
            .assertIsSelected()
        composeTestRule.waitUntil(timeoutMillis = PLAYBACK_TIMEOUT_MS) {
            kotlin.math.abs(currentViewModel().playerState.value.playbackSpeed - 1.25f) < 0.01f
        }
        composeTestRule.onRoot().tryPerformAccessibilityChecks()
        composeTestRule.onNodeWithContentDescription("Закрити налаштування швидкості")
            .performClick()
        waitUntilGone("speed_sheet")
        waitUntilFocused("speed_chip")

        val timerTrigger = composeTestRule.onNodeWithTag("sleep_timer_chip")
        timerTrigger.performClick()
        composeTestRule.waitUntilExactlyOneExists(
            hasTestTag("sleep_timer_sheet"),
            timeoutMillis = NAV_TIMEOUT_MS
        )
        waitUntilFocused("sleep_timer_heading")
        composeTestRule.onNodeWithTag("sleep_timer_heading")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Вимкнено"
                )
            )
        composeTestRule.onNodeWithTag("sleep_timer_option_0")
            .assertIsSelected()
        composeTestRule.onRoot().tryPerformAccessibilityChecks()
        composeTestRule.onNodeWithTag("sleep_timer_option_5")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .performClick()
        composeTestRule.waitUntil(timeoutMillis = PLAYBACK_TIMEOUT_MS) {
            currentViewModel().playerState.value.run {
                sleepTimerMinutes == 5 && sleepTimerRemainingSeconds > 0
            }
        }
        waitUntilGone("sleep_timer_sheet")
        waitUntilFocused("sleep_timer_chip")
        composeTestRule.onRoot().tryPerformAccessibilityChecks()

        val bookmarkTrigger = composeTestRule.onNodeWithTag("add_bookmark_chip")
        bookmarkTrigger.performClick()
        composeTestRule.waitUntilExactlyOneExists(
            hasTestTag("bookmark_sheet"),
            timeoutMillis = NAV_TIMEOUT_MS
        )
        waitUntilFocused("bookmark_sheet_heading")
        composeTestRule.onNodeWithTag("bookmark_sheet").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.PaneTitle,
                "Додати закладку"
            )
        )
        composeTestRule.onNodeWithTag("bookmark_position_context")
            .assert(
                SemanticsMatcher("bookmark chapter and formatted position") { node ->
                    node.config.getOrNull(SemanticsProperties.ContentDescription)
                        ?.singleOrNull()
                        ?.matches(
                            Regex("Закладка в розділі «Дія перша» на позиції \\d{2}:\\d{2}")
                        ) == true
                }
            )
        composeTestRule.onRoot().tryPerformAccessibilityChecks()
        composeTestRule.onNodeWithTag("save_bookmark_button")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .performClick()
        waitUntilGone("bookmark_sheet")
        waitUntilFocused("add_bookmark_chip")
        composeTestRule.waitUntil(timeoutMillis = NAV_TIMEOUT_MS) {
            composeTestRule.onAllNodes(
                SemanticsMatcher("bookmark confirmation") { node ->
                    node.config.getOrNull(SemanticsProperties.Text)
                        ?.any { it.text.startsWith("Закладку додано на") } == true
                }
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Закладку додано на", substring = true)
            .assertIsDisplayed()
        composeTestRule.onRoot().tryPerformAccessibilityChecks()

        composeTestRule.onNodeWithTag("close_player_button")
            .performClick()
        composeTestRule.waitUntil(timeoutMillis = NAV_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("full_player_screen")
                .fetchSemanticsNodes()
                .isEmpty()
        }
        try {
            composeTestRule.waitUntil(timeoutMillis = NAV_TIMEOUT_MS) {
                composeTestRule.onAllNodesWithTag("book_detail_chapter_$fixtureChapterId")
                    .fetchSemanticsNodes()
                    .singleOrNull()
                    ?.config
                    ?.getOrNull(SemanticsProperties.Focused) == true
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            // This gate uses a generated fixture in an isolated emulator.
            // Preserve the actual tree when focus restoration fails;
            // the assertion still fails unchanged.
            Log.e("AccessibilityFocusReturn", composeTestRule.onRoot(useUnmergedTree = true).printToString())
            throw failure
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

        composeTestRule.onNodeWithTag("book_detail_back_button")
            .performClick()
        composeTestRule.waitUntil(timeoutMillis = NAV_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("library_book_item_$fixtureBookId")
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.config
                ?.getOrNull(SemanticsProperties.Focused) == true
        }
        composeTestRule.onNodeWithTag("library_book_item_$fixtureBookId")
            .assertIsFocused()
        composeTestRule.onRoot().tryPerformAccessibilityChecks()

        composeTestRule.onNodeWithTag("library_overflow_button")
            .performClick()
        composeTestRule.onNodeWithTag("library_profile_menu_item")
            .performClick()
        composeTestRule.waitUntil(timeoutMillis = NAV_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("profile_screen_heading", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.config
                ?.getOrNull(SemanticsProperties.Focused) == true
        }
        composeTestRule.onNodeWithTag("profile_screen_heading", useUnmergedTree = true)
            .assertIsFocused()
        composeTestRule.onNodeWithContentDescription("Назад")
            .performClick()
        composeTestRule.waitUntil(timeoutMillis = NAV_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag("library_overflow_button")
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.config
                ?.getOrNull(SemanticsProperties.Focused) == true
        }
        composeTestRule.onNodeWithTag("library_overflow_button")
            .assertIsFocused()
        composeTestRule.onRoot().tryPerformAccessibilityChecks()
    }

    private fun currentViewModel(): MainViewModel =
        ViewModelProvider(composeTestRule.activity)[MainViewModel::class.java]

    private fun waitUntilFocused(testTag: String) {
        composeTestRule.waitUntil(timeoutMillis = NAV_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag(testTag)
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.config
                ?.getOrNull(SemanticsProperties.Focused) == true
        }
        composeTestRule.onNodeWithTag(testTag).assertIsFocused()
    }

    private fun waitUntilGone(testTag: String) {
        composeTestRule.waitUntil(timeoutMillis = NAV_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag(testTag)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    private fun writeSilentWav(target: File, durationSeconds: Int) {
        val sampleRate = 8_000
        val channelCount = 1
        val bitsPerSample = 16
        val bytesPerSample = bitsPerSample / 8
        val dataSize = sampleRate * durationSeconds * channelCount * bytesPerSample
        target.outputStream().buffered().use { output ->
            output.writeAscii("RIFF")
            output.writeLittleEndianInt(36 + dataSize)
            output.writeAscii("WAVE")
            output.writeAscii("fmt ")
            output.writeLittleEndianInt(16)
            output.writeLittleEndianShort(1)
            output.writeLittleEndianShort(channelCount)
            output.writeLittleEndianInt(sampleRate)
            output.writeLittleEndianInt(sampleRate * channelCount * bytesPerSample)
            output.writeLittleEndianShort(channelCount * bytesPerSample)
            output.writeLittleEndianShort(bitsPerSample)
            output.writeAscii("data")
            output.writeLittleEndianInt(dataSize)

            val silence = ByteArray(8_192)
            var remaining = dataSize
            while (remaining > 0) {
                val count = minOf(remaining, silence.size)
                output.write(silence, 0, count)
                remaining -= count
            }
        }
    }

    private fun OutputStream.writeAscii(value: String) {
        write(value.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun OutputStream.writeLittleEndianInt(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
        write(value ushr 16 and 0xff)
        write(value ushr 24 and 0xff)
    }

    private fun OutputStream.writeLittleEndianShort(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
    }

    companion object {
        private const val FIXTURE_DURATION_SECONDS = 30L
        private const val NAV_TIMEOUT_MS = 20_000L
        private const val PLAYBACK_TIMEOUT_MS = 5_000L
    }
}
