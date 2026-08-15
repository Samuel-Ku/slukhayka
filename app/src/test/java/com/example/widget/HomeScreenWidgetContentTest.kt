package com.example.widget

import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.assertHasClickAction
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-17 (#110): the widget's render tree with a fixed snapshot — pins what
 * the launcher shows for each playback state (book/paused/playing/empty).
 * The pure state mapping itself is covered by [WidgetModelMapperTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeScreenWidgetContentTest {

    private fun playingSnapshot() = SessionSnapshot(
        title = "Моя книга",
        isPlaying = true,
        positionMs = 100_000,
        durationMs = 1_000_000,
        hasBook = true,
    )

    private fun pausedSnapshot() = SessionSnapshot(
        title = "Моя книга",
        isPlaying = false,
        positionMs = 0,
        durationMs = 1_000_000,
        hasBook = true,
    )

    private fun noBookSnapshot() = SessionSnapshot(
        title = null,
        isPlaying = false,
        positionMs = 0,
        durationMs = 0,
        hasBook = false,
    )

    @Test
    fun `book rendering - title, progress, pause icon, transport actions`() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        provideComposable { HomeScreenWidgetContent(snapshot = playingSnapshot()) }
        awaitIdle()

        // The book area (title) opens the player. (glance's ComponentName-based
        // start-activity matcher only understands class-based actions; the
        // intent variant is covered by WidgetIntents + the device check.)
        onNode(hasTextEqualTo("Моя книга"))
            .assertExists()
            .assertHasClickAction()

        // Playing → the toggle shows the pause glyph.
        onNode(hasContentDescriptionEqualTo("Пауза")).assertExists().assertHasClickAction()
        onNode(hasContentDescriptionEqualTo("Попередній розділ")).assertExists().assertHasClickAction()
        onNode(hasContentDescriptionEqualTo("Наступний розділ")).assertExists().assertHasClickAction()

        // The play glyph must not be present while playing.
        onNode(hasContentDescriptionEqualTo("Грати")).assertDoesNotExist()
    }

    @Test
    fun `paused book - play glyph instead of pause`() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        provideComposable { HomeScreenWidgetContent(snapshot = pausedSnapshot()) }
        awaitIdle()

        onNode(hasTextEqualTo("Моя книга")).assertExists()
        onNode(hasContentDescriptionEqualTo("Грати")).assertExists().assertHasClickAction()
        onNode(hasContentDescriptionEqualTo("Пауза")).assertDoesNotExist()
    }

    @Test
    fun `no book - neutral placeholder, no controls, no open-player action`() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        provideComposable { HomeScreenWidgetContent(snapshot = noBookSnapshot()) }
        awaitIdle()

        onNode(hasTextEqualTo("Нічого не грає")).assertExists()
        onNode(hasContentDescriptionEqualTo("Пауза")).assertDoesNotExist()
        onNode(hasContentDescriptionEqualTo("Грати")).assertDoesNotExist()
    }
}