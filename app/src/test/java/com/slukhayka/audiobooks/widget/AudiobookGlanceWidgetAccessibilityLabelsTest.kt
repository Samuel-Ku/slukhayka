package com.slukhayka.audiobooks.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudiobookGlanceWidgetAccessibilityLabelsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun transportActionsNameTheExactWorkAndPlaybackActionInUkrainian() {
        val paused = widgetAccessibilityLabels(
            context,
            GlanceWidgetState(title = "Тіні забутих предків", isPlaying = false)
        )
        val playing = widgetAccessibilityLabels(
            context,
            GlanceWidgetState(title = "Тіні забутих предків", isPlaying = true)
        )

        assertEquals("Перемотати «Тіні забутих предків» на 15 секунд назад", paused.rewind)
        assertEquals("Відтворити «Тіні забутих предків»", paused.playPause)
        assertEquals("Поставити «Тіні забутих предків» на паузу", playing.playPause)
        assertEquals("Перемотати «Тіні забутих предків» на 15 секунд уперед", playing.forward)
    }

    @Test
    fun theClickableRootOpensTheAppAndIsNamedForTalkBack() {
        val labels = widgetAccessibilityLabels(context, GlanceWidgetState())

        assertEquals("Відкрити Слухайку", labels.openApp)
    }

    @Test
    fun anEmptyWidgetHasNoTransportButtonsToAnnounce() {
        assertFalse(GlanceWidgetState().showsTransportControls())
        assertTrue(GlanceWidgetState(hasActiveBook = true).showsTransportControls())
    }
}
