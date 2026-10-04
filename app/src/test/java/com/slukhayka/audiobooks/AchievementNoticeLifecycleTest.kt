package com.slukhayka.audiobooks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
}
