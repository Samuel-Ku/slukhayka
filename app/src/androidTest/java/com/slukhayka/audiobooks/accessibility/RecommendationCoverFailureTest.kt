package com.slukhayka.audiobooks.accessibility

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.request.ErrorResult
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.data.recommend.RecommendationEngine
import com.slukhayka.audiobooks.ui.screens.RecommendedBookCard
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Renders a failed cover without modifying the listener's library or network settings. */
class RecommendationCoverFailureTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun failedCoverKeepsRecommendationReadableAndClickable() {
        val failed = AtomicBoolean(false)
        val opened = AtomicBoolean(false)
        val loader = ImageLoader.Builder(rule.activity).components {
            add(coil.intercept.Interceptor { chain ->
                failed.set(true)
                ErrorResult(null, chain.request, IOException("Controlled cover failure"))
            })
        }.build()
        try {
            rule.activity.runOnUiThread {
                rule.activity.setContent {
                    CompositionLocalProvider(LocalImageLoader provides loader) {
                        AudiobookTheme(darkTheme = true) {
                            RecommendedBookCard(
                                rec = RecommendationEngine.Recommendation(
                                    candidate = RecommendationEngine.Candidate(
                                        id = "cover-failure-fixture", title = "Absolute Evil",
                                        author = "Julian Hawthorne", coverImageUrl = "https://cover.invalid/test.jpg"
                                    ), score = 0.8, reasonTitle = "Інші"
                                ), onClick = { opened.set(true) }
                            )
                        }
                    }
                }
            }
            rule.waitUntil(10_000) { failed.get() }
            rule.waitForIdle()
            rule.onNodeWithTag("recommended_cover-failure-fixture").performClick()
            assertTrue(opened.get())
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(rule.activity.getExternalFilesDir(null), "545-cover-failure.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        } finally {
            loader.shutdown()
        }
    }
}
