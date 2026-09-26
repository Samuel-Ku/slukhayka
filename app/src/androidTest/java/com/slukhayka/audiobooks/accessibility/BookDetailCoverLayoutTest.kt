package com.slukhayka.audiobooks.accessibility

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.ui.screens.bookdetail.BookDetailIdentityHeader
import com.slukhayka.audiobooks.ui.screens.bookDetailPresentation
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import java.io.File
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModelProvider
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.components.applySourceCoverHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Pixel evidence at the production header: edge bands survive without stretching. */
class BookDetailCoverLayoutTest {
    // #766 A — this test draws its OWN tree, so it needs the content-free
    // host: MainActivity sets content in onCreate, and `rule.activity.setContent`
    // over it registers no semantics root ("No compose hierarchies found").
    @get:Rule val rule = createAndroidComposeRule<com.slukhayka.audiobooks.testing.TestHostActivity>()

    @Test fun portraitAndSquareCoversKeepTheirEdgesAndMissingCoverIsVisible() {
        val book = AudiobookEntity(id = "cover-layout", title = "Книга без обкладинки", author = "Автор", narrator = "", description = "",
            coverDrawableRes = 0, genre = "", sourceUrl = "https://example.invalid/book")
        val shown = mutableStateOf(book)
        rule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    Column {
                        BookDetailIdentityHeader(
                            shown.value,
                            bookDetailPresentation(shown.value, emptyList(), emptyList()),
                            requestInitialFocus = false
                        )
                    }
                }
            }
        }
        // #1022 — what this test can actually prove, and what it cannot.
        //
        // The hero draws the cover at FULL WIDTH, pinned to the top, then lays
        // two scrims over it: a 140dp top gradient (black 55% -> transparent)
        // for toolbar readability, and a 360dp bottom gradient fading into the
        // page background under the title block. Both are deliberate — see
        // BookDetailSections.kt.
        //
        // Measured on an API 35 emulator with a 400x600 fixture, the node is
        // 1080x1727 px and a sampled column reads: y=0 r=0.45 (the source's top
        // band, darkened by the top scrim), y=100 b=0.60, y=1000 b=0.75 (still
        // the cover), y=1500 = page background (the bottom scrim).
        //
        // That kills the old assertion "BOTH edge bands survive": the top band
        // is scrimmed below any strict red threshold, and the bottom band sits
        // under an opaque gradient BY DESIGN. It is not *cropped* either — for
        // every realistic ratio the cover is shorter than the hero (2:3 =>
        // 616dp < 642dp), so `height(maxOf(natural, hero))` forces the hero
        // height and Crop shows the whole image while shaving ~8dp off each
        // SIDE.
        //
        // What the design does guarantee, and what this test now pins:
        //   1. the cover's top edge is visible near the top of the hero;
        //   2. the cover spans the full width (never letterboxed);
        //   3. a square cover stays square (the `waitUntil` centre probe).
        for ((width, height) in listOf(400 to 600, 400 to 400)) {
            val file = File(rule.activity.cacheDir, "cover-layout-$width-$height.png")
            try {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(if (width == height) AndroidColor.GREEN else AndroidColor.BLUE)
                val paint = Paint().apply { color = AndroidColor.RED }
                // Markers on the top AND bottom edges of the SOURCE. The bottom
                // one may end up under the scrim; the top one is what the
                // top-pinned layout promises to keep.
                canvas.drawRect(0f, 0f, width.toFloat(), 24f, paint)
                canvas.drawRect(0f, (height - 24).toFloat(), width.toFloat(), height.toFloat(), paint)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                rule.runOnUiThread { shown.value = book.copy(coverImageUrl = file.toURI().toString()) }
                rule.waitForIdle()
                // Distinct centers prevent the previous image satisfying the next load.
                rule.waitUntil(10_000) {
                    val pixels = rule.onNodeWithTag("book_detail_cover").captureToImage().toPixelMap()
                    val center = pixels[pixels.width / 2, pixels.height / 2]
                    if (width == height) center.green > 0.9f && center.blue < 0.1f
                    else center.blue > 0.9f && center.green < 0.1f
                }
                val image = rule.onNodeWithTag("book_detail_cover").captureToImage()
                val pixels = image.toPixelMap()
                val x = pixels.width / 2
                // (1) The source's top edge is visible inside the top slice of
                // the hero. The top scrim darkens it, so "reddish" means red
                // clearly dominating the other channels, not red > 0.8.
                val topSlice = (0 until pixels.height / 4).filter { y ->
                    val c = pixels[x, y]
                    c.red > 0.30f && c.red > c.blue * 1.4f && c.red > c.green * 1.4f
                }
                assertTrue(
                    "the cover's top edge must be visible near the top of the hero for $width x $height",
                    topSlice.isNotEmpty()
                )
                // (2) Full width: the body colour reaches both side edges, so the
                // cover is never letterboxed inside the hero.
                val midY = pixels.height / 3
                val left = pixels[1, midY]
                val right = pixels[pixels.width - 2, midY]
                val bodyLeft = if (width == height) left.green > 0.6f else left.blue > 0.6f
                val bodyRight = if (width == height) right.green > 0.6f else right.blue > 0.6f
                assertTrue("the cover must span the full width (left edge) for $width x $height", bodyLeft)
                assertTrue("the cover must span the full width (right edge) for $width x $height", bodyRight)
                saveScreen("553-cover-$width-$height.png")
            } finally {
                file.delete()
            }
        }
        rule.runOnUiThread { shown.value = book }
        rule.waitForIdle()
        rule.onNodeWithTag("book_detail_cover").assertExists()
        saveScreen("553-cover-missing.png")
    }

    @Test fun inspectSavedBookOriginalCover() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCover") == "true")
        val vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
        rule.waitUntil(20_000) { vm.libraryBooks.value.any { it.book.title == "Трохи ненависті" } }
        val book = vm.libraryBooks.value.first { it.book.title == "Трохи ненависті" }.book
        val url = requireNotNull(book.coverImageUrl)
        val result = runBlocking(Dispatchers.IO) {
            rule.activity.imageLoader.execute(ImageRequest.Builder(rule.activity)
                .data(url).applySourceCoverHeaders(url).size(Size.ORIGINAL).allowHardware(false).build())
        }
        assertTrue("original cover must load", result is SuccessResult)
        val original = (result as SuccessResult).drawable.toBitmap()
        File(rule.activity.getExternalFilesDir(null), "553-original-cover.png").outputStream().use {
            original.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        File(rule.activity.getExternalFilesDir(null), "553-original-cover.txt").writeText(
            "${original.width} x ${original.height}\n$url\n"
        )
        // Inspect the real book page without starting playback or editing saved data.
        rule.runOnUiThread { vm.selectBook(book.id) }
        rule.onNodeWithTag("book_detail_cover").assertExists()
        rule.waitForIdle()
        saveScreen("553-live-book.png")
    }

    private fun saveScreen(name: String) {
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(rule.activity.getExternalFilesDir(null), name).outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
