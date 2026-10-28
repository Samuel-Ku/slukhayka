package com.slukhayka.audiobooks.ui.theme

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * F2 of the 2026-10-28 device audit — the window itself must receive the
 * polarity, not only the pure decision: a composable that computes the right
 * answer but never writes it leaves white glyphs on paper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SystemBarsWindowTest {

    @Test
    fun `light app theme turns the system glyphs dark`() {
        val controller = applyToWindow(darkTheme = false)
        assertTrue(controller.isAppearanceLightStatusBars)
        assertTrue(controller.isAppearanceLightNavigationBars)
    }

    @Test
    fun `dark app theme turns the system glyphs light`() {
        val controller = applyToWindow(darkTheme = true)
        assertFalse(controller.isAppearanceLightStatusBars)
        assertFalse(controller.isAppearanceLightNavigationBars)
    }

    private fun applyToWindow(darkTheme: Boolean): WindowInsetsControllerCompat {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { PaperInkSystemBars(darkTheme = darkTheme) }
        shadowOf(Looper.getMainLooper()).idle()
        return WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    }
}
