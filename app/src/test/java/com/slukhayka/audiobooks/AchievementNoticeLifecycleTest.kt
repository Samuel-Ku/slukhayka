package com.slukhayka.audiobooks

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isDisplayed
import androidx.lifecycle.ViewModelProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.SelectedTab
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import androidx.lifecycle.Lifecycle
import androidx.room.RoomDatabase
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.achievements.AchievementFact
import com.slukhayka.audiobooks.ui.achievements.achievementNotice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real App startup, Room, lifecycle and Material snackbar; no global or private test override. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = App::class)
class AchievementNoticeLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun `inactive award waits for resume and activity recreation never reannounces it`() {
        val app = App.instance
        val database: RoomDatabase = AudiobookDatabase.getDatabase(app)
        app.firstLanguageChoice.keepAll()
        app.crashReporting.denyTriggeringReport()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        app.recordAchievementFact(AchievementFact.NOT_INTERESTED_CHOSEN)
        compose.waitUntil(15_000L) {
            runBlocking { app.achievementStore.earned().any { it.id == "first_not_interested" } }
        }
        assertTrue(database.isOpen)
        val text = achievementNotice(app, "first_not_interested")
        assertNull(runBlocking { app.achievementStore.earned().single { it.id == "first_not_interested" }.seenAt })
        compose.onNodeWithText(text).assertDoesNotExist()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(15_000L) {
            runBlocking { app.achievementStore.earned().single { it.id == "first_not_interested" }.seenAt != null }
        }
        compose.mainClock.advanceTimeBy(500L)
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
        compose.onNodeWithText(text).assertDoesNotExist()
        assertNotNull(runBlocking { app.achievementStore.earned().single { it.id == "first_not_interested" }.seenAt })
    }

    @Config(qualifiers = "w411dp-h891dp")
    @Test fun `visible award leaves the library tab physically reachable`() {
        val app = App.instance
        val vm = prepareNoticeJourney()
        compose.mainClock.autoAdvance = false
        app.recordAchievementFact(AchievementFact.REVIEW_ACCEPTED)
        waitForAward("first_review", seen = true)
        val text = achievementNotice(app, "first_review")
        waitUiFrame { compose.onNodeWithText(text).isDisplayed() }
        compose.onNodeWithText(text).assertIsDisplayed()

        // Android performClick dispatches a physical touch at the node centre;
        // invoking the semantics OnClick directly would bypass an overlay.
        waitUiFrame { physicalTargetReady("tab_library") }
        assertPhysicalTargetReady("tab_library")
        compose.onNodeWithTag("tab_library").performClick()
        compose.waitForIdle()
        assertEquals("A visible award must not intercept the library tab", SelectedTab.LIBRARY, vm.selectedTab.value)
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    @Config(qualifiers = "w411dp-h891dp")
    @Test fun `player tools stay reachable and its unclaimed award appears once after exit`() {
        val app = App.instance
        val vm = prepareNoticeJourney()
        val audio = app.filesDir.resolve("achievement-notice-controls.wav")
        // Real local PCM keeps this paused preparation off the network. No
        // player override or synthetic UI state is needed to open the tools.
        val dataSize = 8_000 * 30 * 2
        val wav = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1.toShort()).putShort(1.toShort()).putInt(8_000)
            .putInt(16_000).putShort(2.toShort()).putShort(16.toShort())
            .put("data".toByteArray()).putInt(dataSize)
        audio.writeBytes(wav.array())
        var journeyFailure: Throwable? = null
        try {
            val book = AudiobookEntity("notice-controls", "Notice controls", "Author", "Narrator", "", 0,
                genre = "", sourceUrl = "", totalDurationSeconds = 30L, totalChapters = 1)
            val chapter = ChapterEntity("notice-controls-chapter", book.id, 0, "Chapter", 30L)
            val track = SourceTrackEntity("notice-controls-track", "notice-controls-source", 0,
                audio.absolutePath, localFilePath = audio.absolutePath)
            compose.runOnIdle {
                vm.playerManager.loadAndPlayBook(book, listOf(chapter),
                    playable = listOf(SourceCatalog.PlayableChapter(chapter, track)), autoPlay = false)
                vm.setShowFullPlayer(true)
            }
            waitUiFrame {
                compose.onNodeWithTag("full_player_screen").isDisplayed() &&
                    compose.onNodeWithTag("speed_chip").isDisplayed()
            }
            compose.onNodeWithTag("full_player_screen").assertIsDisplayed()
            compose.mainClock.autoAdvance = false
            app.recordAchievementFact(AchievementFact.SEARCH_IMPORTED)
            waitForAward("first_search_import", seen = null)

            waitUiFrame { physicalTargetReady("speed_chip") }
            assertPhysicalTargetReady("speed_chip")
            compose.onNodeWithTag("speed_chip").performClick()
            waitUiFrame {
                compose.onNodeWithTag("speed_sheet").isDisplayed() &&
                    compose.onNodeWithTag("speed_sheet_heading").isDisplayed()
            }
            compose.onNodeWithTag("speed_sheet").assertIsDisplayed()
            val text = achievementNotice(app, "first_search_import")
            compose.onNodeWithText(text).assertDoesNotExist()
            assertNull("Player awards wait in Room until the overlay exits",
                runBlocking { app.achievementStore.earned().single { it.id == "first_search_import" }.seenAt })
            compose.onNodeWithContentDescription(app.getString(R.string.a11y_speed_close)).performClick()
            waitUiFrame { compose.onAllNodesWithTag("speed_sheet").fetchSemanticsNodes().isEmpty() }
            compose.runOnIdle { vm.setShowFullPlayer(false) }
            waitUiFrame { compose.onAllNodesWithTag("full_player_screen").fetchSemanticsNodes().isEmpty() }
            waitForAward("first_search_import", seen = true)
            waitUiFrame { compose.onNodeWithText(text).isDisplayed() }
            compose.onNodeWithText(text).assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            waitUiFrame { compose.onNodeWithTag("app_background", useUnmergedTree = true).isDisplayed() }
            compose.waitForIdle()
            compose.onNodeWithText(text).assertDoesNotExist()
            assertNotNull(runBlocking { app.achievementStore.earned().single { it.id == "first_search_import" }.seenAt })
        } catch (failure: Throwable) {
            journeyFailure = failure
            throw failure
        } finally {
            // Keep the player mounted until its dialog/exit frames finish;
            // clearing its book first would dispose the modal focus target.
            val cleanup = runCatching {
                if (compose.onAllNodesWithTag("speed_sheet").fetchSemanticsNodes().isNotEmpty()) {
                    waitUiFrame {
                        compose.onNodeWithContentDescription(app.getString(R.string.a11y_speed_close)).isDisplayed()
                    }
                    compose.onNodeWithContentDescription(app.getString(R.string.a11y_speed_close)).performClick()
                    waitUiFrame { compose.onAllNodesWithTag("speed_sheet").fetchSemanticsNodes().isEmpty() }
                }
                compose.runOnIdle { vm.setShowFullPlayer(false) }
                waitUiFrame { compose.onAllNodesWithTag("full_player_screen").fetchSemanticsNodes().isEmpty() }
                compose.runOnIdle { app.playerManager.stopAndClear() }
            }
            audio.delete()
            cleanup.exceptionOrNull()?.let { failure ->
                val originalFailure = journeyFailure ?: throw failure
                originalFailure.addSuppressed(failure)
            }
        }
    }

    private fun prepareNoticeJourney(): MainViewModel {
        val app = App.instance
        app.firstLanguageChoice.keepAll()
        app.crashReporting.denyTriggeringReport()
        val vm = compose.runOnIdle { ViewModelProvider(compose.activity)[MainViewModel::class.java] }
        compose.runOnIdle {
            vm.selectBook(null)
            vm.setShowFullPlayer(false)
            vm.selectTab(SelectedTab.LISTEN)
        }
        compose.waitForIdle()
        return vm
    }

    private fun assertPhysicalTargetReady(tag: String) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val bounds = node.boundsInWindow
        val decor = compose.activity.window.decorView
        assertTrue("$tag is clipped: bounds=$bounds, size=${node.size}",
            bounds.width >= node.size.width - 0.5f && bounds.height >= node.size.height - 0.5f)
        assertTrue("$tag is outside the real window: $bounds in ${decor.width}x${decor.height}",
            bounds.left >= 0f && bounds.top >= 0f && bounds.right <= decor.width && bounds.bottom <= decor.height)
        val centre = Offset(node.positionInWindow.x + node.size.width / 2f,
            node.positionInWindow.y + node.size.height / 2f)
        assertTrue("$tag touch centre must be inside its rendered bounds", bounds.contains(centre))
    }

    private fun physicalTargetReady(tag: String): Boolean {
        if (!compose.onNodeWithTag(tag).isDisplayed()) return false
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val bounds = node.boundsInWindow
        val decor = compose.activity.window.decorView
        return bounds.width >= node.size.width - 0.5f && bounds.height >= node.size.height - 0.5f &&
            bounds.left >= 0f && bounds.top >= 0f && bounds.right <= decor.width && bounds.bottom <= decor.height
    }

    /** Drive real composition/layout frames while Room and dialog effects settle. */
    private fun waitUiFrame(predicate: () -> Boolean) {
        compose.waitUntil(15_000L) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            predicate()
        }
    }

    private fun waitForAward(id: String, seen: Boolean?) {
        waitUiFrame {
            runBlocking { App.instance.achievementStore.earned().singleOrNull { it.id == id } }
                ?.let { row -> seen == null || (row.seenAt != null) == seen } == true
        }
    }

}
